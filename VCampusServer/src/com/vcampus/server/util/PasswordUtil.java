package com.vcampus.server.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码工具类
 *
 * <p>负责密码的加密和验证，使用 SHA-256 + 随机盐值。
 *
 * <p><b>使用示例：</b>
 * <pre>
 * // 1. 用户注册时：生成盐值并加密密码
 * String salt = PasswordUtil.generateSalt();
 * String hashedPwd = PasswordUtil.hashPassword("123456", salt);
 * // 将 salt 和 hashedPwd 存入数据库
 *
 * // 2. 用户登录时：验证密码
 * boolean valid = PasswordUtil.verifyPassword("123456", saltFromDB, hashFromDB);
 * </pre>
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
public class PasswordUtil {

    /** 盐值长度（字节） */
    private static final int SALT_LENGTH = 16;

    /** 哈希算法 */
    private static final String ALGORITHM = "SHA-256";

    /**
     * 生成随机盐值
     *
     * @return Base64 编码的盐值字符串
     */
    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * 对密码进行哈希加密
     *
     * @param password 明文密码
     * @param salt     盐值（Base64 编码）
     * @return Base64 编码的哈希值
     */
    public static String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance(ALGORITHM);
            md.update(salt.getBytes());
            byte[] hashed = md.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("加密算法不存在", e);
        }
    }

    /**
     * 验证密码是否正确
     *
     * @param password      明文密码
     * @param salt          盐值（数据库中存储的）
     * @param hashedPassword 哈希密码（数据库中存储的）
     * @return true 表示密码正确，false 表示密码错误
     */
    public static boolean verifyPassword(String password, String salt, String hashedPassword) {
        String computedHash = hashPassword(password, salt);
        return computedHash.equals(hashedPassword);
    }
}