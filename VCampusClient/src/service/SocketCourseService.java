package service;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import dto.course.CourseActions;
import dto.course.CourseDTO;
import dto.course.CourseMeetingDTO;
import dto.course.CourseMutationResultDTO;
import dto.course.CourseNoticeDTO;
import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CoursePushEventDTO;
import dto.course.CourseScheduleWeekDTO;
import dto.course.CourseSelectionItemDTO;
import dto.course.CourseTeacherDTO;
import dto.course.CourseTermDTO;
import dto.course.GradeRecordDTO;
import dto.course.GradeSummaryDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.ScheduleEntryDTO;
import dto.course.TrainingPlanCourseDTO;
import dto.course.TrainingPlanGroupDTO;
import model.course.CourseMeetingView;
import model.course.CourseMutationResultView;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CoursePushEventType;
import model.course.CoursePushEventView;
import model.course.CourseSelectionItemView;
import model.course.CourseTeacherView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeRecordView;
import model.course.GradeSummaryView;
import model.course.ScheduleDisplayKind;
import model.course.ScheduleEntryView;
import model.course.ScheduleWeekView;
import model.course.SelectionStatus;
import model.course.TrainingPlanCourseView;
import model.course.TrainingPlanGroupView;
import model.course.WaitlistDecision;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;

/** 通过 TCP 协议传输实现的学生课程与选课异步服务。 */
public final class SocketCourseService implements CourseService {
    private static final String MODULE = "course";

    private final CourseTransport transport;
    private final Gson gson = new Gson();

    public SocketCourseService() {
        this(new SocketCourseTransport());
    }

    SocketCourseService(CourseTransport transport) {
        this.transport = transport;
    }

    @Override
    public CompletableFuture<List<CourseTermView>> loadTerms() {
        Message request = request(CourseActions.LIST_TERMS);
        return map(request, response -> {
            List<CourseTermView> terms = new ArrayList<>();
            for (CourseTermDTO dto : list(response, "terms", CourseTermDTO.class)) {
                terms.add(term(dto));
            }
            return List.copyOf(terms);
        });
    }

    @Override
    public CompletableFuture<List<CourseView>> loadCourses(CourseTermView term) {
        Message request = request(CourseActions.LIST_COURSES);
        putTerm(request, term);
        return map(request, response -> {
            List<CourseView> courses = new ArrayList<>();
            for (CourseDTO dto : list(response, "courses", CourseDTO.class)) {
                courses.add(course(dto));
            }
            return List.copyOf(courses);
        });
    }

    @Override
    public CompletableFuture<List<CourseOfferingView>> loadCourseOfferings(
            CourseTermView term, long courseId) {
        Message request = request(CourseActions.LIST_COURSE_OFFERINGS);
        putTerm(request, term);
        request.putData("courseId", Long.toString(courseId));
        return map(request, response -> offerings(
                list(response, "offerings", CourseOfferingDTO.class)));
    }

    @Override
    public CompletableFuture<CoursePlanSnapshotView> loadSelectionSnapshot(
            CourseTermView term) {
        Message request = request(CourseActions.LOAD_SELECTION_SNAPSHOT);
        putTerm(request, term);
        return map(request, response -> snapshot(
                single(response, "snapshot", CoursePlanSnapshotDTO.class)));
    }

    @Override
    public CompletableFuture<CourseMutationResultView> addToPlan(
            CourseTermView term, long offeringId, String operationId) {
        return mutation(CourseActions.ADD_TO_PLAN, term, offeringId, operationId);
    }

    @Override
    public CompletableFuture<CourseMutationResultView> removeFromPlan(
            CourseTermView term, long offeringId, String operationId) {
        return mutation(CourseActions.REMOVE_FROM_PLAN, term, offeringId, operationId);
    }

    @Override
    public CompletableFuture<CourseMutationResultView> selectOffering(
            CourseTermView term, long offeringId, String operationId) {
        return mutation(CourseActions.SELECT_OFFERING, term, offeringId, operationId);
    }

    @Override
    public CompletableFuture<CourseMutationResultView> joinWaitlist(
            CourseTermView term, long offeringId, String operationId) {
        return mutation(CourseActions.JOIN_WAITLIST, term, offeringId, operationId);
    }

    @Override
    public CompletableFuture<CourseMutationResultView> cancelWaitlist(
            CourseTermView term, long offeringId, String operationId) {
        return mutation(CourseActions.CANCEL_WAITLIST, term, offeringId, operationId);
    }

    @Override
    public CompletableFuture<CourseMutationResultView> resolveWaitlistOffer(
            CourseTermView term, long offeringId, String operationId,
            WaitlistDecision decision) {
        Message request = mutationRequest(
                CourseActions.RESOLVE_WAITLIST_OFFER, term, offeringId, operationId);
        request.putData("decision", decision.name());
        return mutationResult(request);
    }

    @Override
    public CompletableFuture<CourseMutationResultView> dropOffering(
            CourseTermView term, long offeringId, String operationId) {
        return mutation(CourseActions.DROP_OFFERING, term, offeringId, operationId);
    }

    @Override
    public CompletableFuture<Void> ackCourseEvent(String eventId) {
        Message request = request(CourseActions.ACK_COURSE_EVENT);
        request.putData("eventId", eventId);
        return map(request, response -> {
            Object acked = response.getData() == null ? null : response.getData().get("acked");
            // 服务端以 SUCCESS + data.acked 表示是否真正确认；只有 true 才算 ACK 成功。
            if (!Boolean.TRUE.equals(acked)) {
                throw new CourseServiceException(MessageCode.CONFLICT, "课程事件未被确认");
            }
            return null;
        });
    }

    @Override
    public CourseSubscription subscribe(CoursePushListener listener) {
        CourseSubscription push = transport.subscribePush(
                MODULE, CourseActions.SELECTION_EVENT,
                message -> listener.onCourseEvent(pushEvent(message)));
        CourseSubscription reconnect = transport.subscribeReconnect(listener::onReconnect);
        return () -> {
            push.close();
            reconnect.close();
        };
    }

    /**
     * {@code schedule} 是一个对象（{@code CourseScheduleWeekDTO}），不是裸数组：日期与节次字典随课次
     * 一起回来，网格的行列由服务端教学日历决定。读法与教师端读 {@code TeacherScheduleWeekDTO} 相同，
     * 用的是本类既有的 {@code single(response, key, type)}。
     *
     * <p>{@code week} 为 null 时不发 {@code week} 键：服务端按教学日历与系统时钟取当前周，与教师端
     * {@code SocketTeacherCourseService.loadTeachingSchedule} 同一约定。
     */
    @Override
    public CompletableFuture<ScheduleWeekView> loadSchedule(
            CourseTermView term, Integer week) {
        Message request = request(CourseActions.LOAD_SCHEDULE);
        putTerm(request, term);
        if (week != null) request.putData("week", week);
        return map(request, response -> {
            CourseScheduleWeekDTO dto =
                    single(response, "schedule", CourseScheduleWeekDTO.class);
            List<ScheduleEntryView> entries = new ArrayList<>();
            for (ScheduleEntryDTO entry : dto.getEntries()) {
                entries.add(scheduleEntry(entry));
            }
            return new ScheduleWeekView(dto.getWeek(), dto.getMinWeek(), dto.getMaxWeek(),
                    dto.getCurrentWeek(), dto.getDates(), dto.getPeriods(),
                    List.copyOf(entries));
        });
    }

    @Override
    public CompletableFuture<List<CourseNoticeView>> loadNotices(
            CourseTermView term, int week) {
        Message request = request(CourseActions.LOAD_NOTICES);
        putTerm(request, term);
        request.putData("week", week);
        return map(request, response -> {
            List<CourseNoticeView> notices = new ArrayList<>();
            for (CourseNoticeDTO dto : list(response, "notices", CourseNoticeDTO.class)) {
                notices.add(new CourseNoticeView(
                        dto.getTerm(), dto.getWeek(), dto.getTitle(), dto.getContent()));
            }
            return List.copyOf(notices);
        });
    }

    @Override
    public CompletableFuture<GradeSummaryView> loadGrades(CourseTermView term) {
        Message request = request(CourseActions.LOAD_GRADES);
        putTerm(request, term);
        return map(request, response -> gradeSummary(
                single(response, "grades", GradeSummaryDTO.class)));
    }

    @Override
    public CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan() {
        Message request = request(CourseActions.LOAD_TRAINING_PLAN);
        return map(request, response -> {
            List<TrainingPlanGroupView> groups = new ArrayList<>();
            for (TrainingPlanGroupDTO dto :
                    list(response, "trainingPlan", TrainingPlanGroupDTO.class)) {
                groups.add(trainingPlanGroup(dto));
            }
            return List.copyOf(groups);
        });
    }

    private CompletableFuture<CourseMutationResultView> mutation(String action,
            CourseTermView term, long offeringId, String operationId) {
        return mutationResult(mutationRequest(action, term, offeringId, operationId));
    }

    private Message mutationRequest(String action, CourseTermView term,
            long offeringId, String operationId) {
        Message request = request(action);
        putTerm(request, term);
        request.putData("offeringId", Long.toString(offeringId));
        request.putData("operationId", operationId);
        return request;
    }

    private CompletableFuture<CourseMutationResultView> mutationResult(Message request) {
        return map(request, response -> mutationResult(
                single(response, "result", CourseMutationResultDTO.class)));
    }

    private Message request(String action) {
        Message message = new Message(MessageType.REQUEST, MODULE, action);
        message.setCode(MessageCode.SUCCESS);
        return message;
    }

    private static void putTerm(Message message, CourseTermView term) {
        message.putData("academicYear", term.getAcademicYear());
        message.putData("semester", term.getSemester());
    }

    private <T> CompletableFuture<T> map(Message request, Function<Message, T> mapper) {
        return transport.send(request).thenApply(response -> {
            requireSuccess(response);
            return mapper.apply(response);
        });
    }

    private static void requireSuccess(Message response) {
        if (response == null) {
            throw new CourseServiceException(MessageCode.ERROR, "课程服务无响应");
        }
        if (response.getCode() != MessageCode.SUCCESS) {
            String message = response.getMessage() == null
                    ? response.getCode().getMessage() : response.getMessage();
            throw new CourseServiceException(response.getCode(), message);
        }
    }

    private <T> T single(Message response, String key, Class<T> type) {
        return read(response, key, type);
    }

    private <T> List<T> list(Message response, String key, Class<T> type) {
        Type listType = TypeToken.getParameterized(List.class, type).getType();
        return read(response, key, listType);
    }

    private <T> T read(Message response, String key, Type type) {
        Object value = response.getData() == null ? null : response.getData().get(key);
        if (value == null) {
            throw new CourseServiceException(MessageCode.ERROR, "缺少响应字段: " + key);
        }
        return gson.fromJson(gson.toJson(value), type);
    }

    private static CourseTermView term(CourseTermDTO dto) {
        return new CourseTermView(dto.getAcademicYear(), dto.getSemester(),
                dto.getDisplayName());
    }

    private static CourseView course(CourseDTO dto) {
        return new CourseView(Long.parseLong(dto.getCourseId()), dto.getCourseCode(),
                dto.getCourseName(), dto.getCourseType(), dto.getCredit(),
                dto.getCreditHours(), dto.getDescription(), dto.getPrerequisites());
    }

    private static CourseTeacherView teacher(CourseTeacherDTO dto) {
        return new CourseTeacherView(dto.getUid(), dto.getDisplayName());
    }

    private static CourseMeetingView meeting(CourseMeetingDTO dto) {
        return new CourseMeetingView(dto.getDayOfWeek(), dto.getStartPeriod(),
                dto.getEndPeriod(), dto.getStartWeek(), dto.getEndWeek(),
                dto.getWeekPattern(), dto.getLocation(), dto.getStartsAtUtc(),
                dto.getEndsAtUtc());
    }

    private static CourseOfferingView offering(CourseOfferingDTO dto) {
        List<CourseTeacherView> teachers = new ArrayList<>();
        for (CourseTeacherDTO teacher : dto.getTeachers()) {
            teachers.add(teacher(teacher));
        }
        List<CourseMeetingView> meetings = new ArrayList<>();
        for (CourseMeetingDTO meeting : dto.getMeetings()) {
            meetings.add(meeting(meeting));
        }
        return new CourseOfferingView(Long.parseLong(dto.getOfferingId()),
                dto.getOfferingCode(), Long.parseLong(dto.getCourseId()), teachers, meetings,
                dto.getEnrolledCount(), dto.getCapacity(),
                SelectionStatus.valueOf(dto.getSelectionState().name()),
                dto.getFailureReason(), dto.getOfferedAt(), dto.getExpiresAt());
    }

    private static List<CourseOfferingView> offerings(List<CourseOfferingDTO> dtos) {
        List<CourseOfferingView> offerings = new ArrayList<>();
        for (CourseOfferingDTO dto : dtos) {
            offerings.add(offering(dto));
        }
        return List.copyOf(offerings);
    }

    private static CourseSelectionItemView selectionItem(CourseSelectionItemDTO dto) {
        return new CourseSelectionItemView(course(dto.getCourse()), offering(dto.getOffering()));
    }

    private static CoursePlanSnapshotView snapshot(CoursePlanSnapshotDTO dto) {
        return new CoursePlanSnapshotView(term(dto.getTerm()),
                selectionItems(dto.getPlanItems()),
                selectionItems(dto.getWaitlistItems()),
                selectionItems(dto.getEnrolledItems()));
    }

    private static List<CourseSelectionItemView> selectionItems(
            List<CourseSelectionItemDTO> dtos) {
        List<CourseSelectionItemView> items = new ArrayList<>();
        for (CourseSelectionItemDTO dto : dtos) {
            items.add(selectionItem(dto));
        }
        return List.copyOf(items);
    }

    private static CourseMutationResultView mutationResult(CourseMutationResultDTO dto) {
        return new CourseMutationResultView(dto.getOperationId(),
                selectionItem(dto.getItem()),
                SelectionStatus.valueOf(dto.getFinalState().name()),
                dto.getOutcomeCode(), dto.getMessage(), snapshot(dto.getSnapshot()));
    }

    private static ScheduleEntryView scheduleEntry(ScheduleEntryDTO dto) {
        return new ScheduleEntryView(Long.parseLong(dto.getOfferingId()), dto.getTerm(),
                dto.getCourseCode(), dto.getCourseName(), dto.getTeacher(), dto.getLocation(),
                dto.getDayOfWeek(), dto.getStartPeriod(), dto.getPeriodCount(),
                dto.getStartWeek(), dto.getEndWeek(), displayKind(dto), dto.getAdjustmentId(),
                dto.getOriginalScheduleText(), dto.getAdjustedScheduleText(),
                dto.getAdjustmentReason());
    }

    /**
     * The wire value is authoritative. An absent or unrecognized kind must fail loudly instead of
     * being rendered as a plain lesson, which would silently hide a temporary adjustment.
     */
    private static ScheduleDisplayKind displayKind(ScheduleEntryDTO dto) {
        ScheduleDisplayKindDTO kind = dto.getDisplayKind();
        if (kind == null) {
            throw new CourseServiceException(MessageCode.ERROR, "缺少响应字段: displayKind");
        }
        return switch (kind) {
            case NORMAL -> ScheduleDisplayKind.NORMAL;
            case ADJUSTED_ORIGINAL -> ScheduleDisplayKind.ADJUSTED_ORIGINAL;
            case ADJUSTED_TARGET -> ScheduleDisplayKind.ADJUSTED_TARGET;
        };
    }

    private static GradeSummaryView gradeSummary(GradeSummaryDTO dto) {
        List<GradeRecordView> records = new ArrayList<>();
        for (GradeRecordDTO record : dto.getRecords()) {
            records.add(new GradeRecordView(record.getTerm(), record.getCourseCode(),
                    record.getCourseName(), record.getCredit(), record.getScore(),
                    record.getGradePoint(), record.getDailyScore(), record.getMidtermScore(),
                    record.getExperimentScore(), record.getFinalScore()));
        }
        return new GradeSummaryView(dto.getTerm(), dto.getTermGpa(), dto.getTermAverage(),
                dto.getCumulativeAverage(), dto.getCumulativeGpa(), records);
    }

    private static TrainingPlanGroupView trainingPlanGroup(TrainingPlanGroupDTO dto) {
        List<TrainingPlanCourseView> courses = new ArrayList<>();
        for (TrainingPlanCourseDTO course : dto.getCourses()) {
            courses.add(new TrainingPlanCourseView(course.getCourseCode(),
                    course.getCourseName(), course.getCredit(), course.getCompletionStatus()));
        }
        return new TrainingPlanGroupView(dto.getName(), dto.getRequiredCredits(),
                dto.getEarnedCredits(), courses);
    }

    private CoursePushEventView pushEvent(Message message) {
        CoursePushEventDTO dto = single(message, "event", CoursePushEventDTO.class);
        return new CoursePushEventView(dto.getEventId(),
                CoursePushEventType.valueOf(dto.getEventType().name()),
                term(dto.getTerm()), Long.parseLong(dto.getOfferingId()),
                dto.getOccurredAt(), dto.getExpiresAt(), dto.getMessage());
    }

    public static final class CourseServiceException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final MessageCode code;

        public CourseServiceException(MessageCode code, String message) {
            super(message);
            this.code = code;
        }

        public MessageCode getCode() {
            return code;
        }
    }
}
