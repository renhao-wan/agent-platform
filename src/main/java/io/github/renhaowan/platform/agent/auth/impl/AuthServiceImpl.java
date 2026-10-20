package io.github.renhaowan.platform.agent.auth.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.agent.auth.AuthService;
import io.github.renhaowan.platform.agent.auth.InvalidCredentialsException;
import io.github.renhaowan.platform.agent.auth.JwtService;
import io.github.renhaowan.platform.agent.auth.RbacService;
import io.github.renhaowan.platform.agent.auth.TooManyLoginAttemptsException;
import io.github.renhaowan.platform.agent.auth.entity.AppUser;
import io.github.renhaowan.platform.agent.auth.mapper.AppUserMapper;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** AuthService 接口实现：PBKDF2 校验（兼容旧 SHA-256 并原地升级）+ JWT 签发（token 只写 uid）。 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AuthServiceImpl implements AuthService {

    private final AppUserMapper userMapper;
    private final JwtService jwtService;
    private final RbacService rbacService;
    private final LoginThrottle loginThrottle;

    @Override
    public LoginResult login(String username, String password) {
        if (loginThrottle.isBlocked(username)) {
            throw new TooManyLoginAttemptsException("登录尝试过于频繁，请 5 分钟后再试");
        }
        AppUser user = userMapper.selectOne(new QueryWrapper<AppUser>().eq("username", username));
        // 用户不存在与密码错误同文案——不向攻击者泄露"该用户名已注册"这一信息
        if (user == null) {
            loginThrottle.onFailure(username);
            throw new InvalidCredentialsException("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != AppUser.STATUS_ENABLED) {
            throw new InvalidCredentialsException("账号已停用");
        }
        if (!PasswordHasher.matches(password, user.getPasswordHash())) {
            loginThrottle.onFailure(username);
            throw new InvalidCredentialsException("用户名或密码错误");
        }
        loginThrottle.onSuccess(username);
        upgradeLegacyHash(user, password);
        Set<String> permissions = rbacService.permissionsOfRole(user.getRole());
        return new LoginResult(jwtService.issue(user.getId()), user.getRole(), permissions);
    }

    /**
     * 旧库里的裸 SHA-256 摘要一旦验证通过就换成 PBKDF2——用户无感知，也不必强制改密。
     * 升级失败不回滚登录（凭证已经验证过），下次登录再试。
     */
    private void upgradeLegacyHash(AppUser user, String password) {
        if (!PasswordHasher.isLegacy(user.getPasswordHash())) {
            return;
        }
        try {
            user.setPasswordHash(PasswordHasher.hash(password));
            userMapper.updateById(user);
        } catch (Exception e) {
            log.warn("密码摘要升级失败，用户 {}：{}", user.getUsername(), e.getMessage());
        }
    }
}
