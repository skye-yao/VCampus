package com.vcampus.server.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端在线用户会话管理器
 *
 * 负责创建、查询、删除和验证用户会话。
 */
public class SessionManager {

    /** 单例实例 */
    private static final SessionManager INSTANCE = new SessionManager();

    /** token -> UserSession */
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    /**
     * 私有构造方法，防止外部创建多个 SessionManager。
     */
    private SessionManager() {
    }

    /**
     * 获取 SessionManager 单例。
     */
    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * 创建用户会话。
     *
     * @param username 用户名/一卡通号
     * @param role 用户角色
     * @return 新创建的用户会话
     */
    public UserSession createSession(String username, String role) {

        // 生成唯一 token
        String token = UUID.randomUUID().toString();

        UserSession session = new UserSession(
                username,
                token,
                role
        );

        sessions.put(token, session);

        return session;
    }

    /**
     * 根据 token 获取用户会话。
     *
     * @param token 登录令牌
     * @return 对应的会话，不存在则返回 null
     */
    public UserSession getSession(String token) {

        if (token == null || token.isEmpty()) {
            return null;
        }

        return sessions.get(token);
    }

    /**
     * 判断 token 是否对应有效会话。
     *
     * @param token 登录令牌
     * @return true 表示用户在线且 token 有效
     */
    public boolean isValid(String token) {

        return getSession(token) != null;
    }

    /**
     * 删除用户会话。
     *
     * @param token 登录令牌
     */
    public void removeSession(String token) {

        if (token != null) {
            sessions.remove(token);
        }
    }

    /**
     * 获取当前在线用户数量。
     */
    public int getOnlineCount() {

        return sessions.size();
    }
}