package service;

import dto.course.CourseActions;
import dto.course.CourseDTO;
import dto.course.CourseMeetingDTO;
import dto.course.CourseMutationResultDTO;
import dto.course.CourseNoticeDTO;
import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CoursePushEventDTO;
import dto.course.CoursePushEventTypeDTO;
import dto.course.CourseSelectionItemDTO;
import dto.course.CourseTeacherDTO;
import dto.course.CourseTermDTO;
import dto.course.GradeRecordDTO;
import dto.course.GradeSummaryDTO;
import dto.course.ScheduleEntryDTO;
import dto.course.SelectionStateDTO;
import dto.course.TrainingPlanCourseDTO;
import dto.course.TrainingPlanGroupDTO;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import model.course.CourseMutationResultView;
import model.course.CourseOfferingView;
import model.course.CoursePushEventType;
import model.course.CoursePushEventView;
import model.course.CourseSelectionItemView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeRecordView;
import model.course.GradeSummaryView;
import model.course.ScheduleEntryView;
import model.course.SelectionStatus;
import model.course.TrainingPlanGroupView;
import model.course.WaitlistDecision;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;

public final class SocketCourseServiceTest {
    private static final CourseTermDTO TERM_DTO =
            new CourseTermDTO(2026, 1, "2026-2027 秋学期");
    private static final CourseTermView TERM =
            new CourseTermView(2026, 1, "2026-2027 秋学期");

    public static void main(String[] args) throws Exception {
        loadTermsSendsCourseRequestAndMapsTerm();
        loadCoursesSendsTermKeysAndMapsCourses();
        loadCourseOfferingsUsesDecimalCourseIdAndMapsStructuredOffering();
        offeringMappingCoversAllSixSelectionStates();
        loadSelectionSnapshotMapsItemsAndTerm();
        mutationsPropagateOperationIdOfferingIdAndSnapshot();
        loadScheduleAndNoticesSendTermAndWeek();
        loadGradesMapsNullableComponents();
        loadTrainingPlanNeedsNoTerm();
        nonSuccessResponsesBecomeStableExceptions();
        ackSendsDecimalEventId();
        ackFailureWhenServerDidNotAcknowledge();
        subscribeWiresPushModuleActionAndMapsEvent();
        subscribeWiresReconnectListener();
        System.out.println("SocketCourseServiceTest: PASS");
    }

    private static void loadTermsSendsCourseRequestAndMapsTerm() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("terms", List.of(TERM_DTO)));
        SocketCourseService service = new SocketCourseService(transport);

        List<CourseTermView> terms = service.loadTerms().join();
        require(terms.size() == 1, "one term expected");
        require(terms.get(0).getAcademicYear() == 2026 && terms.get(0).getSemester() == 1
                        && "2026-2027 秋学期".equals(terms.get(0).getDisplayName()),
                "server term must map to CourseTermView");
        Message request = transport.lastRequest;
        require(request.getType() == MessageType.REQUEST, "course request must be REQUEST");
        require("course".equals(request.getModule()), "module must be course");
        require(CourseActions.LIST_TERMS.equals(request.getAction()), "listTerms action expected");
    }

    private static void loadCoursesSendsTermKeysAndMapsCourses() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("courses", List.of(
                new CourseDTO("101", "CS203", "数据结构", "必修", 4.0, 64, "树", "无"))));
        SocketCourseService service = new SocketCourseService(transport);

        List<CourseView> courses = service.loadCourses(TERM).join();
        require(courses.size() == 1 && courses.get(0).getCourseId() == 101L
                        && "CS203".equals(courses.get(0).getCourseCode()),
                "course DTO must map to CourseView");
        Message request = transport.lastRequest;
        require(Integer.valueOf(2026).equals(request.getData("academicYear")),
                "academicYear key required");
        require(Integer.valueOf(1).equals(request.getData("semester")), "semester key required");
    }

    private static void loadCourseOfferingsUsesDecimalCourseIdAndMapsStructuredOffering() {
        FakeTransport transport = new FakeTransport();
        long courseId = 9007199254740993L;
        transport.respond(message -> message.putData("offerings", List.of(
                new CourseOfferingDTO("9007199254740995", Long.toString(courseId),
                        List.of(new CourseTeacherDTO("T1", "张老师"),
                                new CourseTeacherDTO("T2", "周老师")),
                        List.of(new CourseMeetingDTO(2, 3, 4, 1, 16, "ALL", "教四-201",
                                "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z")),
                        30, 120, SelectionStateDTO.PLANNED, null, null, null))));
        SocketCourseService service = new SocketCourseService(transport);

        List<CourseOfferingView> offerings =
                service.loadCourseOfferings(TERM, courseId).join();
        Message request = transport.lastRequest;
        require(Long.toString(courseId).equals(request.getData("courseId")),
                "courseId must travel as decimal text");
        CourseOfferingView offering = offerings.get(0);
        require(offering.getOfferingId() == 9007199254740995L,
                "offering ID must be parsed exactly from decimal text");
        require(offering.getCourseId() == courseId, "course ID must be parsed exactly");
        require(offering.getTeachers().size() == 2
                        && "张老师".equals(offering.getTeachers().get(0).getDisplayName()),
                "teacher list must map structurally");
        require(offering.getMeetings().size() == 1
                        && offering.getMeetings().get(0).getStartPeriod() == 3
                        && offering.getMeetings().get(0).getEndPeriod() == 4
                        && "教四-201".equals(offering.getMeetings().get(0).getLocation())
                        && Instant.parse("2026-09-01T01:00:00Z")
                                .equals(offering.getMeetings().get(0).getEndsAt()),
                "meeting must map structurally");
    }

    private static void offeringMappingCoversAllSixSelectionStates() {
        List<CourseOfferingDTO> offeringDtos = new ArrayList<>();
        SelectionStateDTO[] states = SelectionStateDTO.values();
        for (int index = 0; index < states.length; index++) {
            offeringDtos.add(new CourseOfferingDTO(Integer.toString(1000 + index), "101",
                    List.of(), List.of(), index, 50, states[index], null, null, null));
        }
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("offerings", offeringDtos));
        SocketCourseService service = new SocketCourseService(transport);

        List<CourseOfferingView> offerings =
                service.loadCourseOfferings(TERM, 101L).join();
        require(offerings.size() == 6, "six selection states must map");
        for (int index = 0; index < states.length; index++) {
            SelectionStatus expected = SelectionStatus.valueOf(states[index].name());
            require(offerings.get(index).getSelectionStatus() == expected,
                    "state " + expected + " must map to the view status");
        }
    }

    private static void loadSelectionSnapshotMapsItemsAndTerm() {
        CourseDTO course = new CourseDTO("101", "CS203", "数据结构", "必修", 4.0, 64, "树", "无");
        CourseOfferingDTO offering = new CourseOfferingDTO("1001", "101",
                List.of(), List.of(), 30, 120, SelectionStateDTO.ENROLLED, null, null, null);
        CoursePlanSnapshotDTO snapshotDto = new CoursePlanSnapshotDTO(TERM_DTO,
                List.of(new CourseSelectionItemDTO(course, offering)),
                List.of(), List.of());
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("snapshot", snapshotDto));
        SocketCourseService service = new SocketCourseService(transport);

        model.course.CoursePlanSnapshotView snapshot =
                service.loadSelectionSnapshot(TERM).join();
        require(snapshot.getPlanItems().size() == 1, "plan item expected");
        CourseSelectionItemView item = snapshot.getPlanItems().get(0);
        require(item.getCourse().getCourseId() == 101L
                        && item.getOffering().getOfferingId() == 1001L,
                "selection item must map course and offering");
        require(snapshot.getTerm().equals(TERM), "snapshot term must map");
    }

    private static void mutationsPropagateOperationIdOfferingIdAndSnapshot() {
        String operationId = "0f8fad5b-d9cb-469f-a165-70867728950e";
        FakeTransport transport = new FakeTransport();
        CourseMutationResultDTO dto = new CourseMutationResultDTO(
                operationId, selectionItem(), SelectionStateDTO.ENROLLED, "ENROLLED",
                "选课成功", new CoursePlanSnapshotDTO(TERM_DTO, List.of(), List.of(),
                        List.of(selectionItem())));
        transport.respond(message -> message.putData("result", dto));
        SocketCourseService service = new SocketCourseService(transport);

        CourseMutationResultView result = service.selectOffering(
                TERM, 9007199254740993L, operationId).join();
        Message request = transport.lastRequest;
        require(CourseActions.SELECT_OFFERING.equals(request.getAction()),
                "selectOffering action expected");
        require(operationId.equals(request.getData("operationId")),
                "operationId must be transmitted unchanged");
        require("9007199254740993".equals(request.getData("offeringId")),
                "offeringId must be decimal text");
        require(Integer.valueOf(2026).equals(request.getData("academicYear"))
                        && Integer.valueOf(1).equals(request.getData("semester")),
                "mutation request must carry the server term");
        require(operationId.equals(result.getOperationId())
                        && result.getFinalState() == SelectionStatus.ENROLLED
                        && "选课成功".equals(result.getMessage())
                        && result.getSnapshot().getEnrolledItems().size() == 1,
                "mutation result must map id, state, message and snapshot");

        String decision = "ACCEPT";
        transport.respond(message -> message.putData("result", dto));
        service.resolveWaitlistOffer(TERM, 1001L, operationId, WaitlistDecision.ACCEPT).join();
        require(decision.equals(transport.lastRequest.getData("decision")),
                "waitlist decision must travel as its enum name");

        transport.respond(message -> message.putData("acked", true));
        service.ackCourseEvent("9007199254740997").join();
        Message ack = transport.lastRequest;
        require(CourseActions.ACK_COURSE_EVENT.equals(ack.getAction()), "ack action expected");
        require("9007199254740997".equals(ack.getData("eventId")),
                "eventId must be decimal text");
    }

    private static void loadScheduleAndNoticesSendTermAndWeek() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("schedule", List.of(
                new ScheduleEntryDTO("1001", "2026-2027 秋学期", "CS203", "数据结构",
                        "张老师", "教四-201", 2, 3, 2, 1, 16))));
        SocketCourseService service = new SocketCourseService(transport);
        List<ScheduleEntryView> schedule = service.loadSchedule(TERM, 3).join();
        Message request = transport.lastRequest;
        require(CourseActions.LOAD_SCHEDULE.equals(request.getAction()), "schedule action");
        require(Integer.valueOf(3).equals(request.getData("week")), "week key required");
        require(Integer.valueOf(2026).equals(request.getData("academicYear")), "year key");
        require(schedule.size() == 1 && schedule.get(0).getOfferingId() == 1001L
                        && schedule.get(0).getStartPeriod() == 3,
                "schedule entry must map");

        transport.respond(message -> message.putData("notices", List.of(
                new CourseNoticeDTO("1", "1001", "2026-2027 秋学期", 8, "停课",
                        "停课通知", "本周暂停"))));
        List<model.course.CourseNoticeView> notices = service.loadNotices(TERM, 8).join();
        require(CourseActions.LOAD_NOTICES.equals(transport.lastRequest.getAction()),
                "notices action");
        require(Integer.valueOf(8).equals(transport.lastRequest.getData("week")), "week key");
        require(notices.size() == 1 && "停课通知".equals(notices.get(0).getTitle())
                        && notices.get(0).getWeek() == 8,
                "notice must map");
    }

    private static void loadGradesMapsNullableComponents() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("grades", new GradeSummaryDTO(
                "2026-2027 秋学期", 3.85, 91.5, 90.8, 3.78, List.of(
                new GradeRecordDTO("2026-2027 秋学期", "CS101", "程序设计基础", 4.0,
                        94.0, 4.0, 95.0, 92.0, null, 95.0)))));
        SocketCourseService service = new SocketCourseService(transport);

        GradeSummaryView summary = service.loadGrades(TERM).join();
        require(CourseActions.LOAD_GRADES.equals(transport.lastRequest.getAction()),
                "grades action");
        require(summary.getTermGpa() == 3.85 && summary.getRecords().size() == 1,
                "grade summary must map");
        GradeRecordView record = summary.getRecords().get(0);
        require(record.getDailyScore() == 95.0 && record.getMidtermScore() == 92.0,
                "present components must map");
        require(record.getExperimentScore() == null,
                "nullable grade component must remain null");
        require(record.getFinalScore() == 95.0, "final score must map");
    }

    private static void loadTrainingPlanNeedsNoTerm() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("trainingPlan", List.of(
                new TrainingPlanGroupDTO("必修课程", 80.0, 9.0, List.of(
                        new TrainingPlanCourseDTO("CS101", "程序设计基础", 4.0, "已修"))))));
        SocketCourseService service = new SocketCourseService(transport);

        List<TrainingPlanGroupView> groups = service.loadTrainingPlan().join();
        require(CourseActions.LOAD_TRAINING_PLAN.equals(transport.lastRequest.getAction()),
                "training plan action");
        require(transport.lastRequest.getData("academicYear") == null,
                "training plan must not send a term");
        require(groups.size() == 1 && "必修课程".equals(groups.get(0).getName())
                        && groups.get(0).getCourses().size() == 1,
                "training plan must map");
    }

    private static void nonSuccessResponsesBecomeStableExceptions() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.BAD_REQUEST);
            message.setMessage("缺少参数");
        });
        SocketCourseService service = new SocketCourseService(transport);

        boolean thrown = false;
        try {
            service.loadTerms().join();
        } catch (CompletionException failure) {
            thrown = failure.getCause() instanceof SocketCourseService.CourseServiceException;
            if (thrown) {
                SocketCourseService.CourseServiceException error =
                        (SocketCourseService.CourseServiceException) failure.getCause();
                require(error.getCode() == MessageCode.BAD_REQUEST,
                        "non-success must expose the server message code");
                require("缺少参数".equals(error.getMessage()),
                        "non-success must expose the server message");
            }
        }
        require(thrown, "non-success must fail with a stable CourseServiceException");
    }

    private static void ackSendsDecimalEventId() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("acked", true));
        SocketCourseService service = new SocketCourseService(transport);
        service.ackCourseEvent("42").join();
        require(CourseActions.ACK_COURSE_EVENT.equals(transport.lastRequest.getAction()),
                "ack action expected");
        require("42".equals(transport.lastRequest.getData("eventId")), "eventId decimal text");
    }

    private static void ackFailureWhenServerDidNotAcknowledge() {
        FakeTransport rejected = new FakeTransport();
        rejected.respond(message -> message.putData("acked", false));
        boolean rejectedThrown = ackFails(new SocketCourseService(rejected));

        FakeTransport missing = new FakeTransport();
        missing.respond(message -> { });
        boolean missingThrown = ackFails(new SocketCourseService(missing));

        FakeTransport nonBoolean = new FakeTransport();
        nonBoolean.respond(message -> message.putData("acked", "true"));
        boolean nonBooleanThrown = ackFails(new SocketCourseService(nonBoolean));

        require(rejectedThrown, "acked=false must fail with a stable CourseServiceException");
        require(missingThrown, "a missing acked value must fail");
        require(nonBooleanThrown, "a non-boolean acked value must fail");
    }

    private static boolean ackFails(SocketCourseService service) {
        try {
            service.ackCourseEvent("42").join();
            return false;
        } catch (CompletionException failure) {
            return failure.getCause() instanceof SocketCourseService.CourseServiceException;
        }
    }

    private static void subscribeWiresPushModuleActionAndMapsEvent() {
        FakeTransport transport = new FakeTransport();
        SocketCourseService service = new SocketCourseService(transport);
        AtomicReference<CoursePushEventView> received = new AtomicReference<>();
        CourseSubscription subscription = service.subscribe(new CoursePushListener() {
            @Override
            public void onCourseEvent(CoursePushEventView event) {
                received.set(event);
            }
        });

        require("course".equals(transport.pushModule), "push module must be course");
        require(CourseActions.SELECTION_EVENT.equals(transport.pushAction),
                "push action must be selectionEvent");
        require(transport.pushListener != null, "push listener must be registered");

        CoursePushEventDTO dto = new CoursePushEventDTO("9", CoursePushEventTypeDTO.WAITLIST_OFFERED,
                TERM_DTO, "1001", "2026-09-10T01:00:00Z", "2026-09-10T01:05:00Z", "候补席位");
        Message push = new Message(MessageType.PUSH, "course", CourseActions.SELECTION_EVENT);
        push.putData("event", dto);
        transport.pushListener.accept(push);

        CoursePushEventView event = received.get();
        require(event != null, "push listener must receive the mapped event");
        require("9".equals(event.getEventId())
                        && event.getEventType() == CoursePushEventType.WAITLIST_OFFERED
                        && event.getOfferingId() == 1001L
                        && event.getTerm().equals(TERM),
                "push event must map id, type, offering and term");
        require(Instant.parse("2026-09-10T01:05:00Z").equals(event.getExpiresAt()),
                "push event must retain server expiresAt");

        subscription.close();
    }

    private static void subscribeWiresReconnectListener() {
        FakeTransport transport = new FakeTransport();
        SocketCourseService service = new SocketCourseService(transport);
        AtomicReference<Integer> reconnects = new AtomicReference<>(0);
        CourseSubscription subscription = service.subscribe(new CoursePushListener() {
            @Override
            public void onCourseEvent(CoursePushEventView event) {
            }

            @Override
            public void onReconnect() {
                reconnects.set(reconnects.get() + 1);
            }
        });

        require(transport.reconnectListener != null, "reconnect listener must be registered");
        transport.reconnectListener.run();
        require(reconnects.get() == 1, "reconnect must reach the listener");
        subscription.close();
    }

    private static CourseSelectionItemDTO selectionItem() {
        return new CourseSelectionItemDTO(
                new CourseDTO("101", "CS203", "数据结构", "必修", 4.0, 64, "树", "无"),
                new CourseOfferingDTO("1001", "101", List.of(), List.of(), 30, 120,
                        SelectionStateDTO.ENROLLED, null, null, null));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeTransport implements CourseTransport {
        private final Deque<Consumer<Message>> responseBuilders = new ArrayDeque<>();
        private Message lastRequest;
        private String pushModule;
        private String pushAction;
        private Consumer<Message> pushListener;
        private Runnable reconnectListener;

        private void respond(Consumer<Message> builder) {
            responseBuilders.addLast(builder);
        }

        @Override
        public CompletableFuture<Message> send(Message request) {
            lastRequest = request;
            Message response = new Message(MessageType.RESPONSE, "course", request.getAction());
            response.setUID(request.getUID());
            if (responseBuilders.isEmpty()) {
                response.setCode(MessageCode.SUCCESS);
            } else {
                responseBuilders.removeFirst().accept(response);
            }
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CourseSubscription subscribePush(String module, String action,
                Consumer<Message> listener) {
            this.pushModule = module;
            this.pushAction = action;
            this.pushListener = listener;
            return () -> { };
        }

        @Override
        public CourseSubscription subscribeReconnect(Runnable listener) {
            this.reconnectListener = listener;
            return () -> { };
        }
    }
}
