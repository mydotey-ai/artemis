package org.mydotey.ai.artemis.management.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 密码哈希（JDK 内置，零新依赖）。
 *
 * <p>存储格式 {@code pbkdf2-sha256$<iterations>$<base64(salt)>$<base64(derivedKey)>}：
 * 算法与参数内嵌自描述，格式演进不改表结构。参数取 OWSAP 现行推荐档：
 * iterations 600,000、salt 16B、derived key 256bit。比较走 {@link MessageDigest#isEqual}
 * 常量时间。
 */
public final class PasswordHasher {

    private static final String ALGORITHM_LABEL = "pbkdf2-sha256";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        Base64.Encoder encoder = Base64.getEncoder();
        return ALGORITHM_LABEL + "$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$"
                + encoder.encodeToString(derive(rawPassword, salt, ITERATIONS));
    }

    /** 坏格式哈希串返回 false（不抛异常——存储损坏不应使认证路径崩溃）。 */
    public static boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) {
            return false;
        }
        String[] parts = storedHash.split("\\$");
        if (parts.length != 4 || !ALGORITHM_LABEL.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (iterations <= 0 || expected.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(derive(rawPassword, salt, iterations), expected);
    }

    private static byte[] derive(String rawPassword, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
