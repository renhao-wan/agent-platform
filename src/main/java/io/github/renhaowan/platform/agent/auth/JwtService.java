package io.github.renhaowan.platform.agent.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与解析（HS256，手写实现，不引入 Spring Security——与项目现状一致）。
 * <ul>
 *   <li>claims 携带 uid / role——解析结果直接投影为 {@link UserPrincipal}；</li>
 *   <li>过期 2 小时；过期/篡改/缺 claim 统一抛 JwtException，由过滤器翻译成 401；</li>
 *   <li>密钥来自 {@code JWT_SECRET} 环境变量（默认 dev 值仅限本地）。HS256 要求
 *       密钥 ≥ 256 位，配置过短时用 SHA-256 确定性派生到 256 位（无需存盐）。</li>
 * </ul>
 */
@Service
public class JwtService {

    public static final String CLAIM_UID = "uid";
    public static final String CLAIM_ROLE = "role";

    private static final Duration TTL = Duration.ofHours(2);
    private static final String DEFAULT_SECRET = "dev-jwt-secret-please-change";

    private final SecretKey key;

    public JwtService(@Value("${JWT_SECRET:" + DEFAULT_SECRET + "}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(bytes.length >= 32 ? bytes : sha256(bytes));
    }

    /** 签发登录令牌：uid/role 写入 claims，2 小时过期。 */
    public String issue(UserPrincipal principal) {
        Date now = new Date();
        return Jwts.builder()
                .claim(CLAIM_UID, principal.uid())
                .claim(CLAIM_ROLE, principal.role())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + TTL.toMillis()))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并验签；非法签名/过期/缺 uid 或 role claim 都抛 JwtException（调用方翻译为 401）。
     */
    public UserPrincipal parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        Number uid = claims.get(CLAIM_UID, Number.class);
        String role = claims.get(CLAIM_ROLE, String.class);
        if (uid == null || role == null || role.isBlank()) {
            throw new JwtException("missing uid/role claims");
        }
        return new UserPrincipal(uid.longValue(), role);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
