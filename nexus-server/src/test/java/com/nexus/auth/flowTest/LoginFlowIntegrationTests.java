package com.nexus.auth.flowTest;

import com.nexus.auth.dto.LoginRequest;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class LoginFlowIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SecretKey secretKey;
    // 直接从secretKey构建解码器，SpringBootTest可以直接注入密钥
    private NimbusJwtDecoder jwtDecoder;
    private static final String TEST_JWT_SECRET =
            Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef"
                            .getBytes(StandardCharsets.UTF_8)
            );

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "nexus.auth.jwt.secret",
                () -> TEST_JWT_SECRET
        );
    }

    // 初始化解码器
    @org.junit.jupiter.api.BeforeEach
    void setupDecoder() {
        jwtDecoder = NimbusJwtDecoder
                .withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Test
    public void loginFlowTests() throws Exception{
        String email = "test-login-eamil" + UUID.randomUUID() + "@example.com";
        String password = "test-login-password";
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName("test-login-username");
        userMapper.insert(user);

        MvcResult result =mockMvc.perform(
                post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password)))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("登录成功"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(1800L))
                .andReturn();

        // 解析返回的JSON，取出 accessToken
        String responseBody = result.getResponse().getContentAsString();
        ObjectNode jsonNode = (ObjectNode) objectMapper.readTree(responseBody);
        String accessToken = jsonNode.at("/data/accessToken").asText();

        // ✅ 解码 JWT
        Jwt jwt = jwtDecoder.decode(accessToken);

        // sub 断言：JWT的sub = user.getId() 的字符串形式
        String subject = jwt.getSubject();
        assertEquals(user.getId().toString(), subject);
    }

}
