package io.github.renhaowan.platform.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.agent.auth.entity.AppUser;
import io.github.renhaowan.platform.agent.auth.mapper.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 登录端到端（HTTP 层）：成功签发 {token, role}；密码错误/用户不存在 → 401。
 */
@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
class AuthControllerTest {

    /** admin123 / staff123 的 SHA-256 摘要（与 data.sql 种子一致）。 */
    private static final String ADMIN_HASH =
            "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9";
    private static final String STAFF_HASH =
            "10176e7b7b24d317acfcf8d2064cfd2f24e154f7b5a96603077d5ef813d6a6b6";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private AppUserMapper appUserMapper;

    @Test
    void loginSuccessReturnsTokenAndRole() throws Exception {
        when(appUserMapper.selectOne(any())).thenReturn(user("admin", ADMIN_HASH, "ADMIN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void loginSuccessTokenCarriesUidAndRoleClaims() throws Exception {
        when(appUserMapper.selectOne(any())).thenReturn(user("staff01", STAFF_HASH, "STAFF"));

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staff01\",\"password\":\"staff123\"}"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = MAPPER.readTree(body);
        assertThat(json.path("role").asText()).isEqualTo("STAFF");
        UserPrincipal principal = jwtService.parse(json.path("token").asText());
        assertThat(principal.role()).isEqualTo("STAFF");
    }

    @Test
    void loginWrongPasswordRejectedWith401() throws Exception {
        when(appUserMapper.selectOne(any())).thenReturn(user("admin", ADMIN_HASH, "ADMIN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void loginUnknownUserRejectedWith401() throws Exception {
        when(appUserMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    private AppUser user(String username, String hash, String role) {
        AppUser user = new AppUser();
        user.setId(2L);
        user.setUsername(username);
        user.setPasswordHash(hash);
        user.setRole(role);
        user.setStatus(AppUser.STATUS_ENABLED);
        return user;
    }
}
