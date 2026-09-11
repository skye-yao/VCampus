package network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已认证 UID 到在线连接的注册表。
 *
 * <p>同一账号可绑定多个连接；注册表只保存 {@link ClientConnection}，不保存裸 Writer。
 * 所有状态变更（绑定、解绑、关闭）都在同一把私有锁内完成，因此 close 与 bind 之间不存在
 * “检查后插入”的竞态：close 之后到达的 bind 一律拒绝并关闭该连接。任何
 * {@link ClientConnection} 的网络 I/O（发送、关闭）都在锁外执行。
 */
public final class OnlineConnectionRegistry implements AutoCloseable {

    private final Object lock = new Object();
    private final Map<String, Set<ClientConnection>> connections = new HashMap<>();

    private boolean closed;

    public void bind(String uid, ClientConnection connection) {
        if (uid == null || connection == null) {
            return;
        }
        boolean rejected;
        synchronized (lock) {
            rejected = closed;
            if (!rejected) {
                connections.computeIfAbsent(uid, key -> new LinkedHashSet<>()).add(connection);
            }
        }
        if (rejected) {
            connection.close();
        }
    }

    public void unbind(String uid, ClientConnection connection) {
        if (uid == null || connection == null) {
            return;
        }
        synchronized (lock) {
            Set<ClientConnection> bound = connections.get(uid);
            if (bound == null) {
                return;
            }
            bound.remove(connection);
            if (bound.isEmpty()) {
                connections.remove(uid);
            }
        }
    }

    public List<ClientConnection> snapshot(String uid) {
        if (uid == null) {
            return List.of();
        }
        synchronized (lock) {
            Set<ClientConnection> bound = connections.get(uid);
            if (bound == null || bound.isEmpty()) {
                return List.of();
            }
            return List.copyOf(bound);
        }
    }

    @Override
    public void close() {
        List<ClientConnection> bound;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            bound = new ArrayList<>();
            for (Set<ClientConnection> set : connections.values()) {
                bound.addAll(set);
            }
            connections.clear();
        }
        for (ClientConnection connection : bound) {
            connection.close();
        }
    }
}
