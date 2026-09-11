package service;

import com.google.gson.Gson;
import dao.CourseEventOutboxDAO;
import dao.CourseEventOutboxDAO.OutboxEvent;
import dto.course.CourseActions;
import dto.course.CoursePushEventDTO;
import dto.course.CoursePushEventTypeDTO;
import dto.course.CourseTermDTO;
import network.ClientConnection;
import network.OnlineConnectionRegistry;
import protocol.Message;
import protocol.MessageType;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 课程事件 outbox 的至少一次投递器。
 *
 * <p>每个周期用一个可关闭的数据库连接有界拉取未 ACK 的事件，按 event_id 顺序向事件
 * 所属账号的全部在线连接发送 PUSH。只有确实向至少一个连接尝试过发送的事件才记录
 * attempt；只有事件所属账号通过 {@code ackCourseEvent} 确认才写入 acked_at。
 */
public final class CourseEventDispatcher implements AutoCloseable {

    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
    private static final int DEFAULT_BATCH_LIMIT = 100;
    private static final Gson GSON = new Gson();

    private final OnlineConnectionRegistry registry;
    private final CourseEventOutboxDAO dao;
    private final Supplier<Connection> connectionFactory;
    private final Clock clock;
    private final Duration interval;
    private final int batchLimit;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CourseEventDispatcher(OnlineConnectionRegistry registry, CourseEventOutboxDAO dao,
                                 Supplier<Connection> connectionFactory) {
        this(registry, dao, connectionFactory, Clock.systemUTC(), DEFAULT_INTERVAL,
                DEFAULT_BATCH_LIMIT, daemonExecutor());
    }

    CourseEventDispatcher(OnlineConnectionRegistry registry, CourseEventOutboxDAO dao,
                          Supplier<Connection> connectionFactory, Clock clock, Duration interval,
                          int batchLimit, ScheduledExecutorService executor) {
        if (registry == null || dao == null || connectionFactory == null || clock == null
                || interval == null || executor == null) {
            throw new IllegalArgumentException("dispatcher dependencies must not be null");
        }
        if (interval.isZero() || interval.isNegative() || batchLimit <= 0) {
            throw new IllegalArgumentException("interval and batch limit must be positive");
        }
        this.registry = registry;
        this.dao = dao;
        this.connectionFactory = connectionFactory;
        this.clock = clock;
        this.interval = interval;
        this.batchLimit = batchLimit;
        this.executor = executor;
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        executor.scheduleAtFixedRate(() -> runOnce(clock.instant()), 0,
                interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void runOnce(Instant now) {
        Connection connection = null;
        try {
            connection = connectionFactory.get();
            List<String> onlineUids = registry.onlineUids();
            for (OutboxEvent event : dao.pending(connection, onlineUids, batchLimit)) {
                deliver(connection, event, now);
            }
        } catch (SQLException | RuntimeException failure) {
            System.err.println("课程事件扫描失败: " + failure.getMessage());
        } finally {
            closeQuietly(connection);
        }
    }

    public boolean acknowledge(String uid, long eventId) {
        if (uid == null || eventId <= 0) {
            return false;
        }
        Connection connection = null;
        try {
            connection = connectionFactory.get();
            return dao.acknowledge(connection, uid, eventId, clock.instant());
        } catch (SQLException | RuntimeException failure) {
            System.err.println("课程事件确认失败: " + failure.getMessage());
            return false;
        } finally {
            closeQuietly(connection);
        }
    }

    private void deliver(Connection connection, OutboxEvent event, Instant now) {
        List<ClientConnection> connections = registry.snapshot(event.uid());
        if (connections.isEmpty()) {
            return;
        }
        Message push = push(event);
        if (push == null) {
            return;
        }
        for (ClientConnection target : connections) {
            try {
                target.send(push);
            } catch (IOException ignored) {
                // 单连接写失败不阻止同账号其他连接、同轮后续事件或未来轮次
            }
        }
        // 存在当前连接即视为一次真实发送尝试，与写入是否成功无关；零连接已在上面提前返回
        try {
            dao.markAttempt(connection, event.eventId(), now);
        } catch (SQLException | RuntimeException failure) {
            System.err.println("课程事件 attempt 记录失败: " + failure.getMessage());
        }
    }

    private Message push(OutboxEvent event) {
        CoursePushEventTypeDTO eventType;
        try {
            eventType = CoursePushEventTypeDTO.valueOf(event.eventType());
        } catch (IllegalArgumentException | NullPointerException failure) {
            return null;
        }
        Map<String, Object> payload = parsePayload(event.payload());
        String offeringId = event.offeringId() != null
                ? Long.toString(event.offeringId())
                : string(payload, "offeringId");
        String occurredAt = string(payload, "occurredAt");
        if (occurredAt == null && event.createdAt() != null) {
            occurredAt = event.createdAt().toString();
        }
        CoursePushEventDTO dto = new CoursePushEventDTO(
                Long.toString(event.eventId()),
                eventType,
                new CourseTermDTO(event.academicYear(), event.semester(), ""),
                offeringId,
                occurredAt,
                string(payload, "expiresAt"),
                string(payload, "message"));
        Message push = new Message(MessageType.PUSH, "course", CourseActions.SELECTION_EVENT);
        push.putData("event", dto);
        return push;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = GSON.fromJson(payload, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException failure) {
            return Map.of();
        }
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 关闭失败不影响后续周期
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
    }

    private static ScheduledExecutorService daemonExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "course-event-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
    }
}
