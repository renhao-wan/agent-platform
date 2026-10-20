package io.github.renhaowan.platform.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.agent.auth.entity.AppUser;
import io.github.renhaowan.platform.agent.auth.impl.PasswordHasher;
import io.github.renhaowan.platform.agent.auth.mapper.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

/**
 * 登录端到端（HTTP 层）：成功签发 {token, role, permissions}；密码错误/用户不存在 → 401。
 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=never",
        "platform.rbac.seed-on-startup=false"
})
@AutoConfigureMockMvc
class AuthControllerTest {

    /** admin123 / staff123 的 PBKDF2 摘要（与 data.sql 种子一致）。 */
    private static final String ADMIN_HASH =
            "pbkdf2$210000$k4qXz92OxljgfDqd9oDvhg==$jM4Skfi2GLyTL7++zGFTy3/XsHePsouva0OYMMC2gWg=";
    private static final String STAFF_HASH =
            "pbkdf2$210000$Jg+arMOCHtnspKocYYQx4A==$BopiPxTxn5CARrI5UcOKMAsNtXMH6SgCdl9mrfpGF6U=";
    /** 升级前数据库里的旧格式：admin123 的裸 SHA-256 摘要。 */
    private static final String LEGACY_ADMIN_HASH =
            "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AppUserMapper appUserMapper;

    @MockitoBean
    private RbacService rbacService;

    @Test
    void loginSuccessReturnsTokenRoleAndPermissions() throws Exception {
        when(appUserMapper.selectOne(any())).thenReturn(user("admin", ADMIN_HASH, "ADMIN"));
        when(rbacService.permissionsOfRole("ADMIN")).thenReturn(Set.of("*"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.permissions[0]").value("*"));
    }

    @Test
    void loginTokenCarriesOnlyUid() throws Exception {
        when(appUserMapper.selectOne(any())).thenReturn(user("staff01", STAFF_HASH, "STAFF"));

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staff01\",\"password\":\"staff123\"}"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = MAPPER.readTree(body);
        assertThat(json.path("role").asText()).isEqualTo("STAFF");
        assertThat(jwtService.parseUid(json.path("token").asText())).isEqualTo(2L);
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

    @Test
    void legacySha256DigestIsUpgradedOnSuccessfulLogin() throws Exception {
        when(appUserMapper.selectOne(any())).thenReturn(user("admin", LEGACY_ADMIN_HASH, "ADMIN"));
        when(rbacService.permissionsOfRole("ADMIN")).thenReturn(Set.of("*"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserMapper).updateById(captor.capture());
        assertThat(PasswordHasher.isLegacy(captor.getValue().getPasswordHash())).isFalse();
        assertThat(PasswordHasher.matches("admin123", captor.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void repeatedFailuresAreThrottled() throws Exception {
        when(appUserMapper.selectOne(any())).thenReturn(user("throttle-user", ADMIN_HASH, "STAFF"));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"throttle-user\",\"password\":\"wrong-pass\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"throttle-user\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isTooManyRequests());
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
