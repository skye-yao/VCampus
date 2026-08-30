package com.vcampus.client.session;

/**
 * 客户端当前登录会话。
 *
 * 保存当前登录用户的基本身份信息，
 * 后续客户端发送请求时使用 token 进行身份认证。
 */
public class ClientSession {

    /** 单例实例 */
    private static final ClientSession INSTANCE = new ClientSession();

    /** 当前登录用户名/一卡通号 */
    private String username;

    /** 当前登录用户的角色 */
    private String role;

    /** 服务端返回的登录令牌 */
    private String token;

    /** 私有构造方法 */
    private ClientSession() {
    }

    /**
     * 获取客户端当前会话。
     */
    public static ClientSession getInstance() {
        return INSTANCE;
    }

    /**
     * 保存登录信息。
     */
    public void login(String username, String role, String token) {
        this.username = username;
        this.role = role;
        this.token = token;
    }

    /**
     * 清除当前登录会话。
     */
    public void logout() {
        this.username = null;
        this.role = null;
        this.token = null;
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    public String getToken() {
        return token;
    }

    /**
     * 判断当前是否已经登录。
     */
    public boolean isLoggedIn() {
        return token != null && !token.isEmpty();
    }
}