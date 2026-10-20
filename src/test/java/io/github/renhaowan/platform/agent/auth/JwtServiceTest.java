package io.github.renhaowan.platform.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService("unit-test-secret-0123456789abcdef0123456789abcdef");

    @Test
    void issueThenParseRoundTripsUid() {
        String token = jwtService.issue(42L);

        assertThat(jwtService.parseUid(token)).isEqualTo(42L);
    }

    @Test
    void parseRejectsTokenSignedWithForeignSecret() {
        String token = new JwtService("another-secret-0123456789abcdef0123456789abcdef")
                .issue(1L);

        assertThatThrownBy(() -> jwtService.parseUid(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void parseRejectsGarbageInput() {
        assertThatThrownBy(() -> jwtService.parseUid("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void missingSecretIsRejectedAtStartup() {
        assertThatThrownBy(() -> new JwtService(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void shortSecretIsRejectedInsteadOfDerived() {
        // 短密钥不再派生：HS256 的 256 位下限是硬要求，弱密钥直接拒绝启动
        assertThatThrownBy(() -> new JwtService("dev-jwt-secret-please-change"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tokenCarriesNoRoleClaim() {
        // 角色是服务端可变状态，token 只证明身份；旧 token 里残留的 role claim 也必须被忽略
        String token = jwtService.issue(9L);

        assertThat(jwtService.parseUid(token)).isEqualTo(9L);
        assertThat(token.split("\\.")).hasSize(3);
    }
}
