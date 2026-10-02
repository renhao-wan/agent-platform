package io.github.renhaowan.platform.agent.auth.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.agent.auth.AuthService;
import io.github.renhaowan.platform.agent.auth.InvalidCredentialsException;
import io.github.renhaowan.platform.agent.auth.JwtService;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.auth.entity.AppUser;
import io.github.renhaowan.platform.agent.auth.mapper.AppUserMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** AuthService 接口实现：SHA-256 摘要比对 + JWT 签发。 */
@RequiredArgsConstructor
@Service
public class AuthServiceImpl implements AuthService {

    private final AppUserMapper userMapper;
    private final JwtService jwtService;

    @Override
    public LoginResult login(String username, String password) {
        AppUser user = userMapper.selectOne(new QueryWrapper<AppUser>().eq("username", username));
        // 用户不存在与密码错误同文案——不向攻击者泄露"该用户名已注册"这一信息
        if (user == null) {
            throw new InvalidCredentialsException("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != AppUser.STATUS_ENABLED) {
            throw new InvalidCredentialsException("账号已停用");
        }
        if (!sha256Hex(password).equals(user.getPasswordHash())) {
            throw new InvalidCredentialsException("用户名或密码错误");
        }
        return new LoginResult(jwtService.issue(new UserPrincipal(user.getId(), user.getRole())), user.getRole());
    }

    /** SHA-256 十六进制小写摘要（与 data.sql 种子、注册写入约定一致）。 */
    public static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
