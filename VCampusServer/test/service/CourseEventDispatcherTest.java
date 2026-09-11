package service;

import com.google.gson.Gson;
import dao.CourseEventOutboxDAO;
import dto.course.CourseActions;
import handler.CourseHandler;
import network.ClientConnection;
import network.OnlineConnectionRegistry;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.SessionManager;
import session.UserSession;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;

public final class CourseEventDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-09-11T03:00:00Z");

    private CourseEventDispatcherTest() {
    }

    public static void main(String[] args) throws Exception {
        FakeOutboxDAO dao = new FakeOutboxDAO();
        dao.events.add(event(1, "student-alpha"));
        dao.events.add(event(2, "student-beta"));
        OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
        StringWriter firstOutput = new StringWriter();
        StringWriter secondOutput = new StringWriter();
        StringWriter betaOutput = new StringWriter();
        registry.bind("student-alpha", new ClientConnection(firstOutput, firstOutput));
        registry.bind("student-alpha", new ClientConnection(secondOutput, secondOutput));
        registry.bind("student-alpha", new ClientConnection(new FailingWriter(), () -> { }));
        registry.bind("student-beta", new ClientConnection(betaOutput, betaOutput));
        CourseEventDispatcher dispatcher = new CourseEventDispatcher(registry, dao,
                () -> null, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1), 20,
                Executors.newSingleThreadScheduledExecutor());

        dispatcher.runOnce(NOW);
        assertEvent(firstOutput, "1");
        assertEvent(secondOutput, "1");
        assertEvent(betaOutput, "2");
        require(dao.attempted.equals(List.of(1L, 2L)),
                "a failed writer must not prevent other connections or later events");

        dispatcher.runOnce(NOW.plusSeconds(1));
        require(firstOutput.toString().lines().count() == 2,
                "an unacked event must be delivered again");
        require(!dispatcher.acknowledge("student-beta", 1L),
                "another account must not acknowledge an event");
        require(dispatcher.acknowledge("student-alpha", 1L),
                "the authenticated event owner may acknowledge it");
        require(dispatcher.acknowledge("student-alpha", 1L),
                "the authenticated event owner may acknowledge an already ACKed event");
        require(!dispatcher.acknowledge("student-alpha", 99L),
                "a missing event must not be acknowledged");

        dao.events.add(event(3, "offline-student"));
        registry.bind("student-gamma", new ClientConnection(new FailingWriter(), () -> { }));
        dao.events.add(event(4, "student-gamma"));
        dispatcher.runOnce(NOW.plusSeconds(2));
        require(!dao.attempted.contains(3L), "zero connections must leave an event unattempted");
        require(dao.attempted.contains(4L),
                "a live connection whose writer fails is still an actual send attempt");

        verifyOnlineUidFairness();
        verifyCourseHandlerAck(registry, dao, dispatcher);

        dispatcher.close();
        dispatcher.close();
        registry.close();
        System.out.println("Course event dispatcher test passed.");
    }

    private static void verifyCourseHandlerAck(OnlineConnectionRegistry registry,
                                               FakeOutboxDAO dao,
                                               CourseEventDispatcher dispatcher) {
        SessionManager sessions = SessionManager.getInstance();
        UserSession alpha = sessions.createSession("student-alpha", "学生");
        UserSession beta = sessions.createSession("student-beta", "学生");
        CourseHandler handler = new CourseHandler(dispatcher);
        dao.events.add(event(5, "student-alpha"));
        try {
            Message forged = ackRequest(beta.getToken(), "5");
            forged.setSender("student-alpha");
            forged.putData("uid", "student-alpha");
            Message forgedResponse = handler.handle(forged);
            require(forgedResponse.getCode() == MessageCode.SUCCESS
                            && Boolean.FALSE.equals(forgedResponse.getData().get("acked")),
                    "forged sender/data UID must not let another session acknowledge an event");

            Message ownerResponse = handler.handle(ackRequest(alpha.getToken(), "5"));
            require(ownerResponse.getCode() == MessageCode.SUCCESS
                            && Boolean.TRUE.equals(ownerResponse.getData().get("acked")),
                    "the authenticated owner may acknowledge through CourseHandler");
            Message duplicateOwnerResponse = handler.handle(ackRequest(alpha.getToken(), "5"));
            require(duplicateOwnerResponse.getCode() == MessageCode.SUCCESS
                            && Boolean.TRUE.equals(duplicateOwnerResponse.getData().get("acked")),
                    "CourseHandler must preserve an owner-idempotent ACK result");

            require(handler.handle(ackRequest(alpha.getToken(), 5)).getCode()
                            == MessageCode.BAD_REQUEST,
                    "a numeric eventId must not be accepted for ACK");
            require(handler.handle(ackRequest(alpha.getToken(), "5.0")).getCode()
                            == MessageCode.BAD_REQUEST,
                    "a non-decimal eventId must not be accepted for ACK");
            require(handler.handle(ackRequest(alpha.getToken(), "0")).getCode()
                            == MessageCode.BAD_REQUEST,
                    "a zero eventId must not be accepted for ACK");
        } finally {
            sessions.removeSession(alpha.getToken());
            sessions.removeSession(beta.getToken());
        }
    }

    private static void verifyOnlineUidFairness() {
        FakeOutboxDAO dao = new FakeOutboxDAO();
        dao.events.add(event(1, "offline-first"));
        dao.events.add(event(2, "offline-second"));
        dao.events.add(event(3, "student-alpha"));
        OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
        StringWriter output = new StringWriter();
        registry.bind("student-alpha", new ClientConnection(output, output));
        CourseEventDispatcher dispatcher = new CourseEventDispatcher(registry, dao, () -> null,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1), 2,
                Executors.newSingleThreadScheduledExecutor());
        try {
            dispatcher.runOnce(NOW);
            assertEvent(output, "3");
            require(dao.onlineUidQuery.equals(List.of("student-alpha")),
                    "dispatcher must query only the current online UID snapshot");
            require(dao.onlinePendingLimit == 2,
                    "the online-UID query must retain the configured batch limit");
            require(dao.attempted.equals(List.of(3L)),
                    "offline prefix events must not consume a bounded online delivery batch");
        } finally {
            dispatcher.close();
            registry.close();
        }
    }

    private static Message ackRequest(String token, Object eventId) {
        Message request = new Message(MessageType.REQUEST, "course",
                CourseActions.ACK_COURSE_EVENT);
        request.setToken(token);
        request.putData("eventId", eventId);
        return request;
    }

    private static CourseEventOutboxDAO.OutboxEvent event(long id, String uid) {
        return new CourseEventOutboxDAO.OutboxEvent(id, uid, "WAITLIST_OFFERED",
                2026, 2, 2001L, "{\"offeringId\":\"2001\","
                + "\"occurredAt\":\"2026-09-11T03:00:00Z\","
                + "\"expiresAt\":\"2026-09-11T03:05:00Z\",\"message\":\"ready\"}", NOW);
    }

    private static void assertEvent(StringWriter output, String eventId) {
        Message message = new Gson().fromJson(output.toString().lines().findFirst().orElseThrow(),
                Message.class);
        require("course".equals(message.getModule()) && "selectionEvent".equals(message.getAction()),
                "dispatcher must use the course selectionEvent push route");
        Object raw = message.getData().get("event");
        require(raw != null && new Gson().toJson(raw).contains("\"eventId\":\"" + eventId + "\""),
                "push payload must contain the stable event ID for deduplication");
    }

    private static final class FakeOutboxDAO extends CourseEventOutboxDAO {
        private final List<OutboxEvent> events = new ArrayList<>();
        private final List<Long> attempted = new ArrayList<>();
        private final java.util.Set<Long> acked = new java.util.HashSet<>();
        private List<String> onlineUidQuery = List.of();
        private int onlinePendingLimit = -1;

        @Override
        public List<OutboxEvent> pending(Connection connection, Collection<String> onlineUids,
                                         int limit) {
            onlineUidQuery = List.copyOf(onlineUids);
            onlinePendingLimit = limit;
            return events.stream().filter(event -> !acked.contains(event.eventId()))
                    .filter(event -> onlineUids.contains(event.uid()))
                    .limit(limit).toList();
        }

        @Override
        public void markAttempt(Connection connection, long eventId, Instant now) {
            attempted.add(eventId);
        }

        @Override
        public boolean acknowledge(Connection connection, String uid, long eventId, Instant now) {
            boolean ownedEvent = events.stream().anyMatch(event -> event.eventId() == eventId
                    && event.uid().equals(uid));
            if (ownedEvent) {
                acked.add(eventId);
            }
            return ownedEvent;
        }
    }

    private static final class FailingWriter extends Writer {
        @Override public void write(char[] buffer, int offset, int length) throws IOException {
            throw new IOException("expected writer failure");
        }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
