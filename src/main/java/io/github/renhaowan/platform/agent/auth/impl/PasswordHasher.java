package io.github.renhaowan.platform.agent.auth.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 密码摘要：新写入一律 PBKDF2-HMAC-SHA256（随机盐 + 21 万次迭代）。
 * <p>
 * 历史数据里是裸 SHA-256 十六进制摘要。无盐 SHA-256 可被彩虹表直接反查，
 * 因此只在登录时用于兼容校验，校验通过后由 {@code AuthServiceImpl} 原地升级。
 */
public final class PasswordHasher {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 210_000;
    /** 存储串里的迭代次数来自库，设上限防止脏数据把登录拖成 DoS。 */
    private static final int MAX_ITERATIONS = 1_000_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String raw) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] digest = pbkdf2(raw, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(digest);
    }

    public static boolean matches(String raw, String stored) {
        if (raw == null || stored == null) {
            return false;
        }
        if (isLegacy(stored)) {
            return MessageDigest.isEqual(
                    sha256Hex(raw).getBytes(StandardCharsets.US_ASCII),
                    stored.getBytes(StandardCharsets.US_ASCII));
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations <= 0 || iterations > MAX_ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, pbkdf2(raw, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 裸 SHA-256 十六进制摘要（旧格式），登录成功后应升级。 */
    public static boolean isLegacy(String stored) {
        if (stored == null || stored.length() != 64) {
            return false;
        }
        for (int i = 0; i < stored.length(); i++) {
            if (Character.digit(stored.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] pbkdf2(String raw, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String sha256Hex(String raw) {
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
