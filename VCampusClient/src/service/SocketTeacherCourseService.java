package service;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.CourseTermDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.teacher.ConfirmGradeImportRequestDTO;
import dto.course.teacher.GradeImportPreviewDTO;
import dto.course.teacher.PreviewGradeImportRequestDTO;
import dto.course.teacher.ReviseGradeImportRequestDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherFileTicketDTO;
import dto.course.teacher.TeacherFileUploadRequestDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
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
    private static final Type APPLICATIONS_PAGE_TYPE = TypeToken.getParameterized(
            TeacherPageDTO.class, AdjustmentRequestSummaryDTO.class).getType();
    private static final Type WRITE_RESULT_TYPE = TypeToken.getParameterized(
            TeacherOperationResultDTO.class, AdjustmentRequestDetailDTO.class).getType();
    private static final Type GRADE_OFFERINGS_PAGE_TYPE = TypeToken.getParameterized(
            TeacherPageDTO.class, TeacherGradeOfferingDTO.class).getType();
    private static final Type GRADE_WRITE_RESULT_TYPE = TypeToken.getParameterized(
            TeacherOperationResultDTO.class, TeacherGradeBookDTO.class).getType();

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

    @Override
    public CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
            int academicYear, int semester, Integer week) {
        Message request = request(TeacherCourseActions.LOAD_TEACHING_SCHEDULE);
        request.putData("academicYear", academicYear);
        request.putData("semester", semester);
        if (week != null) request.putData("week", week);
        return map(request, this::readSchedule);
    }

    // ------------------------------------------------------------------ 调课申请

    @Override
    public CompletableFuture<TeacherAdjustmentOptionsDTO> getAdjustmentOptions(
            String offeringId, String originalOccurrenceId) {
        Message request = request(TeacherCourseActions.GET_ADJUSTMENT_OPTIONS);
        request.putData("offeringId", offeringId);
        request.putData("originalOccurrenceId", originalOccurrenceId);
        return map(request, response -> read(response, "options",
                TeacherAdjustmentOptionsDTO.class));
    }

    @Override
    public CompletableFuture<TeacherAdjustmentPreviewDTO> previewAdjustment(
            TeacherAdjustmentWriteDTO write) {
        Message request = request(TeacherCourseActions.PREVIEW_ADJUSTMENT);
        request.putData("request", write);
        // 预览结果（含 canSubmit）整体位于 conflicts 键，与规格的响应命名一致。
        return map(request, response -> read(response, "conflicts",
                TeacherAdjustmentPreviewDTO.class));
    }

    @Override
    public CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>
            submitAdjustment(TeacherAdjustmentWriteDTO write) {
        Message request = request(TeacherCourseActions.SUBMIT_ADJUSTMENT);
        request.putData("request", write);
        return map(request, response -> read(response, "result", WRITE_RESULT_TYPE));
    }

    @Override
    public CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>
            withdrawAdjustment(WithdrawTeacherAdjustmentRequestDTO withdrawal) {
        Message request = request(TeacherCourseActions.WITHDRAW_ADJUSTMENT);
        request.putData("request", withdrawal);
        return map(request, response -> read(response, "result", WRITE_RESULT_TYPE));
    }

    @Override
    public CompletableFuture<AdjustmentRequestDetailDTO> getAdjustmentRequest(String requestId) {
        Message request = request(TeacherCourseActions.GET_ADJUSTMENT_REQUEST);
        request.putData("requestId", requestId);
        return map(request, response -> read(response, "adjustmentRequest",
                AdjustmentRequestDetailDTO.class));
    }

    @Override
    public CompletableFuture<TeacherPageDTO<AdjustmentRequestSummaryDTO>> listMyAdjustmentRequests(
            AdjustmentRequestStatusDTO status, int page, int size) {
        Message request = request(TeacherCourseActions.LIST_MY_ADJUSTMENT_REQUESTS);
        if (status != null) request.putData("status", status.name());
        putPaging(request, page, size);
        return map(request, response -> read(response, "applications", APPLICATIONS_PAGE_TYPE));
    }

    // ------------------------------------------------------------------ 成绩工作副本

    @Override
    public CompletableFuture<TeacherPageDTO<TeacherGradeOfferingDTO>> listGradeOfferings(
            int academicYear, int semester, int page, int size) {
        Message request = request(TeacherCourseActions.LIST_GRADE_OFFERINGS);
        request.putData("academicYear", academicYear);
        request.putData("semester", semester);
        putPaging(request, page, size);
        return map(request, response -> read(response, "offerings", GRADE_OFFERINGS_PAGE_TYPE));
    }

    @Override
    public CompletableFuture<TeacherGradeBookDTO> getGradeBook(String offeringId) {
        Message request = request(TeacherCourseActions.GET_GRADE_BOOK);
        request.putData("offeringId", offeringId);
        return map(request, response -> read(response, "gradeBook", TeacherGradeBookDTO.class));
    }

    @Override
    public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> saveGradeDraft(
            WriteGradeBookRequestDTO write) {
        return write(TeacherCourseActions.SAVE_GRADE_DRAFT, write);
    }

    @Override
    public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> submitGradeBook(
            WriteGradeBookRequestDTO write) {
        return write(TeacherCourseActions.SUBMIT_GRADE_BOOK, write);
    }

    /**
     * 两个成绩写动作共用一条通路：请求体是 {@link WriteGradeBookRequestDTO}（operationId + 完整
     * 编辑内容），响应是 {@code result} 键上的操作结果信封。动作名与 Handler 共用同一常量，
     * 服务端的幂等摘要与操作日志因此建立在同一个字符串上。
     */
    private CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> write(String action,
            WriteGradeBookRequestDTO write) {
        Message request = request(action);
        request.putData("request", write);
        return map(request, response -> read(response, "result", GRADE_WRITE_RESULT_TYPE));
    }

    // ------------------------------------------------------------------ Excel 模板、导入与名单导出

    @Override
    public CompletableFuture<TeacherFileTicketDTO> requestGradeTemplate(String offeringId) {
        Message request = request(TeacherCourseActions.REQUEST_GRADE_TEMPLATE);
        request.putData("offeringId", offeringId);
        return map(request, response -> read(response, "ticket", TeacherFileTicketDTO.class));
    }

    @Override
    public CompletableFuture<TeacherFileTicketDTO> requestRosterExport(
            String offeringId, String query, Integer enrollmentStatus) {
        Message request = request(TeacherCourseActions.REQUEST_ROSTER_EXPORT);
        request.putData("offeringId", offeringId);
        if (query != null) request.putData("query", query);
        if (enrollmentStatus != null) request.putData("enrollmentStatus", enrollmentStatus);
        return map(request, response -> read(response, "ticket", TeacherFileTicketDTO.class));
    }

    @Override
    public CompletableFuture<TeacherFileTicketDTO> beginGradeUpload(
            TeacherFileUploadRequestDTO upload) {
        Message request = request(TeacherCourseActions.BEGIN_GRADE_UPLOAD);
        request.putData("request", upload);
        return map(request, response -> read(response, "ticket", TeacherFileTicketDTO.class));
    }

    @Override
    public CompletableFuture<GradeImportPreviewDTO> previewGradeImport(
            PreviewGradeImportRequestDTO preview) {
        Message request = request(TeacherCourseActions.PREVIEW_GRADE_IMPORT);
        request.putData("request", preview);
        return map(request, response -> read(response, "preview", GradeImportPreviewDTO.class));
    }

    @Override
    public CompletableFuture<GradeImportPreviewDTO> reviseGradeImport(
            ReviseGradeImportRequestDTO revise) {
        Message request = request(TeacherCourseActions.REVISE_GRADE_IMPORT);
        request.putData("request", revise);
        return map(request, response -> read(response, "preview", GradeImportPreviewDTO.class));
    }

    @Override
    public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> confirmGradeImport(
            ConfirmGradeImportRequestDTO confirm) {
        Message request = request(TeacherCourseActions.CONFIRM_GRADE_IMPORT);
        request.putData("request", confirm);
        return map(request, response -> read(response, "result", GRADE_WRITE_RESULT_TYPE));
    }

    /**
     * 取消导入：请求体里只有一个令牌（服务端的 {@code importToken} 标量读取器读的就是这个键），
     * 响应没有载荷，成功即完成。
     */
    @Override
    public CompletableFuture<Void> cancelGradeImport(String importToken) {
        Message request = request(TeacherCourseActions.CANCEL_GRADE_IMPORT);
        request.putData("request", Map.of("importToken", importToken == null ? "" : importToken));
        return this.<Void>map(request, response -> null);
    }

    /**
     * 课表映射在反序列化前显式拒绝未知 {@code displayKind}。
     *
     * <p>Gson 把无法识别的枚举常量静默解析成 null，而不是抛异常；若直接映射，未知类型会被
     * 当成缺失值并可能在界面回退成 NORMAL。这里先检查原始 JSON，未知或缺失一律报清晰错误。
     */
    private TeacherScheduleWeekDTO readSchedule(Message response) {
        Object value = response.getData() == null ? null : response.getData().get("schedule");
        if (value == null) {
            throw new TeacherCourseServiceException(MessageCode.ERROR, "缺少响应字段: schedule");
        }
        JsonElement tree = gson.toJsonTree(value);
        requireKnownDisplayKinds(tree);
        return gson.fromJson(tree, TeacherScheduleWeekDTO.class);
    }

    private static void requireKnownDisplayKinds(JsonElement schedule) {
        if (schedule == null || !schedule.isJsonObject()) return;
        JsonElement entries = schedule.getAsJsonObject().get("entries");
        if (entries == null || !entries.isJsonArray()) return;
        for (JsonElement entry : entries.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonElement kind = entry.getAsJsonObject().get("displayKind");
            if (kind == null || kind.isJsonNull() || !isKnownDisplayKind(kind)) {
                throw new TeacherCourseServiceException(MessageCode.ERROR,
                        "未知的课表展示类型: " + rawKind(kind));
            }
        }
    }

    private static boolean isKnownDisplayKind(JsonElement kind) {
        if (!kind.isJsonPrimitive() || !kind.getAsJsonPrimitive().isString()) return false;
        String name = kind.getAsString();
        for (ScheduleDisplayKindDTO known : ScheduleDisplayKindDTO.values()) {
            if (known.name().equals(name)) return true;
        }
        return false;
    }

    private static String rawKind(JsonElement kind) {
        if (kind == null || kind.isJsonNull()) return "空值";
        return kind.isJsonPrimitive() ? kind.getAsString() : kind.toString();
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

    /**
     * 失败映射保留服务端 code/message；CONFLICT 额外带出最新可见实体（可能没有），让界面可以刷新
     * 而不是只拿到一句文本，与管理员审批客户端的形状一致。
     *
     * <p>两类冲突各用各的响应键：调课冲突是 {@code conflicts}/{@code latest}（类型化冲突 +
     * 最新申请详情），成绩冲突是 {@code gradeBook}（最新成绩表）。键不同是必须的——同一个
     * {@code latest} 键里放进成绩表会让调课界面把成绩表当成申请详情解析。
     */
    private void requireSuccess(Message response) {
        if (response == null) {
            throw new TeacherCourseServiceException(MessageCode.ERROR, "教师课程服务无响应");
        }
        if (response.getCode() == MessageCode.SUCCESS) return;
        String message = response.getMessage() == null
                ? response.getCode().getMessage() : response.getMessage();
        Map<String, Object> data = response.getData();
        if (response.getCode() != MessageCode.CONFLICT || data == null) {
            throw new TeacherCourseServiceException(response.getCode(), message);
        }
        List<ScheduleConflictDTO> conflicts = data.get("conflicts") == null ? List.of()
                : list(response, "conflicts", ScheduleConflictDTO.class);
        AdjustmentRequestDetailDTO latest = data.get("latest") == null ? null
                : read(response, "latest", AdjustmentRequestDetailDTO.class);
        TeacherGradeBookDTO gradeBook = data.get("gradeBook") == null ? null
                : read(response, "gradeBook", TeacherGradeBookDTO.class);
        throw new TeacherCourseServiceException(response.getCode(), message, conflicts, latest,
                gradeBook);
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

    /**
     * 教师课程请求的稳定失败契约：保留服务端 code 与 message，供界面区分无权限与参数错误。
     * CONFLICT 时额外携带最新的可见实体——调课冲突带回类型化冲突与申请详情，成绩冲突带回最新
     * 成绩表（{@link #getLatestGradeBook()}），两者互不冒充对方的类型。
     */
    public static final class TeacherCourseServiceException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final MessageCode code;
        private final List<ScheduleConflictDTO> conflicts;
        private final AdjustmentRequestDetailDTO latest;
        private final TeacherGradeBookDTO latestGradeBook;

        public TeacherCourseServiceException(MessageCode code, String message) {
            this(code, message, List.of(), null, null);
        }

        public TeacherCourseServiceException(MessageCode code, String message,
                List<ScheduleConflictDTO> conflicts, AdjustmentRequestDetailDTO latest) {
            this(code, message, conflicts, latest, null);
        }

        public TeacherCourseServiceException(MessageCode code, String message,
                List<ScheduleConflictDTO> conflicts, AdjustmentRequestDetailDTO latest,
                TeacherGradeBookDTO latestGradeBook) {
            super(message);
            this.code = code;
            this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            this.latest = latest;
            this.latestGradeBook = latestGradeBook;
        }

        public MessageCode getCode() {
            return code;
        }

        public List<ScheduleConflictDTO> getConflicts() {
            return conflicts;
        }

        public AdjustmentRequestDetailDTO getLatest() {
            return latest;
        }

        /** 成绩写入冲突（版本过期/名单变化）时服务端带回的最新成绩表；没有时为 null。 */
        public TeacherGradeBookDTO getLatestGradeBook() {
            return latestGradeBook;
        }
    }
}
