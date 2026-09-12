package integration;

import com.google.gson.Gson;
import dao.CourseEventOutboxDAO;
import dto.course.CourseActions;
import dto.course.CourseMutationResultDTO;
import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CoursePushEventDTO;
import dto.course.CoursePushEventTypeDTO;
import dto.course.CourseSelectionItemDTO;
import dto.course.SelectionStateDTO;
import handler.CourseHandler;
import network.MessageDispatcher;
import network.OnlineConnectionRegistry;
import network.Server;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.CourseEventDispatcher;
import service.CourseWaitlistService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 9 live end-to-end verification.
 *
 * <p>Aborts unless the JDBC database is exactly {@code virtual_campus_course_test}. It resets and
 * reseeds only that schema, starts the real {@link Server} on an OS-assigned loopback port and then
 * drives the real JSON-line socket protocol through the course workflow. Persisted state is asserted
 * through separate JDBC connections after each protocol response.
 *
 * <p>Run from the repository root with the server, common and test classes and the server libraries
 * on the classpath.
 */
public final class CourseModuleSocketEndToEndTest {

    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final int ACADEMIC_YEAR = 2026;
    private static final int SEMESTER = 2;
    private static final String LOGIN_PASSWORD = "course-test-only";
    private static final String STUDENT_ROLE = "学生";
    private static final String TEACHER_HASH = "J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=";
    private static final String TEST_SALT = "Y291cnNlLXRlc3Qtc2FsdC12MQ==";
    private static final String OFFERING_A = "2001";
    private static final String OFFERING_B = "2002";
    private static final String COURSE_A = "1001";
    private static final long COURSE_A_ID = 1001L;
    private static final long OFFERING_A_ID = 2001L;
    private static final long OFFERING_B_ID = 2002L;
    private static final Gson GSON = new Gson();
    private static final Pattern TBL_USER = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`tbl_user`.*?ENGINE\\s*=\\s*InnoDB.*?;");

    private CourseModuleSocketEndToEndTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        Properties properties = loadProperties(
                root.resolve("VCampusServer/src/resources/db.properties"));
        String url = requiredProperty(properties, "db.url");
        requireTestDatabase(url);
        String jdbc = withTestAuthentication(url);
        String username = requiredProperty(properties, "db.username");
        String password = requiredProperty(properties, "db.password");
        Class.forName(DRIVER);

        System.out.println("[E2E] reset+seed " + TEST_DATABASE);
        resetAndSeed(jdbc, username, password, root);
        System.out.println("[E2E] apply deterministic E2E fixture");
        applyFixture(jdbc, username, password);
        System.out.println("[E2E] fixture applied");

        Harness harness = new Harness();
        try {
            harness.start();
            System.out.println("[E2E] server listening on 127.0.0.1:" + harness.port());
            runScenarios(harness, jdbc, username, password);
        } finally {
            harness.stop();
        }
        System.out.println("Course module socket end-to-end test passed.");
    }

    private static void runScenarios(Harness harness, String jdbc, String user, String pass)
            throws Exception {
        alphaIndex(harness, jdbc, user, pass);
        betaFullWaitlistAndAutoEnroll(harness, jdbc, user, pass);
        alphaOfflineOfferExpiryAndReplay(harness, jdbc, user, pass);
    }

    /** listTerms -> listCourses -> listCourseOfferings on the seeded catalog. */
    private static void alphaIndex(Harness harness, String jdbc, String user, String pass)
            throws Exception {
        try (JsonLineClient alpha = harness.client()) {
            String token = alpha.login("student-alpha");

            Message terms = alpha.course(CourseActions.LIST_TERMS, token, Map.of());
            requireSuccess(terms, "listTerms");
            List<Map<String, Object>> termList = asList(terms.getData("terms"));
            require(termList.stream().anyMatch(term -> number(term.get("academicYear")) == ACADEMIC_YEAR
                            && number(term.get("semester")) == SEMESTER),
                    "listTerms must expose the seeded 2026/2 term");
            System.out.println("[E2E] listTerms -> 2026/2");

            Message courses = alpha.course(CourseActions.LIST_COURSES, token,
                    term(ACADEMIC_YEAR, SEMESTER));
            requireSuccess(courses, "listCourses");
            List<Map<String, Object>> courseList = asList(courses.getData("courses"));
            require(courseList.stream().anyMatch(course -> COURSE_A.equals(course.get("courseId"))),
                    "listCourses must expose the seeded course 1001");
            System.out.println("[E2E] listCourses -> " + courseList.size() + " courses");

            Map<String, Object> offeringParams = term(ACADEMIC_YEAR, SEMESTER);
            offeringParams.put("courseId", COURSE_A);
            Message offerings = alpha.course(CourseActions.LIST_COURSE_OFFERINGS, token,
                    offeringParams);
            requireSuccess(offerings, "listCourseOfferings");
            List<Map<String, Object>> offeringList = asList(offerings.getData("offerings"));
            require(offeringList.stream().anyMatch(item -> OFFERING_A.equals(item.get("offeringId")))
                            && offeringList.stream().anyMatch(
                                    item -> OFFERING_B.equals(item.get("offeringId"))),
                    "course 1001 must expose both seeded offerings");
            require(offeringList.stream()
                            .filter(item -> OFFERING_A.equals(item.get("offeringId")))
                            .findFirst().orElseThrow().get("meetings") instanceof List<?> meetings
                            && meetings.size() == 2,
                    "offering 2001 must expose its two meetings");
            System.out.println("[E2E] listCourseOfferings -> " + offeringList.size() + " offerings");
            assertDatabase(jdbc, user, pass, connection -> {
                require(queryInt(connection, "SELECT COUNT(*) FROM course_offering WHERE offering_id = ?",
                        OFFERING_A_ID) == 1, "seeded offering must exist");
            });
        }
    }

    /**
     * Seeded full offering: beta plans, hits FULL, joins the waitlist, another student drops the
     * last seat, the waitlist advances and beta is auto-enrolled and ACKs the push.
     */
    private static void betaFullWaitlistAndAutoEnroll(Harness harness, String jdbc, String user,
                                                      String pass) throws Exception {
        try (JsonLineClient beta = harness.client();
             JsonLineClient gamma = harness.client()) {
            String betaToken = beta.login("student-beta");
            String gammaToken = gamma.login("student-gamma");

            Message cancel = beta.course(CourseActions.CANCEL_WAITLIST, betaToken,
                    offering(ACADEMIC_YEAR, SEMESTER, OFFERING_B));
            CourseMutationResultDTO cancelResult = mutation(cancel);
            require("WAITLIST_CANCELLED".equals(cancelResult.getOutcomeCode()),
                    "cancelWaitlist must release the seeded waiting row, got "
                            + cancelResult.getOutcomeCode());
            System.out.println("[E2E] beta cancelled the seeded 2002 waitlist");

            Message plan = beta.course(CourseActions.ADD_TO_PLAN, betaToken,
                    offering(ACADEMIC_YEAR, SEMESTER, OFFERING_B));
            CourseMutationResultDTO planResult = mutation(plan);
            require("PLANNED".equals(planResult.getOutcomeCode()),
                    "addToPlan must plan the full offering, got " + planResult.getOutcomeCode());

            Message select = beta.course(CourseActions.SELECT_OFFERING, betaToken,
                    offering(ACADEMIC_YEAR, SEMESTER, OFFERING_B));
            CourseMutationResultDTO selectResult = mutation(select);
            require(select.getCode() == MessageCode.CONFLICT
                            && "FULL".equals(selectResult.getOutcomeCode()),
                    "selecting the full offering must report FULL, got "
                            + selectResult.getOutcomeCode());
            require(selectResult.getFinalState() == SelectionStateDTO.FULL,
                    "full selection must leave the student FULL");
            System.out.println("[E2E] beta select full -> FULL");

            Message join = beta.course(CourseActions.JOIN_WAITLIST, betaToken,
                    offering(ACADEMIC_YEAR, SEMESTER, OFFERING_B));
            CourseMutationResultDTO joinResult = mutation(join);
            require("WAITLISTED".equals(joinResult.getOutcomeCode()),
                    "joinWaitlist must queue on a full offering, got " + joinResult.getOutcomeCode());
            System.out.println("[E2E] beta joinWaitlist -> WAITLISTED");
            assertDatabase(jdbc, user, pass, connection -> {
                require("WAITING".equals(queryString(connection,
                                "SELECT status FROM course_waitlist WHERE uid = ? AND offering_id = ?",
                                "student-beta", OFFERING_B_ID)),
                        "beta must be WAITING after joining");
                require("FULL".equals(queryString(connection,
                                "SELECT status FROM course_plan_item WHERE uid = ? AND offering_id = ?",
                                "student-beta", OFFERING_B_ID)),
                        "beta plan must stay FULL while queueing");
            });

            Message gammaDrop = gamma.course(CourseActions.DROP_OFFERING, gammaToken,
                    offering(ACADEMIC_YEAR, SEMESTER, OFFERING_B));
            CourseMutationResultDTO gammaDropResult = mutation(gammaDrop);
            require("DROPPED".equals(gammaDropResult.getOutcomeCode()),
                    "gamma must drop the last seat, got " + gammaDropResult.getOutcomeCode());
            System.out.println("[E2E] gamma dropped the last seat");

            assertDatabase(jdbc, user, pass, connection -> {
                require(queryInt(connection,
                                "SELECT COUNT(*) FROM enrollment WHERE uid = ? AND offering_id = ?"
                                        + " AND status = 2", "student-beta", OFFERING_B_ID) == 1,
                        "waitlist advance must auto-enroll beta");
                require("ENROLLED".equals(queryString(connection,
                                "SELECT status FROM course_waitlist WHERE uid = ? AND offering_id = ?",
                                "student-beta", OFFERING_B_ID)),
                        "auto-enrolled waitlist row must be ENROLLED");
            });

            driveDispatcher(harness);
            Message push = beta.awaitPush(CoursePushEventTypeDTO.WAITLIST_AUTO_ENROLLED, 10_000);
            CoursePushEventDTO autoEnrolled = pushEvent(push);
            long autoEventId = Long.parseLong(autoEnrolled.getEventId());
            System.out.println("[E2E] beta received WAITLIST_AUTO_ENROLLED event " + autoEventId);
            assertDatabase(jdbc, user, pass, connection -> {
                require("WAITLIST_AUTO_ENROLLED".equals(queryString(connection,
                                "SELECT event_type FROM course_event_outbox WHERE event_id = ?",
                                autoEventId)),
                        "outbox must hold the auto-enroll event");
                require(queryInt(connection,
                                "SELECT COUNT(*) FROM course_event_outbox WHERE event_id = ?"
                                        + " AND acked_at IS NULL", autoEventId) == 1,
                        "auto-enroll event must start unacked");
            });

            Message ack = beta.course(CourseActions.ACK_COURSE_EVENT, betaToken,
                    Map.of("eventId", Long.toString(autoEventId)));
            requireSuccess(ack, "ackCourseEvent");
            require(Boolean.TRUE.equals(ack.getData("acked")),
                    "server must confirm the auto-enroll event");
            assertDatabase(jdbc, user, pass, connection -> require(queryInt(connection,
                            "SELECT COUNT(*) FROM course_event_outbox WHERE event_id = ?"
                                    + " AND acked_at IS NOT NULL AND uid = 'student-beta'",
                            autoEventId) == 1,
                    "ACK must be recorded against the authenticated student"));
            System.out.println("[E2E] beta ACKed event " + autoEventId);

            CoursePlanSnapshotDTO snapshot = snapshot(beta.course(
                    CourseActions.LOAD_SELECTION_SNAPSHOT, betaToken, term(ACADEMIC_YEAR, SEMESTER)));
            require(snapshot.getEnrolledItems().stream()
                            .anyMatch(item -> OFFERING_B.equals(offeringId(item))),
                    "snapshot must show beta enrolled in 2002");
            require(snapshot.getWaitlistItems().isEmpty(),
                    "snapshot must not keep beta on the waitlist after auto-enroll");
            System.out.println("[E2E] loadSelectionSnapshot -> enrolled 2002");

            Message schedule = beta.course(CourseActions.LOAD_SCHEDULE, betaToken,
                    week(ACADEMIC_YEAR, SEMESTER, 1));
            requireSuccess(schedule, "loadSchedule");
            require(!asList(schedule.getData("schedule")).isEmpty(),
                    "beta schedule must contain the newly selected meeting");
            requireSuccess(beta.course(CourseActions.LOAD_NOTICES, betaToken,
                    week(ACADEMIC_YEAR, SEMESTER, 1)), "loadNotices");
            requireSuccess(beta.course(CourseActions.LOAD_GRADES, betaToken,
                    term(ACADEMIC_YEAR, SEMESTER)), "loadGrades");
            Message trainingPlan = beta.course(CourseActions.LOAD_TRAINING_PLAN, betaToken, Map.of());
            requireSuccess(trainingPlan, "loadTrainingPlan");
            require(!asList(trainingPlan.getData("trainingPlan")).isEmpty(),
                    "beta training plan must be published");
            System.out.println("[E2E] loadSchedule/loadNotices/loadGrades/loadTrainingPlan OK");
        }
    }

    /**
     * A waitlisted student is offered a conflicting seat while online, the push is replayed without
     * an ACK, the student disconnects, the offer expires offline, and on reconnect the snapshot
     * restores the authoritative state and deadline.
     */
    private static void alphaOfflineOfferExpiryAndReplay(Harness harness, String jdbc, String user,
                                                         String pass) throws Exception {
        try (JsonLineClient beta = harness.client()) {
            String betaToken = beta.login("student-beta");
            CourseMutationResultDTO drop = mutation(beta.course(CourseActions.DROP_OFFERING,
                    betaToken, offering(ACADEMIC_YEAR, SEMESTER, OFFERING_B)));
            require("DROPPED".equals(drop.getOutcomeCode()),
                    "beta must free the seat again, got " + drop.getOutcomeCode());
            System.out.println("[E2E] beta dropped 2002, vacancy restored");
        }
        assertDatabase(jdbc, user, pass, connection -> require(queryInt(connection,
                        "SELECT enrolled_count FROM course_offering WHERE offering_id = ?",
                        OFFERING_B_ID) == 0, "offering 2002 must be vacant"));

        JsonLineClient alpha = harness.client();
        try {
            String token = alpha.login("student-alpha");
            Message join = alpha.course(CourseActions.JOIN_WAITLIST, token,
                    offering(ACADEMIC_YEAR, SEMESTER, OFFERING_B));
            CourseMutationResultDTO joinResult = mutation(join);
            require("WAITLIST_OFFERED".equals(joinResult.getOutcomeCode()),
                    "conflicting waitlist must be offered a seat, got "
                            + joinResult.getOutcomeCode());
            require(joinResult.getFinalState() == SelectionStateDTO.WAITLIST_OFFERED,
                    "offered waitlist must report WAITLIST_OFFERED");
            System.out.println("[E2E] alpha joinWaitlist -> WAITLIST_OFFERED");

            Instant databaseDeadline = queryDatabase(jdbc, user, pass, connection -> {
                require("OFFERED".equals(queryString(connection,
                                "SELECT status FROM course_waitlist WHERE uid = ? AND offering_id = ?",
                                "student-alpha", OFFERING_B_ID)),
                        "alpha offer must be persisted");
                return instant(connection, "SELECT expires_at FROM course_waitlist"
                        + " WHERE uid = ? AND offering_id = ?", "student-alpha", OFFERING_B_ID);
            });

            driveDispatcher(harness);
            Message first = alpha.awaitPush(CoursePushEventTypeDTO.WAITLIST_OFFERED, 10_000);
            CoursePushEventDTO firstEvent = pushEvent(first);
            require(deadlineMatches(firstEvent.getExpiresAt(), databaseDeadline),
                    "push deadline must match the persisted offer deadline");

            driveDispatcher(harness);
            Message replay = alpha.awaitPush(CoursePushEventTypeDTO.WAITLIST_OFFERED, 10_000);
            require(pushEvent(replay).getEventId().equals(firstEvent.getEventId()),
                    "replayed delivery must reuse the same event id");
            System.out.println("[E2E] alpha received and replayed WAITLIST_OFFERED "
                    + firstEvent.getEventId());

            CoursePlanSnapshotDTO live = snapshot(alpha.course(
                    CourseActions.LOAD_SELECTION_SNAPSHOT, token, term(ACADEMIC_YEAR, SEMESTER)));
            CourseOfferingDTO offered = waitlistOffering(live, OFFERING_B);
            require(offered != null && offered.getSelectionState() == SelectionStateDTO.WAITLIST_OFFERED,
                    "snapshot must restore WAITLIST_OFFERED after replay");
            require(deadlineMatches(offered.getExpiresAt(), databaseDeadline),
                    "snapshot must restore the authoritative offer deadline");
            System.out.println("[E2E] snapshot restored WAITLIST_OFFERED deadline " + databaseDeadline);

            alpha.close();
            require(harness.awaitOffline("student-alpha", 10_000),
                    "alpha must be unbound from the online registry");

            assertDatabase(jdbc, user, pass, connection -> {
                execute(connection, "UPDATE course_waitlist SET"
                        + " offered_at = UTC_TIMESTAMP(6) - INTERVAL 20 MINUTE,"
                        + " expires_at = UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE"
                        + " WHERE uid = 'student-alpha'"
                        + " AND offering_id = 2002 AND status = 'OFFERED'");
            });
            int expired = harness.expireOverdueOffers();
            require(expired >= 1, "offline expiry must transition the offer");
            System.out.println("[E2E] offer expired while alpha offline (" + expired + " row)");
            assertDatabase(jdbc, user, pass, connection -> {
                require("EXPIRED".equals(queryString(connection,
                                "SELECT status FROM course_waitlist WHERE uid = ? AND offering_id = ?",
                                "student-alpha", OFFERING_B_ID)),
                        "offer must be EXPIRED after the offline timeout");
                require("FULL".equals(queryString(connection,
                                "SELECT status FROM course_plan_item WHERE uid = ? AND offering_id = ?",
                                "student-alpha", OFFERING_B_ID)),
                        "expired offer must fall back to FULL");
                require(queryInt(connection, "SELECT COUNT(*) FROM course_event_outbox"
                                + " WHERE uid = 'student-alpha'"
                                + " AND event_type = 'WAITLIST_OFFER_EXPIRED'"
                                + " AND acked_at IS NULL") == 1,
                        "offline expiry must enqueue an unacked expiry event");
            });

            alpha = harness.client();
            token = alpha.login("student-alpha");
            driveDispatcher(harness);
            Message offeredReplay = alpha.awaitPush(CoursePushEventTypeDTO.WAITLIST_OFFERED, 10_000);
            Message expiredPush = alpha.awaitPush(CoursePushEventTypeDTO.WAITLIST_OFFER_EXPIRED, 10_000);
            long expiredEventId = Long.parseLong(pushEvent(expiredPush).getEventId());
            System.out.println("[E2E] reconnect replayed " + pushEvent(offeredReplay).getEventId()
                    + " and delivered expiry " + expiredEventId);

            CoursePlanSnapshotDTO afterExpiry = snapshot(alpha.course(
                    CourseActions.LOAD_SELECTION_SNAPSHOT, token, term(ACADEMIC_YEAR, SEMESTER)));
            require(afterExpiry.getWaitlistItems().isEmpty(),
                    "expired offer must leave the waitlist section");
            CourseSelectionItemDTO planItem = afterExpiry.getPlanItems().stream()
                    .filter(item -> OFFERING_B.equals(offeringId(item)))
                    .findFirst().orElseThrow(() ->
                            new AssertionError("expired offer must restore the FULL plan item"));
            require(planItem.getOffering().getSelectionState() == SelectionStateDTO.FULL,
                    "snapshot must restore the authoritative FULL state");
            System.out.println("[E2E] snapshot after expiry -> FULL plan item");

            long offeredEventId = Long.parseLong(pushEvent(offeredReplay).getEventId());
            for (long eventId : new long[]{offeredEventId, expiredEventId}) {
                Message ack = alpha.course(CourseActions.ACK_COURSE_EVENT, token,
                        Map.of("eventId", Long.toString(eventId)));
                requireSuccess(ack, "ackCourseEvent " + eventId);
                require(Boolean.TRUE.equals(ack.getData("acked")),
                        "ACK must confirm event " + eventId);
            }
            Message duplicateAck = alpha.course(CourseActions.ACK_COURSE_EVENT, token,
                    Map.of("eventId", Long.toString(offeredEventId)));
            requireSuccess(duplicateAck, "duplicate ackCourseEvent " + offeredEventId);
            require(Boolean.TRUE.equals(duplicateAck.getData("acked")),
                    "a repeated ACK by the owner must stay idempotent over the real socket");
            assertDatabase(jdbc, user, pass, connection -> require(queryInt(connection,
                            "SELECT COUNT(*) FROM course_event_outbox WHERE uid = 'student-alpha'"
                                    + " AND acked_at IS NULL") == 0,
                    "all alpha events must be acknowledged"));
            System.out.println("[E2E] alpha ACKed offer and expiry events (duplicate ACK idempotent)");

            requireSuccess(alpha.course(CourseActions.LOAD_SCHEDULE, token,
                    week(ACADEMIC_YEAR, SEMESTER, 1)), "alpha loadSchedule");
            requireSuccess(alpha.course(CourseActions.LOAD_NOTICES, token,
                    week(ACADEMIC_YEAR, SEMESTER, 1)), "alpha loadNotices");
            requireSuccess(alpha.course(CourseActions.LOAD_GRADES, token,
                    term(ACADEMIC_YEAR, SEMESTER)), "alpha loadGrades");
            requireSuccess(alpha.course(CourseActions.LOAD_TRAINING_PLAN, token, Map.of()),
                    "alpha loadTrainingPlan");
            System.out.println("[E2E] alpha read APIs OK");
        } finally {
            alpha.close();
        }
    }

    // ------------------------------------------------------------------
    // Server harness
    // ------------------------------------------------------------------

    private static final class Harness {
        private final OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
        private final CourseWaitlistService waitlist = new CourseWaitlistService();
        private final CourseEventDispatcher dispatcher = new CourseEventDispatcher(
                registry, new CourseEventOutboxDAO(), CourseModuleSocketEndToEndTest::openConnection);
        private final Server server;
        private final Thread thread;

        Harness() {
            CourseHandler handler = new CourseHandler(waitlist, dispatcher);
            this.server = new Server(0, registry, new MessageDispatcher(handler), dispatcher, null);
            this.thread = new Thread(server::start, "e2e-server");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
            long deadline = System.currentTimeMillis() + 10_000;
            while (!server.isRunning() && System.currentTimeMillis() < deadline) {
                sleep(20);
            }
            require(server.isRunning(), "server must start listening");
        }

        void stop() {
            server.stop();
        }

        int port() {
            return server.getPort();
        }

        JsonLineClient client() throws IOException {
            return new JsonLineClient("127.0.0.1", port());
        }

        boolean awaitOffline(String uid, long timeoutMillis) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (registry.snapshot(uid).isEmpty()) {
                    return true;
                }
                sleep(50);
            }
            return registry.snapshot(uid).isEmpty();
        }

        void driveDispatcher() throws Exception {
            java.lang.reflect.Method runOnce = CourseEventDispatcher.class
                    .getDeclaredMethod("runOnce", Instant.class);
            runOnce.setAccessible(true);
            runOnce.invoke(dispatcher, Instant.now());
        }

        int expireOverdueOffers() throws Exception {
            java.lang.reflect.Method expire = CourseWaitlistService.class
                    .getDeclaredMethod("expireOverdueOffers", Instant.class, int.class);
            expire.setAccessible(true);
            return (Integer) expire.invoke(waitlist, Instant.now(), 100);
        }
    }

    private static void driveDispatcher(Harness harness) throws Exception {
        harness.driveDispatcher();
    }

    private static Connection openConnection() {
        try {
            return util.DBUtil.getConnection();
        } catch (SQLException failure) {
            throw new IllegalStateException("database connection failed", failure);
        }
    }

    // ------------------------------------------------------------------
    // Real JSON-line client
    // ------------------------------------------------------------------

    private static final class JsonLineClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();
        private final BlockingQueue<Message> pushes = new LinkedBlockingQueue<>();

        JsonLineClient(String host, int port) throws IOException {
            this.socket = new Socket(host, port);
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Thread readerThread = new Thread(this::readLoop, "e2e-client-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void readLoop() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Message message = GSON.fromJson(line, Message.class);
                    if (message == null) {
                        continue;
                    }
                    if (message.getType() == MessageType.PUSH) {
                        pushes.add(message);
                    } else {
                        CompletableFuture<Message> future = pending.remove(message.getUID());
                        if (future != null) {
                            future.complete(message);
                        }
                    }
                }
            } catch (IOException closed) {
                // socket closed by the test
            }
        }

        String login(String uid) {
            Message response = request("user", "login", null, Map.of(
                    "cardNo", uid, "password", LOGIN_PASSWORD, "role", STUDENT_ROLE));
            requireSuccess(response, "login " + uid);
            Object token = response.getData("token");
            require(token instanceof String value && !value.isBlank(), "login must return a token");
            return (String) token;
        }

        Message course(String action, String token, Map<String, Object> data) {
            return request("course", action, token, data);
        }

        Message request(String module, String action, String token, Map<String, Object> data) {
            Message message = new Message(MessageType.REQUEST, module, action);
            if (token != null) {
                message.setToken(token);
            }
            if (data != null) {
                data.forEach(message::putData);
            }
            CompletableFuture<Message> future = new CompletableFuture<>();
            pending.put(message.getUID(), future);
            try {
                synchronized (writer) {
                    writer.write(GSON.toJson(message));
                    writer.write("\n");
                    writer.flush();
                }
            } catch (IOException failure) {
                throw new IllegalStateException("send failed", failure);
            }
            try {
                return future.get(20, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("no response for " + module + "/" + action, failure);
            }
        }

        Message awaitPush(CoursePushEventTypeDTO type, long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                Message push = pushes.poll(deadline - System.currentTimeMillis(),
                        TimeUnit.MILLISECONDS);
                if (push == null) {
                    break;
                }
                if (pushEvent(push).getEventType() == type) {
                    return push;
                }
            }
            throw new AssertionError("did not receive push " + type);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    // ------------------------------------------------------------------
    // Protocol helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> term(int academicYear, int semester) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("academicYear", academicYear);
        data.put("semester", semester);
        return data;
    }

    private static Map<String, Object> week(int academicYear, int semester, int week) {
        Map<String, Object> data = term(academicYear, semester);
        data.put("week", week);
        return data;
    }

    private static Map<String, Object> offering(int academicYear, int semester, String offeringId) {
        Map<String, Object> data = term(academicYear, semester);
        data.put("offeringId", offeringId);
        data.put("operationId", UUID.randomUUID().toString());
        return data;
    }

    private static CourseMutationResultDTO mutation(Message response) {
        Object raw = response.getData("result");
        require(raw != null, "mutation response must carry a result");
        return GSON.fromJson(GSON.toJson(raw), CourseMutationResultDTO.class);
    }

    private static CoursePushEventDTO pushEvent(Message push) {
        Object raw = push.getData("event");
        require(raw != null, "push must carry an event");
        return GSON.fromJson(GSON.toJson(raw), CoursePushEventDTO.class);
    }

    private static CoursePlanSnapshotDTO snapshot(Message response) {
        requireSuccess(response, "loadSelectionSnapshot");
        Object raw = response.getData("snapshot");
        require(raw != null, "snapshot response must carry a snapshot");
        return GSON.fromJson(GSON.toJson(raw), CoursePlanSnapshotDTO.class);
    }

    private static String offeringId(CourseSelectionItemDTO item) {
        return item.getOffering().getOfferingId();
    }

    private static CourseOfferingDTO waitlistOffering(CoursePlanSnapshotDTO snapshot, String offeringId) {
        return snapshot.getWaitlistItems().stream()
                .filter(item -> offeringId.equals(offeringId(item)))
                .map(CourseSelectionItemDTO::getOffering)
                .findFirst().orElse(null);
    }

    private static boolean deadlineMatches(String isoDeadline, Instant databaseDeadline) {
        if (isoDeadline == null || databaseDeadline == null) {
            return false;
        }
        Instant parsed = Instant.parse(isoDeadline);
        return Math.abs(Duration.between(databaseDeadline, parsed).toMillis()) < 2_000;
    }

    private static void requireSuccess(Message response, String label) {
        require(response != null, label + " must return a response");
        require(response.getCode() == MessageCode.SUCCESS,
                label + " must succeed but was " + response.getCode() + ": "
                        + response.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        require(value instanceof List<?>, "expected a list but was " + value);
        return (List<Map<String, Object>>) value;
    }

    private static int number(Object value) {
        require(value instanceof Number, "expected a number but was " + value);
        return ((Number) value).intValue();
    }

    // ------------------------------------------------------------------
    // Database helpers
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface SqlAssertion {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlQuery<T> {
        T run(Connection connection) throws SQLException;
    }

    private static void assertDatabase(String jdbc, String user, String pass, SqlAssertion assertion)
            throws SQLException {
        try (Connection connection = connect(jdbc, user, pass)) {
            assertion.run(connection);
        }
    }

    private static <T> T queryDatabase(String jdbc, String user, String pass, SqlQuery<T> query)
            throws SQLException {
        try (Connection connection = connect(jdbc, user, pass)) {
            return query.run(connection);
        }
    }

    private static Connection connect(String jdbc, String user, String pass) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbc, user, pass);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+00:00'");
        }
        return connection;
    }

    private static int queryInt(Connection connection, String sql, Object... params)
            throws SQLException {
        return ((Number) queryScalar(connection, sql, params)).intValue();
    }

    private static String queryString(Connection connection, String sql, Object... params)
            throws SQLException {
        Object value = queryScalar(connection, sql, params);
        return value == null ? null : String.valueOf(value);
    }

    private static Instant instant(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "query returned no row");
                Timestamp value = rows.getTimestamp(1);
                return value == null ? null
                        : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
            }
        }
    }

    private static Object queryScalar(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "query returned no row: " + sql);
                return rows.getObject(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // ------------------------------------------------------------------
    // Schema reset, seed and fixture
    // ------------------------------------------------------------------

    private static void resetAndSeed(String jdbc, String user, String pass, Path root)
            throws Exception {
        try (Connection connection = connect(jdbc, user, pass)) {
            requireTestDatabase(jdbc);
            resetTestSchema(connection);
            applyTblUser(connection, root.resolve("VCampusServer/src/resources/init.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V001_create_course_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V002_create_schedule_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V003_extend_course_management.sql"));
            // V004 backfills course_schedule_rule.arrangement_id for existing rules and then
            // tightens it to NOT NULL, so the legacy seed must be loaded before the migration.
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/seed-course-test.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"));
        }
    }

    private static void applyFixture(String jdbc, String user, String pass) throws SQLException {
        try (Connection connection = connect(jdbc, user, pass)) {
            execute(connection, "DELETE FROM course_event_outbox");
            execute(connection, "INSERT INTO tbl_user (UID, name, password, salt, role, college, major)"
                    + " VALUES ('student-gamma', 'Course Test Student C', '" + TEACHER_HASH + "', '"
                    + TEST_SALT + "', 2, 'Engineering', 'Computer Science')");
            execute(connection, "INSERT INTO student_academic_profile"
                    + " (profile_id, uid, major_id, cohort_year, status)"
                    + " VALUES (5003, 'student-gamma', 10, 2026, 'ACTIVE')");
            execute(connection, "UPDATE course_offering SET capacity = 1, enrolled_count = 1"
                    + " WHERE offering_id = " + OFFERING_B_ID);
            execute(connection, "INSERT INTO enrollment"
                    + " (enrollment_id, offering_id, course_id, academic_year, semester, uid, status,"
                    + " select_time) VALUES (6003, " + OFFERING_B_ID + ", " + COURSE_A_ID + ", "
                    + ACADEMIC_YEAR + ", " + SEMESTER + ", 'student-gamma', 2,"
                    + " '2026-09-01 00:00:00.100003')");
            execute(connection, "UPDATE course_plan_item SET status = 'FULL',"
                    + " last_failure_reason = 'E2E fixture'"
                    + " WHERE uid = 'student-alpha' AND offering_id = " + OFFERING_B_ID);
        }
    }

    private static void resetTestSchema(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES"
                             + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        execute(connection, "SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                execute(connection, "DROP TABLE `" + table.replace("`", "``") + "`");
            }
        } finally {
            execute(connection, "SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private static void applyTblUser(Connection connection, Path initSql) throws Exception {
        Matcher matcher = TBL_USER.matcher(Files.readString(initSql, StandardCharsets.UTF_8));
        require(matcher.find(), "authoritative tbl_user definition not found");
        execute(connection, matcher.group());
    }

    private static void applyScript(Connection connection, Path path) throws Exception {
        require(Files.isRegularFile(path), "missing SQL file: " + path.getFileName());
        List<String> statements = splitStatements(Files.readString(path, StandardCharsets.UTF_8));
        for (int i = 0; i < statements.size(); i++) {
            try {
                execute(connection, statements.get(i));
            } catch (SQLException failure) {
                throw new SQLException("failed applying " + path.getFileName()
                        + " statement " + (i + 1), failure);
            }
        }
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean quotedIdentifier = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                    current.append(' ');
                }
                continue;
            }
            if (!single && !quotedIdentifier && c == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (!single && !quotedIdentifier && c == '#') {
                lineComment = true;
                continue;
            }
            if (!single && !quotedIdentifier && c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (!quotedIdentifier && c == '\'') {
                current.append(c);
                if (single && next == '\'') {
                    current.append(next);
                    i++;
                } else {
                    single = !single;
                }
                continue;
            }
            if (!single && c == '`') {
                quotedIdentifier = !quotedIdentifier;
                current.append(c);
                continue;
            }
            if (!single && !quotedIdentifier && c == ';') {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) {
            statements.add(trailing);
        }
        return statements;
    }

    // ------------------------------------------------------------------
    // Guard, config and misc helpers
    // ------------------------------------------------------------------

    private static void requireTestDatabase(String jdbcUrl) {
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        URI uri = URI.create(raw);
        String path = uri.getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database), "refusing live end-to-end test: JDBC database must be"
                + " exactly " + TEST_DATABASE + " but was " + database);
    }

    private static String withTestAuthentication(String jdbcUrl) {
        if (jdbcUrl.matches("(?i).*([?&])allowPublicKeyRetrieval=true(?:&.*)?$")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "allowPublicKeyRetrieval=true";
    }

    private static Properties loadProperties(Path path) throws IOException {
        require(Files.isRegularFile(path), "missing ignored local db.properties");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        require(value != null && !value.isBlank(), "missing database property: " + key);
        return value;
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("VCampusServer/src/resources/init.sql"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("repository root was not found");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", interrupted);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
