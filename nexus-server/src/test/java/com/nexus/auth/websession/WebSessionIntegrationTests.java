package com.nexus.auth.websession;

import com.nexus.auth.dto.LoginRequest;
import com.nexus.auth.service.*;
import com.nexus.auth.token.RefreshTokenGenerator;
import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.auth.exception.*;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"nexus.auth.session.cleanup-enabled=false", "nexus.auth.web.allowed-origins[0]=https://console.example.test", "nexus.auth.web.secure-cookie=true", "nexus.auth.web.cookie-name=__Host-nexus_rt"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class WebSessionIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthSessionService sessions;
    @Autowired LoginService login;
    @Autowired WebLogoutService logout;
    @Autowired RefreshTokenGenerator generator;
    @Autowired BearerUserIdResolver bearer;
    User user;
    @BeforeEach void setUp() {
        assertEquals("nexus_test",jdbc.queryForObject("SELECT DATABASE()",String.class));
        user = new User(); user.setEmail("web-session-"+UUID.randomUUID()+"@example.test"); user.setDisplayName("web test");
        user.setPasswordHash(passwords.encode("test-only-password")); users.insert(user);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE r FROM refresh_tokens r JOIN auth_sessions s ON s.id=r.session_id WHERE s.user_id=?",user.getId());
        jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",user.getId()); users.deleteById(user.getId());
    }
    MockHttpServletRequestBuilder web(String path,String body) {
        return post("/api/auth/"+path).header(WebSessionPolicy.HEADER,"cookie").header("Origin","https://console.example.test")
                .header("Sec-Fetch-Site","same-origin").contentType("application/json").content(body);
    }
    org.springframework.mock.web.MockHttpServletResponse webLogin() throws Exception {
        return mvc.perform(web("login",json.writeValueAsString(new LoginRequest(user.getEmail(),"test-only-password"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refreshToken").doesNotExist()).andReturn().getResponse();
    }
    String rt(org.springframework.mock.web.MockHttpServletResponse response) { return response.getCookie("__Host-nexus_rt").getValue(); }
    String field(org.springframework.mock.web.MockHttpServletResponse response,String name) { return json.readTree(response.getContentAsByteArray()).path("data").path(name).asText(); }

    @Test void webLoginRefreshAndLogoutRevokeRealSessionWhileJsonTransportRemainsIndependent() throws Exception {
        var desktop = login.login(new LoginRequest(user.getEmail(),"test-only-password"));
        var first = webLogin(); String sid=field(first,"sessionId");
        var next=mvc.perform(web("refresh","{\"expectedSessionId\":\""+sid+"\"}").cookie(new Cookie("__Host-nexus_rt",rt(first))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").value(sid))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist()).andReturn().getResponse();
        assertFalse(Objects.equals(rt(first),rt(next)),"Refresh credential must rotate"); assertEquals(field(first,"sessionExpiresAt"),field(next,"sessionExpiresAt"));
        assertEquals(user.getId().longValue(),bearer.resolve("Bearer "+field(first,"accessToken")));
        // Consumed RT is a logout proof, never a refresh retry.
        for (int i=0;i<2;i++) mvc.perform(web("logout","{\"expectedSessionId\":\""+sid+"\"}").cookie(new Cookie("__Host-nexus_rt",rt(first))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serverRevocation").value("confirmed"));
        assertThrows(InvalidAccessTokenException.class,()->bearer.resolve("Bearer "+field(next,"accessToken")));
        assertThrows(InvalidRefreshTokenException.class,()->sessions.refresh(rt(next)));
        assertDoesNotThrow(()->sessions.refresh(desktop.refreshToken()));
    }

    @Test void sessionMismatchDoesNotConsumeOrRevokeAnotherCookieSession() throws Exception {
        var first=webLogin(); String token=rt(first);
        mvc.perform(web("refresh","{\"expectedSessionId\":\"1\"}").cookie(new Cookie("__Host-nexus_rt",token))).andExpect(status().isUnauthorized());
        assertDoesNotThrow(()->sessions.refresh(token));
        assertThrows(InvalidRefreshTokenException.class,()->logout.logout(token,"1"));
        assertEquals(user.getId().longValue(),bearer.resolve("Bearer "+field(first,"accessToken")));
    }

    @Test void rejectedCsrfRequestsHaveNoCookieOrDatabaseEffects() throws Exception {
        var first=webLogin(); String token=rt(first);
        mvc.perform(web("refresh","{}").header("Origin","https://evil.example.test").cookie(new Cookie("__Host-nexus_rt",token)))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        assertDoesNotThrow(()->sessions.refresh(token));
    }

    @Test void jsonRefreshNeverUsesCookieOrSetsCookie() throws Exception {
        var first=webLogin();
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("__Host-nexus_rt",rt(first))).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Set-Cookie"));
        assertDoesNotThrow(()->sessions.refresh(rt(first)));
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(new LoginRequest(user.getEmail(),"test-only-password"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refreshToken").isString()).andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test void unknownLogoutProofCannotRevokeKnownSessions() throws Exception {
        var first=webLogin();
        mvc.perform(web("logout","{}").cookie(new Cookie("__Host-nexus_rt",generator.generate().value())))
                .andExpect(status().isUnauthorized()).andExpect(header().exists("Set-Cookie"));
        assertEquals(user.getId().longValue(),bearer.resolve("Bearer "+field(first,"accessToken")));
    }
}
