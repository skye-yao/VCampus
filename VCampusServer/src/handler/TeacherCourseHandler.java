package handler;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.teacher.ConfirmGradeImportRequestDTO;
import dto.course.teacher.PreviewGradeImportRequestDTO;
import dto.course.teacher.ReviseGradeImportRequestDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherFileTicketDTO;
import dto.course.teacher.TeacherFileUploadRequestDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.TeacherAccessPolicy;
import service.TeacherAdjustmentApplicationService;
import service.TeacherCourseQueryService;
import service.TeacherFileTicketService;
import service.TeacherGradeBookService;
import service.TeacherGradeImportService;
import service.TeacherSpreadsheetService;
import session.SessionManager;
import session.UserSession;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 教师端课程查询与调课（module {@code courseTeacher}）的 TCP 入口。
 *
 * <p>处理顺序固定为：认证 → 角色 → 参数 → 调用服务。教师 UID 只取自服务端校验过的
 * Session（{@link UserSession#getUsername()}），请求体里的 {@code uid}/{@code teacherId}/
 * {@code sender} 不参与任何判定，防止客户端用请求体替换真实身份。
 *
 * <p>只读查询的响应键为 terms、offerings、offering、students、schedules、schedule；调课动作的
 * 响应键为 options、conflicts（预览）、adjustmentRequest、applications、result（写操作）。列表类的
 * items 由 {@link dto.course.teacher.TeacherPageDTO} 承载（含 totalCount/page/size），客户端用
 * TypeToken 解析泛型页。数据库异常只写服务端日志，响应里不出现 SQL、表名或堆栈。
 *
 * <p>调课与成绩写请求体都位于 {@code data.request}；其中身份字段（uid/教师/助教）与 {@code force}
 * 是协议外字段，出现即 BAD_REQUEST，绝不传入服务——教师没有强制权限，新安排的教师/助教由服务端按
 * 原课次快照派生。提交与撤销/保存与提交都是写操作，Handler 不做“先查后写”的归属判断，权限一律由
 * 服务端在事务内重新计算。
 *
 * <p>文件动作的响应键：{@code ticket}（上传票据、以及成绩模板/名单导出两张下载票据）。Excel 文件
 * 本身绝不进业务 JSON，业务请求只带回一张绑定当前 Session、教师、教学班、用途与长度的短时票据，
 * 字节走独立文件端口。下载票据的生成顺序固定为「校验归属 → 生成文件 → 签发票据」：文件是从某个
 * 教学班的名单生成的，先发票据就等于先把别人的名单借出去。
 *
 * <p>成绩动作的响应键：{@code offerings}（成绩列表）、{@code gradeBook}（成绩表）、{@code result}
 * （保存/提交/确认导入的操作结果信封）、{@code preview}（导入预览与修订）。成绩冲突用
 * {@code gradeBook} 带回最新成绩表，与调课冲突的 {@code conflicts}/{@code latest} 区分开，
 * 客户端不解析对方的类型。
 *
 * <p>导入动作（previewGradeImport/reviseGradeImport/confirmGradeImport/cancelGradeImport）走同一套
 * 防线：身份只来自会话，写请求体不许带身份/人员/强制字段，预览的编辑副本与保存草稿的内容共用同一份
 * 形状校验。预览请求里的 {@code baseDraft} 直接沿用成绩写入的字段约定（offeringId 与每行
 * enrollmentId 只接受十进制字符串），因此导入不是绕过写请求校验的第二条入口。
 */
public class TeacherCourseHandler {
    private static final String MODULE = "courseTeacher";
    /** 与设计第 3 节一致：size 为 1..100。 */
    private static final int MAX_PAGE_SIZE = 100;
    private static final String TEACHER_ROLE = "教师";
    /** 写请求体只做一次 JSON → 类型转换，转换失败统一按 BAD_REQUEST 返回。 */
    private static final Gson GSON = new Gson();
    /**
     * 教师写请求体里绝不允许出现的字段：uid/教师/助教身份与强制标志。教师协议没有可替换人员
     * 的字段（服务端从原课次快照派生），也没有 force；出现任何一个是客户端伪造，直接拒绝。
     */
    private static final Set<String> FORGED_WRITE_FIELDS = Set.of(
            "uid", "teacherId", "teacherUid", "newTeacherUid",
            "assistantId", "assistantUid", "newAssistantUid", "force");

    private final TeacherCourseQueryService queries;
    private final TeacherAdjustmentApplicationService adjustments;
    private final TeacherGradeBookService grades;
    private final TeacherFileTicketService files;
    private final TeacherGradeImportService imports;
    /**
     * 表格读写本身不碰数据库也不碰票据，因此固定实例化，没有构造参数：调用方只需保证
     * 「先校验归属、再生成文件、最后签发票据」的顺序（下载票据一旦签发，文件就已经是别人的名单了）。
     */
    private final TeacherSpreadsheetService spreadsheets = new TeacherSpreadsheetService();

    public TeacherCourseHandler() {
        this(new TeacherCourseQueryService(), new TeacherAdjustmentApplicationService(),
                new TeacherGradeBookService(), null);
    }

    /**
     * 只读查询的构造：调课与成绩动作在该形态下报告“尚未开放”，与
     * {@link AdminCourseHandler} 对未接线服务的处理一致；生产入口使用无参构造。
     */
    public TeacherCourseHandler(TeacherCourseQueryService queries) {
        this(queries, null, null, null);
    }

    public TeacherCourseHandler(TeacherCourseQueryService queries,
                                TeacherAdjustmentApplicationService adjustments) {
        this(queries, adjustments, null, null);
    }

    public TeacherCourseHandler(TeacherCourseQueryService queries,
                                TeacherAdjustmentApplicationService adjustments,
                                TeacherGradeBookService grades) {
        this(queries, adjustments, grades, null);
    }

    public TeacherCourseHandler(TeacherCourseQueryService queries,
                                TeacherAdjustmentApplicationService adjustments,
                                TeacherGradeBookService grades,
                                TeacherFileTicketService files) {
        this(queries, adjustments, grades, files, null);
    }

    /**
     * 生产装配：导入预览/修订/确认需要一个同时认识文件票据、成绩表与预览仓的服务。
     * 未接线时导入动作报「尚未开放」，与其它可选服务的处理一致。
     */
    public TeacherCourseHandler(TeacherCourseQueryService queries,
                                TeacherAdjustmentApplicationService adjustments,
                                TeacherGradeBookService grades,
                                TeacherFileTicketService files,
                                TeacherGradeImportService imports) {
        this.queries = queries == null ? new TeacherCourseQueryService() : queries;
        this.adjustments = adjustments;
        this.grades = grades;
        this.files = files;
        this.imports = imports;
    }

    public Message handle(Message request) {
        Message response = response(request);
        UserSession session = SessionManager.getInstance().getSession(request.getToken());
        if (session == null) {
            return failure(response, MessageCode.UNAUTHORIZED, "登录会话已失效，请重新登录");
        }
        if (!TEACHER_ROLE.equals(session.getRole())) {
            return failure(response, MessageCode.FORBIDDEN, "仅教师可以访问教学班服务");
        }
        String action = request.getAction();
        if (action == null || action.isBlank()) {
            return failure(response, MessageCode.BAD_REQUEST, "Action 不能为空");
        }

        try {
            // 身份只来自会话；请求体中的任何归属字段都被忽略。
            String uid = session.getUsername();
            switch (action) {
                case TeacherCourseActions.LIST_TERMS ->
                        response.putData("terms", queries.listTerms(uid));
                case TeacherCourseActions.LIST_OFFERINGS -> {
                    Paging paging = paging(request);
                    response.putData("offerings", queries.listOfferings(uid,
                            integer(request, "academicYear"), integer(request, "semester"),
                            optionalText(request, "query"), paging.number(), paging.size()));
                }
                case TeacherCourseActions.GET_OFFERING -> response.putData("offering",
                        queries.getOffering(uid, decimalId(request, "offeringId")));
                case TeacherCourseActions.LIST_OFFERING_STUDENTS -> {
                    Paging paging = paging(request);
                    response.putData("students", queries.listOfferingStudents(uid,
                            decimalId(request, "offeringId"), optionalText(request, "query"),
                            enrollmentStatus(request), paging.number(), paging.size()));
                }
                case TeacherCourseActions.LIST_OFFERING_SCHEDULES -> response.putData("schedules",
                        queries.listOfferingSchedules(uid, decimalId(request, "offeringId")));
                case TeacherCourseActions.LOAD_TEACHING_SCHEDULE -> response.putData("schedule",
                        queries.loadTeachingSchedule(uid,
                                integer(request, "academicYear"), integer(request, "semester"),
                                optionalInteger(request, "week")));
                case TeacherCourseActions.GET_ADJUSTMENT_OPTIONS -> {
                    TeacherAdjustmentApplicationService service = adjustments();
                    response.putData("options", service.options(uid, decimalId(request, "offeringId"),
                            decimalId(request, "originalOccurrenceId")));
                }
                case TeacherCourseActions.PREVIEW_ADJUSTMENT -> {
                    // 纯预检查：忽略 operationId，也不要求原因非空；权限由服务端重算。
                    TeacherAdjustmentApplicationService service = adjustments();
                    response.putData("conflicts", service.preview(uid,
                            adjustmentWrite(request)));
                }
                case TeacherCourseActions.SUBMIT_ADJUSTMENT -> {
                    TeacherAdjustmentApplicationService service = adjustments();
                    return mutation(response, service.submit(uid, adjustmentWrite(request)));
                }
                case TeacherCourseActions.WITHDRAW_ADJUSTMENT -> {
                    TeacherAdjustmentApplicationService service = adjustments();
                    return mutation(response, service.withdraw(uid, withdrawal(request)));
                }
                case TeacherCourseActions.GET_ADJUSTMENT_REQUEST -> {
                    TeacherAdjustmentApplicationService service = adjustments();
                    response.putData("adjustmentRequest",
                            service.get(uid, decimalId(request, "requestId")));
                }
                case TeacherCourseActions.LIST_MY_ADJUSTMENT_REQUESTS -> {
                    TeacherAdjustmentApplicationService service = adjustments();
                    Paging paging = paging(request);
                    response.putData("applications", service.listMine(uid,
                            adjustmentStatus(request), paging.number(), paging.size()));
                }
                case TeacherCourseActions.LIST_GRADE_OFFERINGS -> {
                    TeacherGradeBookService service = grades();
                    Paging paging = paging(request);
                    response.putData("offerings", service.listGradeOfferings(uid,
                            integer(request, "academicYear"), integer(request, "semester"),
                            paging.number(), paging.size()));
                }
                case TeacherCourseActions.GET_GRADE_BOOK -> response.putData("gradeBook",
                        grades().getGradeBook(uid, decimalId(request, "offeringId")));
                case TeacherCourseActions.SAVE_GRADE_DRAFT -> {
                    return mutation(response, grades().saveDraft(uid, gradeWrite(request)));
                }
                case TeacherCourseActions.SUBMIT_GRADE_BOOK -> {
                    return mutation(response, grades().submitGradeBook(uid, gradeWrite(request)));
                }
                case TeacherCourseActions.BEGIN_GRADE_UPLOAD -> {
                    // 只签发短时票据：文件字节走独立端口，业务 JSON 里绝不出现 Base64 文件内容。
                    TeacherFileTicketService service = files();
                    TeacherFileUploadRequestDTO upload = uploadRequest(request);
                    response.putData("ticket", service.issueUpload(session,
                            upload.getOfferingId(), upload.getExpectedRevision(),
                            upload.getFileName(), upload.getByteLength(), upload.getSha256()));
                }
                case TeacherCourseActions.REQUEST_GRADE_TEMPLATE -> {
                    // 下载方向：先按归属校验入口取成绩表，再生成文件——模板里是学生名单，
                    // 顺序反了就等于把别人的班级名单发出去。
                    TeacherFileTicketService fileService = files();
                    String offeringId = decimalId(request, "offeringId");
                    TeacherGradeBookDTO gradeBook = grades().getGradeBook(uid, offeringId);
                    response.putData("ticket", issueDownloadFile(fileService, session, offeringId,
                            "成绩模板.xlsx",
                            path -> spreadsheets.writeGradeTemplate(path, gradeBook)));
                }
                case TeacherCourseActions.REQUEST_ROSTER_EXPORT -> {
                    // 与名单列表同一个归属校验入口；过滤条件相同，区别只是取全部结果而不是当前页。
                    TeacherFileTicketService fileService = files();
                    String offeringId = decimalId(request, "offeringId");
                    List<TeacherRosterRowDTO> roster = queries.listAllOfferingStudents(uid, offeringId,
                            optionalText(request, "query"), enrollmentStatus(request));
                    response.putData("ticket", issueDownloadFile(fileService, session, offeringId,
                            "学生名单.xlsx", path -> spreadsheets.writeRoster(path, roster)));
                }
                case TeacherCourseActions.PREVIEW_GRADE_IMPORT -> {
                    // 预览不写库：兑换上传票据、解析、把候选与问题一起回给界面。
                    response.putData("preview",
                            imports().preview(uid, session, previewImport(request)));
                }
                case TeacherCourseActions.REVISE_GRADE_IMPORT -> {
                    response.putData("preview", imports().revise(uid, reviseImport(request)));
                }
                case TeacherCourseActions.CONFIRM_GRADE_IMPORT -> {
                    return mutation(response, imports().confirm(uid, confirmImport(request)));
                }
                case TeacherCourseActions.CANCEL_GRADE_IMPORT -> {
                    // 取消只是丢弃令牌；客户端恢复自己的编辑副本，服务端本来就没写过任何东西。
                    imports().cancel(uid, importToken(request));
                }
                default -> {
                    return failure(response, MessageCode.BAD_REQUEST, "不支持的教师课程操作");
                }
            }
            response.setCode(MessageCode.SUCCESS);
            return response;
        } catch (TeacherAccessPolicy.AccessDeniedException denied) {
            // 授权失败只表达“无权限”，与“对象不存在 / 空名单”区分，且不泄露内部关系。
            return failure(response, MessageCode.FORBIDDEN, denied.getMessage());
        } catch (IllegalArgumentException invalid) {
            return failure(response, MessageCode.BAD_REQUEST, invalid.getMessage());
        } catch (TeacherAdjustmentApplicationService.NotFoundException missing) {
            return failure(response, MessageCode.NOT_FOUND, missing.getMessage());
        } catch (TeacherAdjustmentApplicationService.ConflictException conflict) {
            // 与管理员调课审批同形：冲突类型化列表 + 最新可见实体（可能没有）。
            response.putData("conflicts", conflict.getConflicts());
            if (conflict.getEntity() != null) response.putData("latest", conflict.getEntity());
            return failure(response, MessageCode.CONFLICT, conflict.getMessage());
        } catch (TeacherGradeImportService.NotFoundException expired) {
            // 导入预览已过期或不属于本人：客户端据此回到「重新上传」这一步。
            return failure(response, MessageCode.NOT_FOUND, expired.getMessage());
        } catch (TeacherGradeBookService.ConflictException conflict) {
            // 成绩冲突只有“最新成绩表”一种附带实体：版本过期或名单变化时客户端据此提示重新加载。
            if (conflict.getEntity() != null) response.putData("gradeBook", conflict.getEntity());
            return failure(response, MessageCode.CONFLICT, conflict.getMessage());
        } catch (DatabaseException failure) {
            logFailure(action, failure);
            return failure(response, MessageCode.ERROR, "教师课程服务暂不可用");
        } catch (RuntimeException failure) {
            logFailure(action, failure);
            return failure(response, MessageCode.ERROR, "服务端内部错误");
        }
    }

    private TeacherAdjustmentApplicationService adjustments() {
        if (adjustments == null) {
            throw new IllegalArgumentException("该教师操作尚未开放");
        }
        return adjustments;
    }

    private TeacherGradeBookService grades() {
        if (grades == null) {
            throw new IllegalArgumentException("该教师操作尚未开放");
        }
        return grades;
    }

    private TeacherFileTicketService files() {
        if (files == null) {
            throw new IllegalArgumentException("该教师操作尚未开放");
        }
        return files;
    }

    private TeacherGradeImportService imports() {
        if (imports == null) {
            throw new IllegalArgumentException("该教师操作尚未开放");
        }
        return imports;
    }

    /**
     * 在文件服务的临时目录里生成一个下载用的表格**并签发票据**，返回票据（响应键 {@code ticket}）。
     *
     * <p>文件名由服务端生成，客户端只贡献扩展名。生成与签发必须共用同一个收尾：写表失败与签发失败
     * （工作簿超过 5 MiB、会话失效）都会留下一个**没有票据条目**的文件，而
     * {@link TeacherFileTicketService#purgeExpired} 是按票据条目回收临时文件的——看不见它，就永远
     * 收不走，一份这样的残件会一直占到停服。因此两步放在同一个 try 里，任何一步抛出都先删掉半成品
     * 再抛（下载方向文件连接也会在成功发送后回收，那条路径已经不会留孤儿了）。
     */
    private TeacherFileTicketDTO issueDownloadFile(TeacherFileTicketService fileService,
            UserSession session, String offeringId, String clientFileName, Consumer<Path> writer) {
        Path target = fileService.newTempFile(clientFileName);
        try {
            writer.accept(target);
            return fileService.issueDownload(session, offeringId, target);
        } catch (RuntimeException failure) {
            TeacherFileTicketService.deleteQuietly(target);
            throw failure;
        }
    }

    /** 写操作响应：结果信封进 result，响应消息取自操作结果，与管理员课程写操作一致。 */
    private static <T> Message mutation(Message response, TeacherOperationResultDTO<T> result) {
        response.putData("result", result);
        response.setCode(MessageCode.SUCCESS);
        response.setMessage(result.getMessage());
        return response;
    }

    /**
     * 解析调课写请求体。先拒绝身份/人员/force 伪造字段与非法原始类型，再交给 Gson；operationId
     * 对提交/撤销由服务校验 UUID（BAD_REQUEST 原样返回），预览忽略它。
     */
    private TeacherAdjustmentWriteDTO adjustmentWrite(Message request) {
        Map<String, Object> values = requestValues(request);
        requireDecimalText(values.get("offeringId"), "offeringId");
        Object rawTargets = values.get("targets");
        if (!(rawTargets instanceof List<?> targets)) {
            throw new IllegalArgumentException("targets 必须为数组");
        }
        for (Object raw : targets) {
            if (!(raw instanceof Map<?, ?> target)) {
                throw new IllegalArgumentException("targets 的元素必须为 JSON 对象");
            }
            requireDecimalText(target.get("originalOccurrenceId"), "originalOccurrenceId");
            Object targetDate = target.get("targetDate");
            if (targetDate != null && !(targetDate instanceof String)) {
                throw new IllegalArgumentException("targetDate 必须为 ISO 本地日期字符串");
            }
        }
        requireOptionalString(values, "operationId");
        requireOptionalString(values, "newClassroomId");
        requireOptionalString(values, "reason");
        return payload(request, TeacherAdjustmentWriteDTO.class);
    }

    /**
     * 解析成绩写请求体（保存草稿/提交）。
     *
     * <p>与调课写请求同一套防线，但字段不同：身份/人员/force 伪造字段同样出现即拒绝；BIGINT 标识
     * （offeringId、每行的 enrollmentId）只接受十进制字符串，避免 Gson 经 double 静默改写；
     * operationId/rosterDigest 必须是字符串，内容里的 rows 必须是对象数组。这些检查都在 Gson 之前，
     * 因此“数字放进字符串字段”这类输入会在转换阶段就被拒绝，而不是变成一个看似合法的请求。
     * 归属与版本的真实性由服务端在事务内重新校验，Handler 只保证形状。
     */
    private WriteGradeBookRequestDTO gradeWrite(Message request) {
        Map<String, Object> values = gradeValues(request);
        Object rawContent = values.get("content");
        if (!(rawContent instanceof Map<?, ?> content)) {
            throw new IllegalArgumentException("content 必须为 JSON 对象");
        }
        requireGradeContent(content, "content");
        requireOptionalString(values, "operationId");
        return payload(request, WriteGradeBookRequestDTO.class);
    }

    /**
     * 成绩内容（保存/提交的 {@code content} 与导入预览的 {@code baseDraft}）的形状校验：
     * 同一份规则只写一次，两个入口不会各有一套取整与伪造字段口径。
     *
     * <p>内容里不许出现身份/人员/强制字段（出现即说明客户端在试图自己指定归属）；BIGINT 标识只接受
     * 十进制字符串，避免 Gson 经 double 静默改写；内容里的 rows 必须是对象数组。归属与版本的真实性
     * 由服务端在事务内重新校验，Handler 只保证形状。
     */
    private static void requireGradeContent(Map<?, ?> content, String label) {
        rejectForgedFields(content, "成绩");
        requireDecimalText(content.get("offeringId"), "offeringId");
        Object rawRows = content.get("rows");
        if (!(rawRows instanceof List<?> rows)) {
            throw new IllegalArgumentException(label + ".rows 必须为数组");
        }
        for (Object raw : rows) {
            if (!(raw instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException(label + ".rows 的元素必须为 JSON 对象");
            }
            requireDecimalText(row.get("enrollmentId"), "enrollmentId");
            Object rawScores = row.get("scores");
            if (rawScores != null && !(rawScores instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("scores 必须为 JSON 对象");
            }
        }
        requireOptionalString(content, "rosterDigest");
    }

    /**
     * 导入预览请求体：一张已经上传成功的票据 + 教师当前的编辑副本。副本走与成绩写入完全相同的内容
     * 校验（含伪造字段防线），因此预览收到的 baseDraft 形状与保存草稿时一致。
     */
    private PreviewGradeImportRequestDTO previewImport(Message request) {
        Map<String, Object> values = gradeValues(request);
        requireOptionalString(values, "uploadTicket");
        Object rawDraft = values.get("baseDraft");
        if (!(rawDraft instanceof Map<?, ?> draft)) {
            throw new IllegalArgumentException("baseDraft 必须为 JSON 对象");
        }
        requireGradeContent(draft, "baseDraft");
        return payload(request, PreviewGradeImportRequestDTO.class);
    }

    /** 修订请求体：令牌、期望的预览版本、修正与排除行；行号只接受整数，不合法就直接拒绝。 */
    private ReviseGradeImportRequestDTO reviseImport(Message request) {
        Map<String, Object> values = gradeValues(request);
        requireOptionalString(values, "importToken");
        integerValue(values.get("expectedPreviewRevision"), "expectedPreviewRevision");
        Object rawCorrections = values.get("corrections");
        if (!(rawCorrections instanceof List<?> corrections)) {
            throw new IllegalArgumentException("corrections 必须为数组");
        }
        for (Object raw : corrections) {
            if (!(raw instanceof Map<?, ?> correction)) {
                throw new IllegalArgumentException("corrections 的元素必须为 JSON 对象");
            }
            integerValue(correction.get("rowNumber"), "rowNumber");
            Object rawCells = correction.get("correctedCells");
            if (!(rawCells instanceof Map<?, ?> cells)) {
                throw new IllegalArgumentException("correctedCells 必须为 JSON 对象");
            }
            for (Map.Entry<?, ?> entry : cells.entrySet()) {
                if (!(entry.getKey() instanceof String)
                        || !(entry.getValue() instanceof String)) {
                    throw new IllegalArgumentException(
                            "correctedCells 必须是「字段名 → 文本」的字符串映射");
                }
            }
        }
        Object rawExcluded = values.get("excludedRows");
        if (rawExcluded != null) {
            if (!(rawExcluded instanceof List<?> excluded)) {
                throw new IllegalArgumentException("excludedRows 必须为数组");
            }
            for (Object raw : excluded) {
                integerValue(raw, "excludedRows 的元素");
            }
        }
        return payload(request, ReviseGradeImportRequestDTO.class);
    }

    /**
     * 确认导入请求体：确认请求刻意不带成绩内容，因此这里只需要形状检查——候选只存在于服务端。
     */
    private ConfirmGradeImportRequestDTO confirmImport(Message request) {
        Map<String, Object> values = gradeValues(request);
        requireOptionalString(values, "operationId");
        requireOptionalString(values, "importToken");
        integerValue(values.get("expectedPreviewRevision"), "expectedPreviewRevision");
        integerValue(values.get("expectedRevision"), "expectedRevision");
        return payload(request, ConfirmGradeImportRequestDTO.class);
    }

    /** 取消导入请求体：只有一个令牌，与其它写请求共用伪造字段防线。 */
    private static String importToken(Message request) {
        Map<String, Object> values = gradeValues(request);
        requireOptionalString(values, "importToken");
        Object token = values.get("importToken");
        if (!(token instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("importToken 不能为空");
        }
        return text.trim();
    }

    /**
     * 解析上传票据请求体：只有“哪个教学班、基于哪个草稿版本、文件多大、摘要是什么”，没有文件内容。
     *
     * <p>与调课/成绩写请求同一套伪造字段防线；BIGINT 标识只接受十进制字符串，长度必须是整数
     * （越界、摘要格式与归属由票据服务在签发时再校验，规则只有一个出口）。教学班的归属在导入
     * 预览/确认时重新核验，票据只负责把用途、长度和身份钉在一起。
     */
    private TeacherFileUploadRequestDTO uploadRequest(Message request) {
        Map<String, Object> values = writeValues(request, "上传");
        requireDecimalText(values.get("offeringId"), "offeringId");
        requireOptionalString(values, "fileName");
        requireOptionalString(values, "sha256");
        integerValue(values.get("expectedRevision"), "expectedRevision");
        integerValue(values.get("byteLength"), "byteLength");
        return payload(request, TeacherFileUploadRequestDTO.class);
    }

    private WithdrawTeacherAdjustmentRequestDTO withdrawal(Message request) {
        Map<String, Object> values = requestValues(request);
        requireOptionalString(values, "operationId");
        requireDecimalText(values.get("requestId"), "requestId");
        return payload(request, WithdrawTeacherAdjustmentRequestDTO.class);
    }

    /**
     * data.request 必须是 JSON 对象，且不得携带伪造身份/人员/强制字段：教师写协议没有这些字段，
     * 出现即拒绝，避免客户端以为可以替换人员或强制通过。
     */
    private static Map<String, Object> requestValues(Message request) {
        return writeValues(request, "调课");
    }

    /** 成绩写请求体：与调课同一套伪造字段防线，错误文案按动作命名，便于定位是哪一类请求。 */
    private static Map<String, Object> gradeValues(Message request) {
        return writeValues(request, "成绩");
    }

    private static Map<String, Object> writeValues(Message request, String subject) {
        Object value = request.getData() == null ? null : request.getData().get("request");
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("request 必须为 JSON 对象");
        }
        rejectForgedFields(raw, subject);
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key) values.put(key, entry.getValue());
        }
        return values;
    }

    /** 身份/人员/强制字段出现即拒绝；请求体与它的 content 走同一份名单，不会有一处漏检。 */
    private static void rejectForgedFields(Map<?, ?> raw, String subject) {
        for (String forged : FORGED_WRITE_FIELDS) {
            if (raw.containsKey(forged)) {
                throw new IllegalArgumentException(
                        forged + " 不是教师" + subject + "字段，教师不能指定人员或强制通过");
            }
        }
    }

    /** JSON → 写 DTO；类型不匹配（数字放进字符串字段、小数放进整数等）统一按 BAD_REQUEST 表达。 */
    private <T> T payload(Message request, Class<T> type) {
        Object value = request.getData() == null ? null : request.getData().get("request");
        try {
            return GSON.fromJson(GSON.toJson(value), type);
        } catch (JsonParseException | IllegalStateException | NumberFormatException failure) {
            throw new IllegalArgumentException("request 字段格式无效", failure);
        }
    }

    /** BIGINT 标识只接受十进制字符串；数字会被 Gson 经 double 静默改写，必须在解析前拒绝。 */
    private static void requireDecimalText(Object value, String key) {
        if (!(value instanceof String text) || !text.matches("[0-9]+")) {
            throw new IllegalArgumentException(key + " 必须为十进制字符串");
        }
        try {
            if (Long.parseLong(text) <= 0) {
                throw new IllegalArgumentException(key + " 必须为正整数");
            }
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " 超出 BIGINT 范围");
        }
    }

    private static void requireOptionalString(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(key + " 必须为字符串");
        }
    }

    /** 我的调课申请筛选：缺省交给服务端默认 PENDING，未知值一律 400（四态，含教师撤销）。 */
    private static AdjustmentRequestStatusDTO adjustmentStatus(Message request) {
        String status = optionalText(request, "status");
        if (status == null) return null;
        try {
            return AdjustmentRequestStatusDTO.valueOf(status.trim());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "status 必须为 PENDING、APPROVED、REJECTED 或 WITHDRAWN");
        }
    }

    private static Message response(Message request) {
        Message response = new Message(MessageType.RESPONSE, MODULE, request.getAction());
        response.setUID(request.getUID());
        return response;
    }

    private static Message failure(Message response, MessageCode code, String message) {
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    /** 请求里成对出现的 page/size；两者一起校验，避免页码与页大小各自越界。 */
    private record Paging(int number, int size) {
    }

    private static Paging paging(Message request) {
        int number = integer(request, "page");
        int size = integer(request, "size");
        if (number < 1) {
            throw new IllegalArgumentException("page 必须大于 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size 必须为 1 至 100");
        }
        // DAO 的 OFFSET 是 int；(page-1)*size 溢出的页码在这里就拒绝，绝不落到 SQL 层。
        if ((long) (number - 1) * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page 超出有效范围");
        }
        return new Paging(number, size);
    }

    /** enrollmentStatus 只接受缺省（NULL）、2（正常）与 3（退课）；其余一律视为非法输入。 */
    private static Integer enrollmentStatus(Message request) {
        Map<String, Object> data = request.getData();
        if (data == null || data.get("enrollmentStatus") == null) {
            return null;
        }
        int status = integer(request, "enrollmentStatus");
        if (status != 2 && status != 3) {
            throw new IllegalArgumentException("enrollmentStatus 只接受 2（正常）或 3（退课）");
        }
        return status;
    }

    /**
     * week 可缺省：缺省表示“由服务端按教学日历决定当前周”。出现时必须是合法整数（越界由服务层判定，
     * 因为它们依赖教学日历的 minWeek/maxWeek）。
     */
    private static Integer optionalInteger(Message request, String key) {
        Map<String, Object> data = request.getData();
        if (data == null || data.get(key) == null) return null;
        return integer(request, key);
    }

    private static void logFailure(String action, RuntimeException failure) {
        System.err.println("教师课程请求处理失败: action=" + action);
        failure.printStackTrace(System.err);
    }

    private static String optionalText(Message request, String key) {
        Map<String, Object> data = request.getData();
        Object value = data == null ? null : data.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " 必须为字符串");
        }
        return text.isBlank() ? null : text;
    }

    private static int integer(Message request, String key) {
        return integerValue(data(request, key), key);
    }

    /**
     * 整数字段的统一判定：data 顶层的查询参数与 data.request 内的写请求体共用同一套规则，
     * 因此“上传声明了几个字节”和“第几页”不会各有一套取整口径。
     */
    private static int integerValue(Object value, String key) {
        if (value == null) {
            throw new IllegalArgumentException("缺少参数: " + key);
        }
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)
                    || decimal < Integer.MIN_VALUE || decimal > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(key + " 必须为整数");
            }
            return (int) decimal;
        }
        if (value instanceof String text && text.matches("-?[0-9]+")) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException(key + " 超出整数范围");
            }
        }
        throw new IllegalArgumentException(key + " 必须为整数");
    }

    /** BIGINT 标识在网络上是十进制字符串；非数字、非正数、超出范围都在这里拒绝。 */
    private static String decimalId(Message request, String key) {
        Object value = data(request, key);
        if (!(value instanceof String text) || !text.matches("[0-9]+")) {
            throw new IllegalArgumentException(key + " 必须为十进制字符串");
        }
        try {
            if (Long.parseLong(text) <= 0) {
                throw new IllegalArgumentException(key + " 必须为正整数");
            }
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " 超出 BIGINT 范围");
        }
        return text;
    }

    private static Object data(Message request, String key) {
        Map<String, Object> data = request.getData();
        Object value = data == null ? null : data.get(key);
        if (value == null) throw new IllegalArgumentException("缺少参数: " + key);
        return value;
    }
}
