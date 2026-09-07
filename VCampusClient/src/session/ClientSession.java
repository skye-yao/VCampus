package session;

import entity.User;

/**
 * 客户端当前登录会话。
 *
 * 保存当前登录用户的基本身份信息及完整 User 对象。
 */
public class ClientSession {

    private static final ClientSession INSTANCE = new ClientSession();

    private String username;
    private String role;
    private String token;
    private User currentUser;

    private ClientSession() {
    }

    public static ClientSession getInstance() {
        return INSTANCE;
    }

    /**
     * 保存登录信息。
     */
    public synchronized void login(String username, String role, String token, User user) {
        this.username = username;
        this.role = role;
        this.token = token;
        this.currentUser = user;
    }

    /**
     * 清除当前登录会话。
     */
    public synchronized void logout() {
        this.username = null;
        this.role = null;
        this.token = null;
        this.currentUser = null;
    }

    public synchronized String getUsername() {
        return username;
    }

    public synchronized String getRole() {
        return role;
    }

    public synchronized String getToken() {
        return token;
    }

    public synchronized User getCurrentUser() {
        return currentUser;
    }

    public synchronized void setCurrentUser(User currentUser) {
        this.currentUser = currentUser;
    }

    public synchronized boolean isLoggedIn() {
        return token != null && !token.isEmpty();
    }
}
