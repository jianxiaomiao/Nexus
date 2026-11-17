package com.nexus.auth.websession;

import com.nexus.auth.dto.AuthTokensResponse;
import com.nexus.auth.service.*;
import com.nexus.common.web.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebSessionPolicyTests {
    final LoginService login = mock(LoginService.class);
    final AuthSessionService sessions = mock(AuthSessionService.class);
    final WebLogoutService logout = mock(WebLogoutService.class);
    final WebSessionPolicy policy = new WebSessionPolicy(new WebSessionProperties(true, "__Host-nexus_rt", List.of("https://console.example.test")), new MockEnvironment());
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WebAuthController(policy, login, sessions, logout))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    final AuthTokensResponse tokens = new AuthTokensResponse("test-at", "Bearer", 600, "test-rt", "123", Instant.parse("2026-11-18T00:00:00Z"));

    @Test void cookieHasProductionBoundariesAndBodyOmitsRefreshCredential() throws Exception {
        when(login.login(any())).thenReturn(tokens);
        var response = mvc.perform(post("/api/auth/login").header(WebSessionPolicy.HEADER, "cookie")
                        .header("Origin", "https://console.example.test").header("Sec-Fetch-Site", "same-origin")
                        .contentType("application/json").content("{\"email\":\"a@example.test\",\"password\":\"test-only-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.sessionId").value("123")).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("__Host-nexus_rt=test-rt"));
        for (String flag : List.of("Path=/", "Secure", "HttpOnly", "SameSite=Strict")) assertTrue(cookie.contains(flag));
        assertFalse(cookie.contains("Domain=")); assertFalse(cookie.contains("Max-Age="));
        assertFalse(response.getContentAsString().contains("test-rt"));
    }

    @Test void untrustedOrMissingOriginAndCrossSiteRequestsCannotReadOrClearCookies() throws Exception {
        for (String origin : List.of("", "null", "https://evil.example.test", "https://console.example.test.evil.test")) {
            mvc.perform(post("/api/auth/refresh").header(WebSessionPolicy.HEADER, "cookie").header("Origin", origin)
                            .contentType("application/json").cookie(new Cookie("__Host-nexus_rt", "test-rt")).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        }
        for (String site : List.of("cross-site", "same-site")) {
            mvc.perform(post("/api/auth/logout").header(WebSessionPolicy.HEADER, "cookie").header("Origin", "https://console.example.test")
                            .header("Sec-Fetch-Site", site).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        }
        verifyNoInteractions(login, sessions, logout);
    }

    @Test void cookieModeRejectsBodyRefreshTokensAndDuplicateCookiesBeforeCallingService() throws Exception {
        for (String body : List.of("{\"refreshToken\":\"test-rt\"}", "{\"expectedSessionId\":123}")) {
            mvc.perform(post("/api/auth/refresh").header(WebSessionPolicy.HEADER,"cookie").header("Origin","https://console.example.test")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/auth/refresh").header(WebSessionPolicy.HEADER,"cookie").header("Origin","https://console.example.test")
                .cookie(new Cookie("__Host-nexus_rt","one"),new Cookie("__Host-nexus_rt","two"))
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(sessions);
    }

    @Test void absentLogoutCookieIsNotNeededAndClearMatchesOriginalCookieScope() throws Exception {
        var response = mvc.perform(post("/api/auth/logout").header(WebSessionPolicy.HEADER,"cookie").header("Origin","https://console.example.test")
                .contentType("application/json").content("{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serverRevocation").value("not-needed")).andReturn().getResponse();
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
        assertTrue(response.getHeader("Set-Cookie").contains("Path=/")); verifyNoInteractions(logout);
    }

    @Test void insecureCookieOnlyAcceptsExplicitLocalLoopbackConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new WebSessionPolicy(new WebSessionProperties(false,"nexus_rt_dev",List.of("http://127.0.0.1:5173")), new MockEnvironment()));
        assertThrows(IllegalArgumentException.class, () -> new WebSessionPolicy(new WebSessionProperties(false,"nexus_rt_dev",List.of("http://console.example.test")), new MockEnvironment().withProperty("spring.profiles.active","local")));
        MockEnvironment local = new MockEnvironment(); local.setActiveProfiles("local");
        var dev = new WebSessionPolicy(new WebSessionProperties(false,"nexus_rt_dev",List.of("http://127.0.0.1:5173")), local);
        MockHttpServletResponse response = new MockHttpServletResponse(); dev.set(response,"test-rt");
        assertFalse(response.getHeader("Set-Cookie").contains("Secure"));
        assertTrue(response.getHeader("Set-Cookie").contains("HttpOnly"));
        assertThrows(IllegalArgumentException.class, () -> new WebSessionPolicy(new WebSessionProperties(true,"__Host-nexus_rt",List.of("https://console.example.test/")), local));
        assertThrows(IllegalArgumentException.class, () -> new WebSessionPolicy(new WebSessionProperties(true,"__Host-nexus_rt",List.of("https://console.example.test", "https://console.example.test:8443")), local));
    }
}
