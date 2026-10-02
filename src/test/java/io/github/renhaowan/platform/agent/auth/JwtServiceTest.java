package io.github.renhaowan.platform.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.renhaowan.platform.agent.auth.impl.AuthServiceImpl;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService("unit-test-secret-0123456789abcdef0123456789abcdef");

    @Test
    void issueThenParseRoundTripsUidAndRole() {
        String token = jwtService.issue(new UserPrincipal(42L, "ADMIN"));

        UserPrincipal principal = jwtService.parse(token);

        assertThat(principal.uid()).isEqualTo(42L);
        assertThat(principal.role()).isEqualTo("ADMIN");
    }

    @Test
    void parseRejectsTokenSignedWithForeignSecret() {
        String token = new JwtService("another-secret-0123456789abcdef0123456789abcdef")
                .issue(new UserPrincipal(1L, "STAFF"));

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void parseRejectsGarbageInput() {
        assertThatThrownBy(() -> jwtService.parse("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretIsDerivedNotRejected() {
        // 默认 dev secret 仅 28 字节（224 位）——HS256 要求 256 位，应 SHA-256 派生而非抛 WeakKey 异常
        JwtService shortKeyService = new JwtService("dev-jwt-secret-please-change");
        String token = shortKeyService.issue(new UserPrincipal(7L, "STAFF"));

        assertThat(shortKeyService.parse(token).uid()).isEqualTo(7L);
    }

    @Test
    void sha256HexMatchesSeedDigest() {
        // 与 data.sql 种子摘要一致（admin123），保证登录校验对得上种子数据
        assertThat(AuthServiceImpl.sha256Hex("admin123"))
                .isEqualTo("240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9");
    }
}
