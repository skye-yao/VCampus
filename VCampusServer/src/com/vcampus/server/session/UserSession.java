package com.vcampus.server.session;

import java.time.LocalDateTime;

/**
 * 服务端用户会话
 *
 * 表示一个已经登录的用户在服务端的会话信息。
 */
public class UserSession {

    /** 用户名/一卡通号 */
    private final String username;

    /** 登录令牌 */
    private final String token;

    /** 登录时间 */
    private final LocalDateTime loginTime;

    /** 用户权限/角色 */
    private final String role;

    public UserSession(String username, String token, String role) {
        this.username = username;
        this.token = token;
        this.role = role;
        this.loginTime = LocalDateTime.now();
    }

    public String getUsername() {
        return username;
    }

    public String getToken() {
        return token;
    }

    public LocalDateTime getLoginTime() {
        return loginTime;
    }

    public String getRole() {
        return role;
    }

    @Override
    public String toString() {
        return "UserSession{" +
                "username='" + username + '\'' +
                ", token='" + token + '\'' +
                ", loginTime=" + loginTime +
                ", role='" + role + '\'' +
                '}';
    }
}