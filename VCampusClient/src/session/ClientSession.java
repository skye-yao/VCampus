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
    public void login(String username, String role, String token, User user) {
        this.username = username;
        this.role = role;
        this.token = token;
        this.currentUser = user;
    }

    /**
     * 清除当前登录会话。
     */
    public void logout() {
        this.username = null;
        this.role = null;
        this.token = null;
        this.currentUser = null;
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

    public User getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(User currentUser) {
        this.currentUser = currentUser;
    }

    public boolean isLoggedIn() {
        return token != null && !token.isEmpty();
    }
}
