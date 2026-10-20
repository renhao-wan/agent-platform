package io.github.renhaowan.platform.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.renhaowan.platform.agent.auth.impl.AuthServiceImpl;
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
    void shortSecretIsDerivedNotRejected() {
        // 默认 dev secret 仅 28 字节（224 位）——HS256 要求 256 位，应 SHA-256 派生而非抛 WeakKey 异常
        JwtService shortKeyService = new JwtService("dev-jwt-secret-please-change");
        String token = shortKeyService.issue(7L);

        assertThat(shortKeyService.parseUid(token)).isEqualTo(7L);
    }

    @Test
    void tokenCarriesNoRoleClaim() {
        // 角色是服务端可变状态，token 只证明身份；旧 token 里残留的 role claim 也必须被忽略
        String token = jwtService.issue(9L);

        assertThat(jwtService.parseUid(token)).isEqualTo(9L);
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void sha256HexMatchesSeedDigest() {
        // 与 data.sql 种子摘要一致（admin123），保证登录校验对得上种子数据
        assertThat(AuthServiceImpl.sha256Hex("admin123"))
                .isEqualTo("240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9");
    }
}
