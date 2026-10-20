package io.github.renhaowan.platform.agent.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与解析（HS256，手写实现，不引入 Spring Security——与项目现状一致）。
 * <ul>
 *   <li>claims 只携带 uid：token 只证明"是谁"，角色与权限是服务端可变状态，由
 *       {@link RbacService} 在每次请求时加载——JWT 里不写 role；</li>
 *   <li>过期 2 小时；过期/篡改/缺 uid 统一抛 JwtException，由过滤器翻译成 401；</li>
 *   <li>密钥必须由 {@code JWT_SECRET} 提供且不少于 32 字节（HS256 的 256 位下限）。
 *       缺失或过短直接拒绝启动——仓库内不存在默认密钥，否则任何拿到源码的人都能
 *       伪造 uid（含管理员）的 token。</li>
 * </ul>
 * 旧 token 里可能还带 role claim，解析时直接忽略——不需要强制重新登录。
 */
@Service
public class JwtService {

    public static final String CLAIM_UID = "uid";
    private static final int MIN_SECRET_BYTES = 32;

    private static final Duration TTL = Duration.ofHours(2);

    private final SecretKey key;

    public JwtService(@Value("${JWT_SECRET:}") String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET 未配置或不足 " + MIN_SECRET_BYTES + " 字节；请在 .env 中生成随机密钥后再启动");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** 签发登录令牌：只写 uid，2 小时过期。 */
    public String issue(long uid) {
        Date now = new Date();
        return Jwts.builder()
                .claim(CLAIM_UID, uid)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + TTL.toMillis()))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并验签，返回用户 id；非法签名/过期/缺 uid claim 都抛 JwtException（调用方翻译为 401）。
     */
    public long parseUid(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        Number uid = claims.get(CLAIM_UID, Number.class);
        if (uid == null) {
            throw new JwtException("missing uid claim");
        }
        return uid.longValue();
    }
}
