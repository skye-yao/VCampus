package service;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.ClientSession;

/**
 * 教师课程查询的 TCP 实现，module {@code courseTeacher}。
 *
 * <p>请求不携带任何身份字段：token 来自 {@link ClientSession}，教师 UID 由服务端从会话解析。
 * 列表响应是 {@link TeacherPageDTO} 对象，用 {@link TypeToken} 保留泛型实参后再解析，避免
 * Gson 因类型擦除把 items 退化成 Map。
 */
public final class SocketTeacherCourseService implements TeacherCourseService {
    private static final String MODULE = "courseTeacher";

    private static final Type OFFERING_PAGE_TYPE = TypeToken.getParameterized(
            TeacherPageDTO.class, TeacherOfferingDTO.class).getType();
    private static final Type ROSTER_PAGE_TYPE = TypeToken.getParameterized(
            TeacherPageDTO.class, TeacherRosterRowDTO.class).getType();

    private final TeacherCourseTransport transport;
    private final Gson gson = new Gson();

    public SocketTeacherCourseService() {
        this(new SocketTeacherCourseTransport());
    }

    SocketTeacherCourseService(TeacherCourseTransport transport) {
        this.transport = transport;
    }

    @Override
    public CompletableFuture<List<CourseTermDTO>> listTerms() {
        Message request = request(TeacherCourseActions.LIST_TERMS);
        return map(request, response -> List.copyOf(list(response, "terms", CourseTermDTO.class)));
    }

    @Override
    public CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
            int academicYear, int semester, String query, int page, int size) {
        Message request = request(TeacherCourseActions.LIST_OFFERINGS);
        request.putData("academicYear", academicYear);
        request.putData("semester", semester);
        if (query != null) request.putData("query", query);
        putPaging(request, page, size);
        return map(request, response -> read(response, "offerings", OFFERING_PAGE_TYPE));
    }

    @Override
    public CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId) {
        Message request = request(TeacherCourseActions.GET_OFFERING);
        request.putData("offeringId", offeringId);
        return map(request, response -> read(response, "offering", TeacherOfferingDetailDTO.class));
    }

    @Override
    public CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
            String offeringId, String query, Integer enrollmentStatus, int page, int size) {
        Message request = request(TeacherCourseActions.LIST_OFFERING_STUDENTS);
        request.putData("offeringId", offeringId);
        if (query != null) request.putData("query", query);
        if (enrollmentStatus != null) request.putData("enrollmentStatus", enrollmentStatus);
        putPaging(request, page, size);
        return map(request, response -> read(response, "students", ROSTER_PAGE_TYPE));
    }

    @Override
    public CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(String offeringId) {
        Message request = request(TeacherCourseActions.LIST_OFFERING_SCHEDULES);
        request.putData("offeringId", offeringId);
        return map(request, response -> List.copyOf(
                list(response, "schedules", ScheduleArrangementDTO.class)));
    }

    private static void putPaging(Message request, int page, int size) {
        request.putData("page", page);
        request.putData("size", size);
    }

    private static Message request(String action) {
        Message message = new Message(MessageType.REQUEST, MODULE, action);
        message.setCode(MessageCode.SUCCESS);
        message.setToken(ClientSession.getInstance().getToken());
        return message;
    }

    private <T> CompletableFuture<T> map(Message request, Function<Message, T> mapper) {
        return transport.send(request).thenApply(response -> {
            requireSuccess(response);
            return mapper.apply(response);
        });
    }

    private void requireSuccess(Message response) {
        if (response == null) {
            throw new TeacherCourseServiceException(MessageCode.ERROR, "教师课程服务无响应");
        }
        if (response.getCode() == MessageCode.SUCCESS) return;
        String message = response.getMessage() == null
                ? response.getCode().getMessage() : response.getMessage();
        throw new TeacherCourseServiceException(response.getCode(), message);
    }

    private <T> List<T> list(Message response, String key, Class<T> type) {
        Type listType = TypeToken.getParameterized(List.class, type).getType();
        return read(response, key, listType);
    }

    private <T> T read(Message response, String key, Type type) {
        Object value = response.getData() == null ? null : response.getData().get(key);
        if (value == null) {
            throw new TeacherCourseServiceException(MessageCode.ERROR, "缺少响应字段: " + key);
        }
        return gson.fromJson(gson.toJson(value), type);
    }

    /** 教师课程请求的稳定失败契约：保留服务端 code 与 message，供界面区分无权限与参数错误。 */
    public static final class TeacherCourseServiceException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final MessageCode code;

        public TeacherCourseServiceException(MessageCode code, String message) {
            super(message);
            this.code = code;
        }

        public MessageCode getCode() {
            return code;
        }
    }
}
