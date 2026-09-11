package session;

import network.ClientHandler;
import protocol.Message;
import protocol.MessageType;
import protocol.MessageCode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端在线用户会话管理器
 *
 * 负责创建、查询、删除和验证用户会话，并维护 UID 与连接的绑定，实现账号防重登踢出。
 */
public class SessionManager {

    /** 单例实例 */
    private static final SessionManager INSTANCE = new SessionManager();

    /** token -> UserSession */
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    /** username (UID) -> token，用于防重登检测 */
    private final Map<String, String> userTokens = new ConcurrentHashMap<>();

    /** token -> ClientHandler，用于主动向指定客户端连接推送消息（如强制下线） */
    private final Map<String, ClientHandler> clientHandlers = new ConcurrentHashMap<>();

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
     * 若该 UID 已经在线，将触发防重登逻辑：
     * 向原客户端推送下线通知并断开原连接，随后允许新连接登入。
     *
     * @param username 用户名/一卡通号 (UID)
     * @param role 用户角色
     * @return 新创建的用户会话
     */
    public UserSession createSession(String username, String role) {
        ClientHandler currentHandler = ClientHandler.CURRENT_HANDLER.get();

        // 1. 若当前连接之前曾绑定过其他旧会话，先清理原映射
        if (currentHandler != null) {
            unbindHandler(currentHandler);
        }

        // 2. 检查该 UID 是否已经登录
        if (username != null) {
            String oldToken = userTokens.get(username);
            if (oldToken != null) {
                UserSession oldSession = sessions.remove(oldToken);
                ClientHandler oldHandler = clientHandlers.remove(oldToken);
                userTokens.remove(username);

                // 若存在旧连接且并非当前连接，发送下线通知并断开原连接
                if (oldHandler != null && oldHandler != currentHandler) {
                    try {
                        System.out.println("检测到账号 " + username + " 在新连接登录，向原客户端发送下线提示并断开原连接...");
                        Message kickout = new Message(MessageType.PUSH, "user", "kickout");
                        kickout.setCode(MessageCode.UNAUTHORIZED);
                        kickout.setMessage("您的账号已在别处登录，请确认是您本人操作，注意账号安全，若非本人操作请及时修改密码");
                        oldHandler.sendMessage(kickout);

                        // 异步延迟断开原连接，确保推送消息已刷出到网络
                        Thread closer = new Thread(() -> {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException ignored) {}
                            try {
                                oldHandler.close();
                            } catch (Exception ignored) {}
                        }, "kickout-closer-" + username);
                        closer.setDaemon(true);
                        closer.start();
                    } catch (Exception e) {
                        System.err.println("向原客户端发送防重登下线通知失败: " + e.getMessage());
                    }
                }
            }
        }

        // 3. 生成新唯一 token 并注册新会话
        String token = UUID.randomUUID().toString();

        UserSession session = new UserSession(
                username,
                token,
                role
        );

        sessions.put(token, session);
        if (username != null) {
            userTokens.put(username, token);
        }
        if (currentHandler != null) {
            clientHandlers.put(token, currentHandler);
        }

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
     * 删除用户会话（正常登出时调用）。
     *
     * @param token 登录令牌
     */
    public void removeSession(String token) {
        if (token != null) {
            UserSession session = sessions.remove(token);
            clientHandlers.remove(token);
            if (session != null && session.getUsername() != null) {
                userTokens.remove(session.getUsername(), token);
            }
        }
    }

    /**
     * 当连接断开时，解绑该 Handler 关联的所有会话。
     *
     * @param handler 客户端处理器实例
     */
    public void unbindHandler(ClientHandler handler) {
        if (handler == null) return;
        for (Map.Entry<String, ClientHandler> entry : clientHandlers.entrySet()) {
            if (entry.getValue() == handler) {
                String token = entry.getKey();
                if (clientHandlers.remove(token, handler)) {
                    UserSession session = sessions.remove(token);
                    if (session != null && session.getUsername() != null) {
                        userTokens.remove(session.getUsername(), token);
                    }
                }
            }
        }
    }

    /**
     * 查询指定 UID 用户当前是否在线。
     */
    public boolean isUserLoggedIn(String username) {
        if (username == null) return false;
        String token = userTokens.get(username);
        return token != null && sessions.containsKey(token);
    }

    /**
     * 获取当前在线用户数量。
     */
    public int getOnlineCount() {
        return sessions.size();
    }
}