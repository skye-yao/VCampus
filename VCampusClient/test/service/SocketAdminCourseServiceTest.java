package service;

import com.google.gson.Gson;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.GradeDistributionBucketDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.admin.catalog.AdminCourseDTO;
import dto.course.admin.catalog.AdminOfferingDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.enrollment.OfferingStudentDTO;
import dto.course.admin.enrollment.StudentSearchResultDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminEnrollmentPageView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import model.course.admin.OfferingStudentView;
import model.course.admin.StudentSearchResultView;
import model.course.admin.ScheduleArrangementView;
import model.course.admin.SchedulePlanView;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.ClientSession;

public final class SocketAdminCourseServiceTest {
    private static final String TOKEN = "token-123";
    private static final String COURSE_ID = "9007199254740993";
    private static final String OFFERING_ID = "9007199254740995";
    private static final String REQUEST_ID = "9007199254740999";
    private static final String GRADE_SUBMISSION_ID = "9007199254740993";

    public static void main(String[] args) {
        ClientSession.getInstance().login("admin-alpha", "管理员", TOKEN, null);
        try {
            listCoursesSendsFiltersAndMapsBigIds();
            listCoursesOmitsNullFilters();
            createCourseSendsRequestInstanceAndMapsResult();
            updateCourseSendsRequestInstanceAndMapsResult();
            archiveAndRestoreCourseSendTargetKeys();
            listOfferingsMapsNullableAssistant();
            createAndUpdateOfferingSendRequestInstance();
            cancelOfferingSendsTargetKeys();
            deleteDraftOfferingMapsNullEntity();
            nonSuccessBecomesStableException();
            catalogConflictMapsLatestCourse();
            offeringConflictMapsLatestOffering();
            nullResponseBecomesError();
            schedulingMethodsAreDeclaredInTheService();
            listScheduleResourcesSendsFiltersAndMapsResources();
            loadSchedulePlanMapsTermAndNestedConflicts();
            loadOfferingArrangementsMapsViewsAndOmitsNullOffering();
            checkArrangementSendsRequestInstanceAndMapsConflicts();
            saveArrangementSendsRequestInstanceAndMapsView();
            deleteArrangementSendsTargetKeysAndMapsNullEntity();
            publishSchedulePlanSendsTargetKeysAndMapsPlanView();
            schedulingConflictMapsLatestArrangement();
            studentSearchMapsPageAndListAdapter();
            offeringStudentsMapPageAndListAdapter();
            enrollmentPreviewMapsExactTargetsAndRiskSeverities();
            enrollmentMutationsMapTypedHistoryRows();
            enrollmentConflictsKeepTypedRisksAndLatestRow();
            enrollmentPageRequiresServerMetadata();
            adjustmentMethodsAreDeclaredInTheService();
            adjustmentListSendsFiltersAndMapsItsPage();
            adjustmentDetailAndDecisionMapTypedPayloads();
            adjustmentConflictKeepsTypedRisksAndLatestDetail();
            gradeMethodsAreDeclaredInTheService();
            gradeListSendsFiltersAndMapsItsPage();
            gradeDetailAndDecisionMapTypedPayloads();
            gradeConflictKeepsTypedLatestDetail();
        } finally {
            ClientSession.getInstance().logout();
        }
        System.out.println("SocketAdminCourseServiceTest: PASS");
    }

    private static void listCoursesSendsFiltersAndMapsBigIds() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("courses", List.of(courseDto())));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        List<AdminCourseView> courses = service.listCourses("数据", "ACTIVE").join();
        requireEnvelope(transport, AdminCourseActions.LIST_COURSES);
        require("数据".equals(transport.lastRequest.getData("query")),
                "query filter must travel unchanged");
        require("ACTIVE".equals(transport.lastRequest.getData("status")),
                "status filter must travel unchanged");

        require(courses.size() == 1, "one course expected");
        AdminCourseView course = courses.get(0);
        require(COURSE_ID.equals(course.getCourseId()),
                "course ID must stay the exact decimal string");
        require("CS203".equals(course.getCourseCode())
                        && "数据结构".equals(course.getCourseName())
                        && "必修".equals(course.getCourseType()),
                "course text fields must map");
        require(course.getCredit() == 4.0 && course.getCreditHours() == 64,
                "credit and hours must map");
        require("线性表、树和图".equals(course.getDescription())
                        && "程序设计基础".equals(course.getPrerequisites()),
                "description and prerequisites must map");
        require(course.isAllowCrossMajor() && course.isFinalExam(),
                "boolean flags must map");
        require("ACTIVE".equals(course.getStatus()), "status must stay the server string");
        require(course.getOfferingCount() == 2 && course.getVersion() == 3,
                "offeringCount and version must map");
    }

    private static void listCoursesOmitsNullFilters() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("courses", List.of()));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        service.listCourses(null, null).join();
        requireEnvelope(transport, AdminCourseActions.LIST_COURSES);
        require(transport.lastRequest.getData("query") == null,
                "a null query must be omitted from the request");
        require(transport.lastRequest.getData("status") == null,
                "a null status must be omitted from the request");
    }

    private static void createCourseSendsRequestInstanceAndMapsResult() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result", courseResult()));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        CourseEditorRequestDTO request = courseRequest("op-create", null, 0);
        AdminOperationResultView<AdminCourseView> result = service.createCourse(request).join();
        require(transport.lastRequest.getData("request") == request,
                "the request payload must be the DTO instance itself");
        requireEnvelope(transport, AdminCourseActions.CREATE_COURSE);
        requireResult(result, "op-create", "课程已创建", COURSE_ID, 3);
    }

    private static void updateCourseSendsRequestInstanceAndMapsResult() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result", courseResult()));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        CourseEditorRequestDTO request = courseRequest("op-update", COURSE_ID, 3);
        AdminOperationResultView<AdminCourseView> result = service.updateCourse(request).join();
        require(transport.lastRequest.getData("request") == request,
                "the request payload must be the DTO instance itself");
        requireEnvelope(transport, AdminCourseActions.UPDATE_COURSE);
        requireResult(result, "op-create", "课程已创建", COURSE_ID, 3);
    }

    private static void archiveAndRestoreCourseSendTargetKeys() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result", courseResult()));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        AdminOperationResultView<AdminCourseView> archived =
                service.archiveCourse(COURSE_ID, 3, "op-archive").join();
        requireEnvelope(transport, AdminCourseActions.ARCHIVE_COURSE);
        require(COURSE_ID.equals(transport.lastRequest.getData("courseId")),
                "courseId must travel as the decimal string");
        require(Integer.valueOf(3).equals(transport.lastRequest.getData("expectedVersion")),
                "expectedVersion must travel as an Integer");
        require("op-archive".equals(transport.lastRequest.getData("operationId")),
                "operationId must travel unchanged");
        requireResult(archived, "op-create", "课程已创建", COURSE_ID, 3);

        transport.respond(message -> message.putData("result", courseResult()));
        service.restoreCourse(COURSE_ID, 3, "op-restore").join();
        requireEnvelope(transport, AdminCourseActions.RESTORE_COURSE);
        require(COURSE_ID.equals(transport.lastRequest.getData("courseId")),
                "restore must send the decimal courseId");
        require(Integer.valueOf(3).equals(transport.lastRequest.getData("expectedVersion")),
                "restore must send the expected version");
        require("op-restore".equals(transport.lastRequest.getData("operationId")),
                "restore must send the operationId");
    }

    private static void listOfferingsMapsNullableAssistant() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("offerings", List.of(offeringDto())));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        List<AdminOfferingView> offerings = service.listOfferings(COURSE_ID).join();
        requireEnvelope(transport, AdminCourseActions.LIST_OFFERINGS);
        require(COURSE_ID.equals(transport.lastRequest.getData("courseId")),
                "listOfferings must send the decimal courseId");

        require(offerings.size() == 1, "one offering expected");
        AdminOfferingView offering = offerings.get(0);
        require(OFFERING_ID.equals(offering.getOfferingId())
                        && COURSE_ID.equals(offering.getCourseId()),
                "offering IDs must stay exact decimal strings");
        require("OFF-1".equals(offering.getOfferingCode())
                        && offering.getAcademicYear() == 2026
                        && offering.getSemester() == 1
                        && offering.getCapacity() == 120
                        && offering.getEnrolledCount() == 30,
                "offering numbers must map");
        require("OPEN".equals(offering.getStatus())
                        && "SCHEDULED".equals(offering.getScheduleStatus())
                        && offering.getVersion() == 4,
                "offering status, schedule status and version must map");
        require("T1".equals(offering.getTeacherUid())
                        && "张老师".equals(offering.getTeacherName()),
                "teacher must map");
        require(offering.getAssistantUid() == null && offering.getAssistantName() == null,
                "a null assistant must survive the mapping");
    }

    private static void createAndUpdateOfferingSendRequestInstance() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result", offeringResult()));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        OfferingEditorRequestDTO createRequest = offeringRequest("op-offer-create", null, 0);
        AdminOperationResultView<AdminOfferingView> created =
                service.createOffering(createRequest).join();
        require(transport.lastRequest.getData("request") == createRequest,
                "the offering request payload must be the DTO instance itself");
        requireEnvelope(transport, AdminCourseActions.CREATE_OFFERING);
        requireOfferingResult(created);

        transport.respond(message -> message.putData("result", offeringResult()));
        OfferingEditorRequestDTO updateRequest =
                offeringRequest("op-offer-update", OFFERING_ID, 4);
        AdminOperationResultView<AdminOfferingView> updated =
                service.updateOffering(updateRequest).join();
        require(transport.lastRequest.getData("request") == updateRequest,
                "the offering update payload must be the DTO instance itself");
        requireEnvelope(transport, AdminCourseActions.UPDATE_OFFERING);
        requireOfferingResult(updated);
    }

    private static void cancelOfferingSendsTargetKeys() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result", offeringResult()));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        service.cancelOffering(OFFERING_ID, 4, "op-cancel").join();
        requireEnvelope(transport, AdminCourseActions.CANCEL_OFFERING);
        require(OFFERING_ID.equals(transport.lastRequest.getData("offeringId")),
                "offeringId must travel as the decimal string");
        require(Integer.valueOf(4).equals(transport.lastRequest.getData("expectedVersion")),
                "expectedVersion must travel as an Integer");
        require("op-cancel".equals(transport.lastRequest.getData("operationId")),
                "operationId must travel unchanged");
    }

    private static void deleteDraftOfferingMapsNullEntity() {
        FakeTransport transport = new FakeTransport();
        AdminOperationResultDTO<Void> dto =
                new AdminOperationResultDTO<>("op-delete", "OK", "教学班已删除", null, List.of());
        transport.respond(message -> message.putData("result", dto));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        AdminOperationResultView<Void> result =
                service.deleteDraftOffering(OFFERING_ID, 1, "op-delete").join();
        requireEnvelope(transport, AdminCourseActions.DELETE_DRAFT_OFFERING);
        require(OFFERING_ID.equals(transport.lastRequest.getData("offeringId")),
                "deleteDraft must send the decimal offeringId");
        require(Integer.valueOf(1).equals(transport.lastRequest.getData("expectedVersion")),
                "deleteDraft must send the expected version");
        require("op-delete".equals(transport.lastRequest.getData("operationId")),
                "deleteDraft must send the operationId");
        require(result.getEntity() == null, "deleteDraft must map a null entity");
        require("op-delete".equals(result.getOperationId())
                        && "OK".equals(result.getOutcomeCode())
                        && "教学班已删除".equals(result.getMessage()),
                "deleteDraft must map operationId, outcomeCode and message");
    }

    private static void nonSuccessBecomesStableException() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.BAD_REQUEST);
            message.setMessage("缺少参数");
        });
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        boolean thrown = false;
        try {
            service.listCourses(null, null).join();
        } catch (CompletionException failure) {
            thrown = failure.getCause()
                    instanceof SocketAdminCourseService.AdminCourseServiceException;
            if (thrown) {
                SocketAdminCourseService.AdminCourseServiceException error =
                        (SocketAdminCourseService.AdminCourseServiceException) failure.getCause();
                require(error.getCode() == MessageCode.BAD_REQUEST,
                        "non-success must expose the server message code");
                require("缺少参数".equals(error.getMessage()),
                        "non-success must expose the server message");
                require(error.getLatest() == null,
                        "a non-conflict failure must not carry a latest payload");
            }
        }
        require(thrown, "non-success must fail with a stable AdminCourseServiceException");
    }

    private static void catalogConflictMapsLatestCourse() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.CONFLICT);
            message.setMessage("课程版本或状态已变化，请刷新后重试");
            message.putData("latest", wireShaped(courseDto()));
        });
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        boolean thrown = false;
        try {
            service.archiveCourse(COURSE_ID, 3, "op-archive").join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketAdminCourseService.AdminCourseServiceException error) {
                thrown = true;
                require(error.getCode() == MessageCode.CONFLICT,
                        "a conflict must keep the CONFLICT code");
                require(error.getLatest() instanceof AdminCourseView,
                        "a catalog conflict must map latest to an AdminCourseView, not a raw map");
                AdminCourseView latest = (AdminCourseView) error.getLatest();
                require(COURSE_ID.equals(latest.getCourseId()),
                        "the mapped latest course must keep the exact BIGINT course id");
                require("CS203".equals(latest.getCourseCode())
                                && "数据结构".equals(latest.getCourseName()),
                        "the mapped latest course must keep its code and name");
                require(latest.getVersion() == 3 && latest.getOfferingCount() == 2,
                        "the mapped latest course must keep its version and offering count");
                require("ACTIVE".equals(latest.getStatus()) && latest.getCredit() == 4.0
                                && latest.getCreditHours() == 64,
                        "the mapped latest course must keep its status, credit and hours");
            }
        }
        require(thrown, "a catalog conflict must fail with AdminCourseServiceException");
    }

    private static void offeringConflictMapsLatestOffering() {
        assertOfferingConflictMapsLatest(service ->
                service.cancelOffering(OFFERING_ID, 4, "op-cancel"));
        assertOfferingConflictMapsLatest(service ->
                service.deleteDraftOffering(OFFERING_ID, 4, "op-delete"));
    }

    private static void assertOfferingConflictMapsLatest(
            Function<SocketAdminCourseService, CompletableFuture<?>> call) {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.CONFLICT);
            message.setMessage("教学班版本或状态已变化，请刷新后重试");
            message.putData("latest", wireShaped(offeringDto()));
        });
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        boolean thrown = false;
        try {
            call.apply(service).join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketAdminCourseService.AdminCourseServiceException error) {
                thrown = true;
                require(error.getCode() == MessageCode.CONFLICT,
                        "an offering conflict must keep the CONFLICT code");
                require(error.getLatest() instanceof AdminOfferingView,
                        "an offering conflict must map latest to an AdminOfferingView");
                AdminOfferingView latest = (AdminOfferingView) error.getLatest();
                require(OFFERING_ID.equals(latest.getOfferingId())
                                && COURSE_ID.equals(latest.getCourseId()),
                        "the mapped latest offering must keep the exact BIGINT ids");
                require(latest.getVersion() == 4 && "OPEN".equals(latest.getStatus()),
                        "the mapped latest offering must keep its version and status");
                require("OFF-1".equals(latest.getOfferingCode())
                                && latest.getAcademicYear() == 2026
                                && latest.getSemester() == 1,
                        "the mapped latest offering must keep its code and term");
                require(latest.getAssistantUid() == null,
                        "the mapped latest offering must keep a null assistant");
            }
        }
        require(thrown, "an offering conflict must fail with AdminCourseServiceException");
    }

    /** Mirrors the real transport: a socket response carries data as Gson maps, not typed DTOs. */
    private static Object wireShaped(Object dto) {
        Gson gson = new Gson();
        return gson.fromJson(gson.toJson(dto), Object.class);
    }

    private static void nullResponseBecomesError() {
        FakeTransport transport = new FakeTransport();
        transport.respondNull();
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        boolean thrown = false;
        try {
            service.listCourses(null, null).join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketAdminCourseService.AdminCourseServiceException error) {
                thrown = true;
                require(error.getCode() == MessageCode.ERROR,
                        "a null response must map to ERROR");
                require("课程管理服务无响应".equals(error.getMessage()),
                        "a null response must keep the stable message");
            }
        }
        require(thrown, "a null response must fail with AdminCourseServiceException");
    }

    private static void schedulingMethodsAreDeclaredInTheService() {
        declared("listScheduleResources", String.class, String.class);
        declared("loadSchedulePlan", int.class, int.class);
        declared("loadOfferingArrangements", String.class, String.class);
        declared("checkArrangement", SaveArrangementRequestDTO.class);
        declared("saveArrangement", SaveArrangementRequestDTO.class);
        declared("deleteArrangement", String.class, int.class, String.class);
        declared("publishSchedulePlan", String.class, int.class, String.class, boolean.class,
                String.class);
    }

    private static void declared(String name, Class<?>... parameters) {
        try {
            SocketAdminCourseService.class.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException failure) {
            throw new AssertionError("SocketAdminCourseService must override " + name
                    + "; an inherited throwing default must not remain");
        }
    }

    private static void listScheduleResourcesSendsFiltersAndMapsResources() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("resources",
                List.of(wireShaped(resource()))));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        List<ScheduleResourceDTO> resources = service.listScheduleResources("teacher", "张").join();
        requireEnvelope(transport, AdminCourseActions.LIST_SCHEDULE_RESOURCES);
        require("teacher".equals(transport.lastRequest.getData("type")),
                "the resource type filter must travel unchanged");
        require("张".equals(transport.lastRequest.getData("query")),
                "the resource query filter must travel unchanged");
        require(resources.size() == 1, "one resource expected");
        ScheduleResourceDTO resource = resources.get(0);
        require("8001".equals(resource.getResourceId())
                        && "T1001".equals(resource.getBusinessId())
                        && "张老师".equals(resource.getName())
                        && "teacher".equals(resource.getResourceType())
                        && resource.getCapacity() == 0,
                "rescheduling resource fields must map");

        transport.respond(message -> message.putData("resources", List.of()));
        service.listScheduleResources(null, null).join();
        require(transport.lastRequest.getData("type") == null
                        && transport.lastRequest.getData("query") == null,
                "null resource filters must be omitted");
    }

    private static void loadSchedulePlanMapsTermAndNestedConflicts() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("plan", wireShaped(planDto())));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        SchedulePlanDTO plan = service.loadSchedulePlan(2026, 1).join();
        requireEnvelope(transport, AdminCourseActions.LOAD_SCHEDULE_PLAN);
        require(Integer.valueOf(2026).equals(transport.lastRequest.getData("academicYear"))
                        && Integer.valueOf(1).equals(transport.lastRequest.getData("semester")),
                "the term must travel as ints");
        require("7001".equals(plan.getPlanId()) && "2026 秋排课方案".equals(plan.getName()),
                "plan identity must map");
        require(plan.getRevision() == 1 && "DRAFT".equals(plan.getStatus()) && !plan.isCurrent(),
                "plan revision, status and current flag must map");
        require(plan.getConflicts().size() == 1
                        && plan.getConflicts().get(0).getSeverity()
                                == ScheduleConflictSeverityDTO.BLOCKING,
                "nested conflicts must map through the plan with their severity");
    }

    private static void loadOfferingArrangementsMapsViewsAndOmitsNullOffering() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("arrangements",
                List.of(wireShaped(arrangementDto()))));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        List<ScheduleArrangementView> views =
                service.loadOfferingArrangements("7001", "1001").join();
        requireEnvelope(transport, AdminCourseActions.LOAD_OFFERING_ARRANGEMENTS);
        require("7001".equals(transport.lastRequest.getData("planId"))
                        && "1001".equals(transport.lastRequest.getData("offeringId")),
                "plan and offering ids must travel as decimal strings");
        require(views.size() == 1, "one arrangement expected");
        ScheduleArrangementView view = views.get(0);
        require("9001".equals(view.getArrangementId()) && "7001".equals(view.getPlanId())
                        && "1001".equals(view.getOfferingId()),
                "arrangement identity must map");
        require(view.getSlots().size() == 2
                        && view.getSlots().get(0).getDayOfWeek() == 1
                        && view.getSlots().get(1).getEndPeriod() == 4,
                "both typed slots must map");
        require(view.getStartWeek() == 1 && view.getEndWeek() == 16
                        && "DRAFT".equals(view.getStatus()) && view.getVersion() == 3,
                "week range, status and version must map");
        require(view.getTeacher() != null
                        && "T1001".equals(view.getTeacher().getBusinessId())
                        && view.getAssistant() == null
                        && "3001".equals(view.getClassroom().getBusinessId()),
                "resources must map, keeping a null assistant");

        transport.respond(message -> message.putData("arrangements", List.of()));
        service.loadOfferingArrangements("7001", null).join();
        require(transport.lastRequest.getData("offeringId") == null,
                "a null offering id must be omitted so all offering arrangements load");
    }

    private static void checkArrangementSendsRequestInstanceAndMapsConflicts() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("conflicts",
                List.of(wireShaped(conflict()))));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        SaveArrangementRequestDTO request = arrangementRequest("op-check", null, 0, false, null);
        List<ScheduleConflictDTO> conflicts = service.checkArrangement(request).join();
        requireEnvelope(transport, AdminCourseActions.CHECK_ARRANGEMENT);
        require(transport.lastRequest.getData("request") == request,
                "the check payload must be the DTO instance itself");
        require(conflicts.size() == 1, "one conflict expected");
        ScheduleConflictDTO conflict = conflicts.get(0);
        require("TEACHER".equals(conflict.getType())
                        && conflict.getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE,
                "conflict type and severity must map");
        require(conflict.getWeek() == 1 && conflict.getDayOfWeek() == 1
                        && conflict.getStartPeriod() == 1 && conflict.getEndPeriod() == 2,
                "conflict window must map");
        require("2001".equals(conflict.getRelatedOfferingId())
                        && "教师时间冲突".equals(conflict.getMessage()),
                "conflict relation and message must map");
    }

    private static void saveArrangementSendsRequestInstanceAndMapsView() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result",
                wireShaped(arrangementResult("op-save"))));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        SaveArrangementRequestDTO request = arrangementRequest("op-save", null, 0, false, null);
        AdminOperationResultView<ScheduleArrangementView> result =
                service.saveArrangement(request).join();
        requireEnvelope(transport, AdminCourseActions.SAVE_ARRANGEMENT);
        require(transport.lastRequest.getData("request") == request,
                "the save payload must be the DTO instance itself");
        require("op-save".equals(result.getOperationId())
                        && "OK".equals(result.getOutcomeCode())
                        && "教学安排已保存".equals(result.getMessage()),
                "save result envelope must map");
        require(result.getEntity() != null
                        && "9001".equals(result.getEntity().getArrangementId())
                        && result.getEntity().getSlots().size() == 2,
                "the saved arrangement must map to a view with both slots");
    }

    private static void deleteArrangementSendsTargetKeysAndMapsNullEntity() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result", wireShaped(voidResult())));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        AdminOperationResultView<Void> result =
                service.deleteArrangement("9001", 3, "op-delete").join();
        requireEnvelope(transport, AdminCourseActions.DELETE_ARRANGEMENT);
        require("9001".equals(transport.lastRequest.getData("arrangementId")),
                "arrangementId must travel as the decimal string");
        require(Integer.valueOf(3).equals(transport.lastRequest.getData("expectedVersion")),
                "expectedVersion must travel as an Integer");
        require("op-delete".equals(transport.lastRequest.getData("operationId")),
                "operationId must travel unchanged");
        require(result.getEntity() == null, "a delete result must map a null entity");
        require("op-delete".equals(result.getOperationId())
                        && "教学安排已删除".equals(result.getMessage()),
                "delete result fields must map");
    }

    private static void publishSchedulePlanSendsTargetKeysAndMapsPlanView() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("result", wireShaped(planResult())));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        AdminOperationResultView<SchedulePlanView> result = service.publishSchedulePlan(
                "7001", 1, "op-publish", true, "教室临时调整").join();
        requireEnvelope(transport, AdminCourseActions.PUBLISH_SCHEDULE_PLAN);
        require("7001".equals(transport.lastRequest.getData("planId"))
                        && Integer.valueOf(1).equals(transport.lastRequest.getData("expectedRevision")),
                "planId and expectedRevision must travel");
        require("op-publish".equals(transport.lastRequest.getData("operationId")),
                "operationId must travel unchanged");
        require(Boolean.TRUE.equals(transport.lastRequest.getData("force"))
                        && "教室临时调整".equals(transport.lastRequest.getData("overrideReason")),
                "force intent and reason must travel for server-side trimming");
        require(result.getEntity() != null
                        && "7001".equals(result.getEntity().getPlanId())
                        && "PUBLISHED".equals(result.getEntity().getStatus())
                        && result.getEntity().getRevision() == 2,
                "the published plan must map to a view");
    }

    private static void schedulingConflictMapsLatestArrangement() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.CONFLICT);
            message.setMessage("存在阻断性冲突，无法保存");
            message.putData("conflicts", List.of(wireShaped(conflict())));
            message.putData("latest", wireShaped(arrangementDto()));
        });
        SocketAdminCourseService service = new SocketAdminCourseService(transport);

        boolean thrown = false;
        try {
            service.saveArrangement(arrangementRequest("op-save", null, 0, false, null)).join();
        } catch (CompletionException failure) {
            if (failure.getCause()
                    instanceof SocketAdminCourseService.AdminCourseServiceException error) {
                thrown = true;
                require(error.getCode() == MessageCode.CONFLICT,
                        "a scheduling conflict must keep the CONFLICT code");
                require(error.getLatest() instanceof ScheduleArrangementView,
                        "a scheduling conflict must map latest to a ScheduleArrangementView, "
                                + "not a raw map");
                ScheduleArrangementView latest = (ScheduleArrangementView) error.getLatest();
                require("9001".equals(latest.getArrangementId())
                                && latest.getVersion() == 3,
                        "the mapped latest arrangement must keep its id and version");
            }
        }
        require(thrown, "a scheduling conflict must fail with AdminCourseServiceException");
    }

    private static ScheduleResourceDTO resource() {
        return new ScheduleResourceDTO("8001", "T1001", "张老师", "teacher", 0);
    }

    private static void studentSearchMapsPageAndListAdapter() {
        FakeTransport transport = new FakeTransport();
        Consumer<Message> response = message -> {
            message.putData("students", List.of(wireShaped(new StudentSearchResultDTO(
                    "20240031", "陈晨", "软件工程", 2024, "ACTIVE"))));
            message.putData("totalCount", 35.0);
            message.putData("pageNumber", 2.0);
            message.putData("pageSize", 10.0);
        };
        transport.respond(response);
        AdminCourseService service = new SocketAdminCourseService(transport);
        AdminEnrollmentPageView<StudentSearchResultView> page = service.searchStudentsPage(" 陈 ", 2, 10).join();
        requireEnvelope(transport, AdminCourseActions.SEARCH_STUDENTS);
        require(" 陈 ".equals(transport.lastRequest.getData("query"))
                        && Integer.valueOf(2).equals(transport.lastRequest.getData("pageNumber"))
                        && Integer.valueOf(10).equals(transport.lastRequest.getData("pageSize")),
                "search must send query and the pageNumber/pageSize wire contract");
        require(page.getTotalCount() == 35 && page.getPageNumber() == 2 && page.getPageSize() == 10,
                "search must preserve authoritative server pagination");
        StudentSearchResultView row = page.getItems().get(0);
        require("20240031".equals(row.getUid()) && "陈晨".equals(row.getName())
                        && "软件工程".equals(row.getMajor()) && row.getCohortYear() == 2024
                        && "ACTIVE".equals(row.getAcademicStatus()),
                "search must map complete StudentSearchResultView rows");
        transport.respond(response);
        require("陈晨".equals(service.searchStudents("陈", 2, 10).join().get(0).getName()),
                "the original List adapter must delegate to the real page implementation");
    }

    private static void offeringStudentsMapPageAndListAdapter() {
        FakeTransport transport = new FakeTransport();
        Consumer<Message> response = message -> {
            message.putData("offeringStudents", List.of(wireShaped(enrollmentRow("ENROLLED", false))));
            message.putData("totalCount", 30.0);
            message.putData("pageNumber", 1.0);
            message.putData("pageSize", 20.0);
        };
        transport.respond(response);
        AdminCourseService service = new SocketAdminCourseService(transport);
        AdminEnrollmentPageView<OfferingStudentView> page = service.listOfferingStudentsPage(
                OFFERING_ID, null, 1, 20).join();
        requireEnvelope(transport, AdminCourseActions.LIST_OFFERING_STUDENTS);
        require(OFFERING_ID.equals(transport.lastRequest.getData("offeringId"))
                        && transport.lastRequest.getData("query") == null,
                "roster must preserve exact target IDs and an optional filter");
        require(page.getTotalCount() == 30 && page.getPageNumber() == 1 && page.getPageSize() == 20,
                "roster totals and page coordinates must map");
        OfferingStudentView row = page.getItems().get(0);
        require("9007199254740997".equals(row.getEnrollmentId()) && !row.isRemovable()
                        && "成绩已发布".equals(row.getBlockedReason()) && "ENROLLED".equals(row.getEnrollmentStatus()),
                "exact enrollment IDs and grade-removal restrictions must map");
        transport.respond(response);
        require(service.listOfferingStudents(OFFERING_ID, "陈", 1, 20).join().size() == 1,
                "the original roster List adapter must remain usable");
        require("陈".equals(transport.lastRequest.getData("query")), "roster filter must travel");
    }

    private static void enrollmentPreviewMapsExactTargetsAndRiskSeverities() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("preview", wireShaped(new AdminEnrollmentPreviewDTO(
                OFFERING_ID, "20240031", enrollmentRisks()))));
        SocketAdminCourseService service = new SocketAdminCourseService(transport);
        AdminEnrollmentPreviewDTO preview = service.previewAdminEnrollment(OFFERING_ID, "20240031").join();
        requireEnvelope(transport, AdminCourseActions.PREVIEW_ADMIN_ENROLLMENT);
        require(OFFERING_ID.equals(transport.lastRequest.getData("offeringId"))
                        && "20240031".equals(transport.lastRequest.getData("studentUid")),
                "preview targets must be scalar exact strings");
        require(OFFERING_ID.equals(preview.getOfferingId()) && "20240031".equals(preview.getStudentUid())
                        && preview.getRisks().get(0).getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE
                        && preview.getRisks().get(1).getSeverity() == ScheduleConflictSeverityDTO.BLOCKING,
                "preview must deserialize enum risks rather than raw maps");
    }

    private static void enrollmentMutationsMapTypedHistoryRows() {
        FakeTransport transport = new FakeTransport();
        SocketAdminCourseService service = new SocketAdminCourseService(transport);
        AdminEnrollmentRequestDTO request = new AdminEnrollmentRequestDTO(
                "30000000-0000-0000-0000-000000000002", OFFERING_ID, "20240031", true, "  教务批准  ");
        for (boolean removal : new boolean[] {false, true}) {
            transport.respond(message -> message.putData("result", wireShaped(new AdminOperationResultDTO<>(
                    request.getOperationId(), "OK", removal ? "已移除" : "已添加",
                    enrollmentRow(removal ? "DROPPED" : "ENROLLED", !removal), enrollmentRisks()))));
            AdminOperationResultView<OfferingStudentView> result = removal
                    ? service.removeStudentFromOffering(request).join() : service.addStudentToOffering(request).join();
            requireEnvelope(transport, removal ? AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING
                    : AdminCourseActions.ADD_STUDENT_TO_OFFERING);
            require(transport.lastRequest.getData("request") == request
                            && transport.lastRequest.getData("uid") == null,
                    "enrollment DTO must travel under request with identity supplied only by token");
            require(request.getOperationId().equals(result.getOperationId())
                            && "OK".equals(result.getOutcomeCode())
                            && "9007199254740997".equals(result.getEntity().getEnrollmentId())
                            && (removal ? "DROPPED" : "ENROLLED").equals(result.getEntity().getEnrollmentStatus()),
                    "add/remove must map typed immutable history results");
        }
    }

    private static void enrollmentConflictsKeepTypedRisksAndLatestRow() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.CONFLICT);
            message.setMessage("需要确认风险");
            message.putData("latest", wireShaped(enrollmentRow("ENROLLED", false)));
            message.putData("conflicts", wireShaped(enrollmentRisks()));
        });
        SocketAdminCourseService service = new SocketAdminCourseService(transport);
        try {
            service.removeStudentFromOffering(new AdminEnrollmentRequestDTO(
                    "30000000-0000-0000-0000-000000000003", OFFERING_ID, "20240031", false, null)).join();
            throw new AssertionError("a conflict must fail the future");
        } catch (CompletionException failure) {
            require(failure.getCause() instanceof SocketAdminCourseService.AdminCourseServiceException,
                    "conflict must use the stable service exception");
            SocketAdminCourseService.AdminCourseServiceException error =
                    (SocketAdminCourseService.AdminCourseServiceException) failure.getCause();
            require(error.getCode() == MessageCode.CONFLICT && error.getLatest() instanceof OfferingStudentView latest
                            && "9007199254740997".equals(latest.getEnrollmentId()) && !latest.isRemovable(),
                    "latest must be an OfferingStudentView with exact ID and restrictions");
            require(error.getConflicts().size() == 2
                            && error.getConflicts().get(0).getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE
                            && error.getConflicts().get(1).getSeverity() == ScheduleConflictSeverityDTO.BLOCKING,
                    "dialogs must retain typed rejection risks");
            try {
                error.getConflicts().clear();
                throw new AssertionError("exception conflict snapshots must be immutable");
            } catch (UnsupportedOperationException expected) { }
        }
        require(new SocketAdminCourseService.AdminCourseServiceException(MessageCode.ERROR, "old").getConflicts().isEmpty()
                        && new SocketAdminCourseService.AdminCourseServiceException(MessageCode.CONFLICT, "old", "latest")
                                .getLatest().equals("latest"),
                "existing exception constructors must remain compatible");
    }

    private static void enrollmentPageRequiresServerMetadata() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> message.putData("students", List.of()));
        try {
            new SocketAdminCourseService(transport).searchStudentsPage("陈", 1, 20).join();
            throw new AssertionError("missing totals must not silently turn into a fabricated page");
        } catch (CompletionException failure) {
            require(failure.getCause() instanceof SocketAdminCourseService.AdminCourseServiceException error
                            && error.getCode() == MessageCode.ERROR,
                    "missing page metadata must become a stable service error");
        }
    }

    private static void adjustmentMethodsAreDeclaredInTheService() {
        declared("listAdjustmentRequestsPage", AdjustmentRequestStatusDTO.class, int.class, int.class);
        declared("getAdjustmentRequest", String.class);
        declared("reviewAdjustmentRequest", ApprovalDecisionRequestDTO.class);
    }

    private static void adjustmentListSendsFiltersAndMapsItsPage() {
        FakeTransport transport = new FakeTransport();
        SocketAdminCourseService service = new SocketAdminCourseService(transport);
        transport.respond(message -> {
            message.putData("adjustmentRequests", List.of(wireShaped(adjustmentSummary())));
            message.putData("totalCount", 1L);
            message.putData("pageNumber", 2);
            message.putData("pageSize", 20);
        });
        AdjustmentRequestPageDTO page = service.listAdjustmentRequestsPage(
                AdjustmentRequestStatusDTO.APPROVED, 2, 20).join();
        requireEnvelope(transport, AdminCourseActions.LIST_ADJUSTMENT_REQUESTS);
        require("APPROVED".equals(transport.lastRequest.getData("status"))
                        && Integer.valueOf(2).equals(transport.lastRequest.getData("pageNumber"))
                        && Integer.valueOf(20).equals(transport.lastRequest.getData("pageSize")),
                "the adjustment list must send the typed status and paging");
        require(page.getTotalCount() == 1L && page.getPageNumber() == 2 && page.getPageSize() == 20
                        && page.getItems().size() == 1,
                "the adjustment page must map the server metadata");
        require(REQUEST_ID.equals(page.getItems().get(0).getRequestId())
                        && page.getItems().get(0).getStatus() == AdjustmentRequestStatusDTO.APPROVED
                        && page.getItems().get(0).getTargetWeekCount() == 2,
                "the list adapter must keep the typed status and exact decimal request ID");

        FakeTransport unfiltered = new FakeTransport();
        unfiltered.respond(message -> {
            message.putData("adjustmentRequests", List.of());
            message.putData("totalCount", 0L);
            message.putData("pageNumber", 1);
            message.putData("pageSize", 20);
        });
        require(new SocketAdminCourseService(unfiltered)
                        .listAdjustmentRequests(null, 1, 20).join().isEmpty(),
                "the derived list accessor must reuse the page transport");
        requireEnvelope(unfiltered, AdminCourseActions.LIST_ADJUSTMENT_REQUESTS);
        require(unfiltered.lastRequest.getData("status") == null,
                "a null status must be omitted so the server keeps its PENDING default");
    }

    private static void adjustmentDetailAndDecisionMapTypedPayloads() {
        FakeTransport transport = new FakeTransport();
        SocketAdminCourseService service = new SocketAdminCourseService(transport);
        transport.respond(message -> message.putData("adjustmentRequest",
                wireShaped(adjustmentDetail())));
        AdjustmentRequestDetailDTO detail = service.getAdjustmentRequest(REQUEST_ID).join();
        requireEnvelope(transport, AdminCourseActions.GET_ADJUSTMENT_REQUEST);
        require(REQUEST_ID.equals(transport.lastRequest.getData("requestId")),
                "the detail read must send the exact decimal request ID");
        require(REQUEST_ID.equals(detail.getRequestId())
                        && detail.getStatus() == AdjustmentRequestStatusDTO.PENDING
                        && detail.getNewDayOfWeek() == 5 && detail.getNewStartPeriod() == 1
                        && detail.getNewEndPeriod() == 2
                        && detail.getTargets().size() == 1
                        && "8001".equals(detail.getTargets().get(0).getOriginalOccurrenceId())
                        && detail.getConflicts().size() == 1
                        && detail.getConflicts().get(0).getSeverity()
                        == ScheduleConflictSeverityDTO.OVERRIDABLE,
                "the detail must map typed targets and conflicts");

        ApprovalDecisionRequestDTO decision = new ApprovalDecisionRequestDTO(
                "40000000-0000-0000-0000-000000000001", REQUEST_ID, 3, true, true, "已协调教师", null);
        transport.respond(message -> message.putData("result", wireShaped(
                new AdminOperationResultDTO<>(decision.getOperationId(), "OK", "调课申请已通过",
                        adjustmentDetail(), List.of(adjustmentConflict())))));
        AdminOperationResultView<AdjustmentRequestDetailDTO> result =
                service.reviewAdjustmentRequest(decision).join();
        requireEnvelope(transport, AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST);
        require(transport.lastRequest.getData("request") == decision,
                "the decision must travel under request as the typed DTO instance");
        require(decision.getOperationId().equals(result.getOperationId())
                        && "OK".equals(result.getOutcomeCode())
                        && "调课申请已通过".equals(result.getMessage())
                        && result.getEntity() != null
                        && result.getEntity().getVersion() == 3,
                "a decision must map the typed operation result");
    }

    private static void adjustmentConflictKeepsTypedRisksAndLatestDetail() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.CONFLICT);
            message.setMessage("存在阻断性冲突，无法通过调课申请");
            message.putData("latest", wireShaped(adjustmentDetail()));
            message.putData("conflicts", wireShaped(List.of(adjustmentConflict())));
        });
        try {
            new SocketAdminCourseService(transport).reviewAdjustmentRequest(
                    new ApprovalDecisionRequestDTO("40000000-0000-0000-0000-000000000002",
                            REQUEST_ID, 3, true, false, null, null)).join();
            throw new AssertionError("a conflict must fail the future");
        } catch (CompletionException failure) {
            require(failure.getCause() instanceof SocketAdminCourseService.AdminCourseServiceException,
                    "a conflict must use the stable service exception");
            SocketAdminCourseService.AdminCourseServiceException error =
                    (SocketAdminCourseService.AdminCourseServiceException) failure.getCause();
            require(error.getCode() == MessageCode.CONFLICT
                            && error.getLatest() instanceof AdjustmentRequestDetailDTO latest
                            && REQUEST_ID.equals(latest.getRequestId()),
                    "the conflict must carry the latest typed detail for the dialog");
            require(error.getConflicts().size() == 1
                            && error.getConflicts().get(0).getSeverity()
                            == ScheduleConflictSeverityDTO.OVERRIDABLE,
                    "the dialog must retain the typed approval conflicts");
        }
    }

    private static void gradeMethodsAreDeclaredInTheService() {
        declared("listGradeSubmissionsPage", ApprovalStatusDTO.class, int.class, int.class);
        declared("getGradeSubmission", String.class);
        declared("reviewGradeSubmission", ApprovalDecisionRequestDTO.class);
    }

    private static void gradeListSendsFiltersAndMapsItsPage() {
        FakeTransport transport = new FakeTransport();
        SocketAdminCourseService service = new SocketAdminCourseService(transport);
        transport.respond(message -> {
            message.putData("gradeSubmissions", List.of(wireShaped(gradeSummary())));
            message.putData("totalCount", 1L);
            message.putData("pageNumber", 2);
            message.putData("pageSize", 20);
        });
        GradeSubmissionPageDTO page = service.listGradeSubmissionsPage(
                ApprovalStatusDTO.PENDING, 2, 20).join();
        requireEnvelope(transport, AdminCourseActions.LIST_GRADE_SUBMISSIONS);
        require("PENDING".equals(transport.lastRequest.getData("status"))
                        && Integer.valueOf(2).equals(transport.lastRequest.getData("pageNumber"))
                        && Integer.valueOf(20).equals(transport.lastRequest.getData("pageSize")),
                "the grade list must send the typed status and paging");
        require(page.getTotalCount() == 1L && page.getPageNumber() == 2 && page.getPageSize() == 20
                        && page.getItems().size() == 1,
                "the grade page must map the server metadata");
        require(GRADE_SUBMISSION_ID.equals(page.getItems().get(0).getSubmissionId())
                        && page.getItems().get(0).getStatus() == ApprovalStatusDTO.PENDING
                        && page.getItems().get(0).getStudentCount() == 2
                        && "张老师".equals(page.getItems().get(0).getTeacherName()),
                "the list adapter must keep the exact decimal submission ID and the summary fields");

        FakeTransport unfiltered = new FakeTransport();
        unfiltered.respond(message -> {
            message.putData("gradeSubmissions", List.of());
            message.putData("totalCount", 0L);
            message.putData("pageNumber", 1);
            message.putData("pageSize", 20);
        });
        require(new SocketAdminCourseService(unfiltered)
                        .listGradeSubmissions(null, 1, 20).join().isEmpty(),
                "the derived list accessor must reuse the page transport");
        requireEnvelope(unfiltered, AdminCourseActions.LIST_GRADE_SUBMISSIONS);
        require(unfiltered.lastRequest.getData("status") == null,
                "a null status must be omitted so the server keeps its PENDING default");
    }

    private static void gradeDetailAndDecisionMapTypedPayloads() {
        FakeTransport transport = new FakeTransport();
        SocketAdminCourseService service = new SocketAdminCourseService(transport);
        transport.respond(message -> message.putData("gradeSubmission",
                wireShaped(gradeDetail())));
        GradeSubmissionDetailDTO detail = service.getGradeSubmission(GRADE_SUBMISSION_ID).join();
        requireEnvelope(transport, AdminCourseActions.GET_GRADE_SUBMISSION);
        require(GRADE_SUBMISSION_ID.equals(transport.lastRequest.getData("submissionId")),
                "the detail read must send the exact decimal submission ID");
        require(GRADE_SUBMISSION_ID.equals(detail.getSummary().getSubmissionId())
                        && detail.getSummary().getStatus() == ApprovalStatusDTO.PENDING
                        && detail.getDistribution().size() == 5
                        && "90-100".equals(detail.getDistribution().get(0).getLabel())
                        && detail.getItems().size() == 2
                        && detail.getItems().get(0).getScore() == 85.0
                        && detail.getItems().get(0).getGradeLevel() == 3
                        && detail.getItems().get(0).getGradePoint() == 3.5
                        && detail.getItems().get(1).getScore() == null,
                "the detail must map typed distribution, items and a nullable score");

        ApprovalDecisionRequestDTO decision = new ApprovalDecisionRequestDTO(
                "40000000-0000-0000-0000-000000000001", GRADE_SUBMISSION_ID, 1, true, false, null,
                "同意");
        transport.respond(message -> message.putData("result", wireShaped(
                new AdminOperationResultDTO<>(decision.getOperationId(), "OK", "成绩提交已通过",
                        gradeDetail(), List.of()))));
        AdminOperationResultView<GradeSubmissionDetailDTO> result =
                service.reviewGradeSubmission(decision).join();
        requireEnvelope(transport, AdminCourseActions.REVIEW_GRADE_SUBMISSION);
        require(transport.lastRequest.getData("request") == decision,
                "the decision must travel under request as the typed DTO instance");
        require(decision.getOperationId().equals(result.getOperationId())
                        && "OK".equals(result.getOutcomeCode())
                        && "成绩提交已通过".equals(result.getMessage())
                        && result.getEntity() != null
                        && result.getEntity().getSummary().getSubmissionId()
                                .equals(GRADE_SUBMISSION_ID),
                "a decision must map the typed operation result");
    }

    private static void gradeConflictKeepsTypedLatestDetail() {
        FakeTransport transport = new FakeTransport();
        transport.respond(message -> {
            message.setCode(MessageCode.CONFLICT);
            message.setMessage("成绩提交已被处理，请刷新后重试");
            message.putData("latest", wireShaped(gradeDetail()));
        });
        try {
            new SocketAdminCourseService(transport).reviewGradeSubmission(
                    new ApprovalDecisionRequestDTO("40000000-0000-0000-0000-000000000002",
                            GRADE_SUBMISSION_ID, 1, true, false, null, null)).join();
            throw new AssertionError("a conflict must fail the future");
        } catch (CompletionException failure) {
            require(failure.getCause() instanceof SocketAdminCourseService.AdminCourseServiceException,
                    "a conflict must use the stable service exception");
            SocketAdminCourseService.AdminCourseServiceException error =
                    (SocketAdminCourseService.AdminCourseServiceException) failure.getCause();
            require(error.getCode() == MessageCode.CONFLICT
                            && error.getLatest() instanceof GradeSubmissionDetailDTO latest
                            && GRADE_SUBMISSION_ID.equals(latest.getSummary().getSubmissionId()),
                    "the conflict must carry the latest typed detail for the dialog");
            require(error.getConflicts().isEmpty(),
                    "grade approval carries no typed conflicts");
        }
    }

    private static GradeSubmissionSummaryDTO gradeSummary() {
        return new GradeSubmissionSummaryDTO(GRADE_SUBMISSION_ID, "1001", "数据结构", "OFF-1001", 1,
                "T1001", "张老师", 2, 85.0, 90.0, 80.0, 0, ApprovalStatusDTO.PENDING,
                "2026-09-10T02:00:00Z");
    }

    private static GradeSubmissionDetailDTO gradeDetail() {
        List<GradeDistributionBucketDTO> distribution = List.of(
                new GradeDistributionBucketDTO("90-100", 1),
                new GradeDistributionBucketDTO("80-89", 1),
                new GradeDistributionBucketDTO("70-79", 0),
                new GradeDistributionBucketDTO("60-69", 0),
                new GradeDistributionBucketDTO("0-59", 0));
        List<GradeSubmissionItemDTO> items = List.of(
                new GradeSubmissionItemDTO("8001", "20240031", "陈晨", 88.0, 86.0, null, 84.0, 85.0,
                        3, 3.5),
                new GradeSubmissionItemDTO("8002", "20240032", "林晓", null, null, null, null, null,
                        null, null));
        return new GradeSubmissionDetailDTO(gradeSummary(), distribution, items, null, null, null);
    }

    private static AdjustmentRequestSummaryDTO adjustmentSummary() {
        return new AdjustmentRequestSummaryDTO(REQUEST_ID, "数据结构", "OFF-1001", "T1001", "张老师",
                2, AdjustmentRequestStatusDTO.APPROVED, "2026-09-10T02:00:00Z");
    }

    private static AdjustmentRequestDetailDTO adjustmentDetail() {
        return new AdjustmentRequestDetailDTO(REQUEST_ID, "2001", "T1001", "临时调课",
                AdjustmentRequestStatusDTO.PENDING, 3, 5, 1, 2,
                new ScheduleResourceDTO("T2001", "T2001", "李老师", "teacher", 0), null,
                new ScheduleResourceDTO("3001", "3001", "A-101", "classroom", 120),
                List.of(new AdjustmentTargetDTO("8001", 1, "2026-09-08T00:00:00Z",
                        "2026-09-08T01:35:00Z", "张老师", null, "A-101")),
                List.of(adjustmentConflict()), "2026-09-10T02:00:00Z", null, null, null);
    }

    private static ScheduleConflictDTO adjustmentConflict() {
        return new ScheduleConflictDTO("TEACHER_OVERLAP", ScheduleConflictSeverityDTO.OVERRIDABLE,
                "T2001", "2001", 1, 5, 1, 2, "任课教师在该时间已有其他课程");
    }

    private static OfferingStudentDTO enrollmentRow(String status, boolean removable) {
        return new OfferingStudentDTO("9007199254740997", "20240031", "陈晨", "软件工程", 2024,
                status, removable, removable ? null : "成绩已发布");
    }

    private static List<ScheduleConflictDTO> enrollmentRisks() {
        return List.of(new ScheduleConflictDTO("CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE,
                        "20240031", OFFERING_ID, 0, 0, 0, 0, "人数已满"),
                new ScheduleConflictDTO("GRADE_WORKFLOW_LOCKED", ScheduleConflictSeverityDTO.BLOCKING,
                        "20240031", OFFERING_ID, 0, 0, 0, 0, "成绩已发布"));
    }

    private static ScheduleConflictDTO conflict() {
        return new ScheduleConflictDTO("TEACHER", ScheduleConflictSeverityDTO.OVERRIDABLE,
                "8001", "2001", 1, 1, 1, 2, "教师时间冲突");
    }

    private static SchedulePlanDTO planDto() {
        return new SchedulePlanDTO("7001", "2026 秋排课方案", 1, "DRAFT", false,
                List.of(new ScheduleConflictDTO("TEACHER", ScheduleConflictSeverityDTO.BLOCKING,
                        "8001", "2001", 1, 1, 1, 2, "教师时间冲突")));
    }

    private static ScheduleArrangementDTO arrangementDto() {
        List<ScheduleSlotDTO> slots = new ArrayList<>();
        slots.add(new ScheduleSlotDTO(1, 1, 2));
        slots.add(new ScheduleSlotDTO(3, 3, 4));
        return new ScheduleArrangementDTO("9001", "7001", "1001", resource(), null,
                new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120), slots,
                1, 16, "DRAFT", 3);
    }

    private static SaveArrangementRequestDTO arrangementRequest(String operationId,
            String arrangementId, int expectedVersion, boolean force, String reason) {
        List<ScheduleSlotDTO> slots = new ArrayList<>();
        slots.add(new ScheduleSlotDTO(1, 1, 2));
        slots.add(new ScheduleSlotDTO(3, 3, 4));
        return new SaveArrangementRequestDTO(operationId, arrangementId, expectedVersion,
                "7001", "1001", "T1001", null, "3001", slots, 1, 16, force, reason);
    }

    private static AdminOperationResultDTO<ScheduleArrangementDTO> arrangementResult(
            String operationId) {
        return new AdminOperationResultDTO<>(operationId, "OK", "教学安排已保存",
                arrangementDto(), List.of(conflict()));
    }

    private static AdminOperationResultDTO<SchedulePlanDTO> planResult() {
        return new AdminOperationResultDTO<>("op-publish", "OK", "排课方案已发布",
                new SchedulePlanDTO("7001", "2026 秋排课方案", 2, "PUBLISHED", true, List.of()),
                List.of());
    }

    private static AdminOperationResultDTO<Void> voidResult() {
        return new AdminOperationResultDTO<>("op-delete", "OK", "教学安排已删除", null, List.of());
    }

    private static void requireResult(AdminOperationResultView<AdminCourseView> result,
            String operationId, String message, String courseId, int version) {
        require(operationId.equals(result.getOperationId()),
                "operationId must map through the result");
        require("OK".equals(result.getOutcomeCode()), "outcomeCode must map through the result");
        require(message.equals(result.getMessage()), "message must map through the result");
        require(result.getEntity() != null, "a course result must carry its entity");
        require(courseId.equals(result.getEntity().getCourseId()),
                "the entity course ID must stay the exact decimal string");
        require(result.getEntity().getVersion() == version,
                "the entity version must map through the result");
    }

    private static void requireOfferingResult(AdminOperationResultView<AdminOfferingView> result) {
        require("op-offer".equals(result.getOperationId())
                        && "OK".equals(result.getOutcomeCode())
                        && "教学班已创建".equals(result.getMessage()),
                "offering result fields must map");
        require(result.getEntity() != null
                        && OFFERING_ID.equals(result.getEntity().getOfferingId()),
                "offering result entity must map");
    }

    private static void requireEnvelope(FakeTransport transport, String action) {
        Message request = transport.lastRequest;
        require(request.getType() == MessageType.REQUEST, "admin request must be REQUEST");
        require("courseAdmin".equals(request.getModule()), "module must be courseAdmin");
        require(action.equals(request.getAction()), "action must be " + action);
        require(TOKEN.equals(request.getToken()),
                "the token must be attached explicitly from ClientSession");
        require(request.getCode() == MessageCode.SUCCESS, "request code must be SUCCESS");
    }

    private static AdminCourseDTO courseDto() {
        return new AdminCourseDTO(COURSE_ID, "CS203", "数据结构", "必修", 4.0, 64,
                "线性表、树和图", "程序设计基础", true, true, "ACTIVE", 2, 3);
    }

    private static CourseEditorRequestDTO courseRequest(
            String operationId, String courseId, int expectedVersion) {
        return new CourseEditorRequestDTO(operationId, courseId, expectedVersion, "CS203",
                "数据结构", "必修", 4.0, 64, "线性表、树和图", "程序设计基础", true, true);
    }

    private static AdminOperationResultDTO<AdminCourseDTO> courseResult() {
        return new AdminOperationResultDTO<>("op-create", "OK", "课程已创建", courseDto(),
                List.of());
    }

    private static AdminOfferingDTO offeringDto() {
        return new AdminOfferingDTO(OFFERING_ID, "OFF-1", COURSE_ID, 2026, 1, 120, 30,
                "OPEN", "T1", "张老师", null, null, "SCHEDULED", 4);
    }

    private static OfferingEditorRequestDTO offeringRequest(
            String operationId, String offeringId, int expectedVersion) {
        return new OfferingEditorRequestDTO(operationId, offeringId, expectedVersion,
                COURSE_ID, "OFF-1", 2026, 1, 120, "T1", null, 2);
    }

    private static AdminOperationResultDTO<AdminOfferingDTO> offeringResult() {
        return new AdminOperationResultDTO<>("op-offer", "OK", "教学班已创建", offeringDto(),
                List.of());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeTransport implements AdminCourseTransport {
        private final Deque<Consumer<Message>> responseBuilders = new ArrayDeque<>();
        private Message lastRequest;
        private boolean respondNull;

        private void respond(Consumer<Message> builder) {
            responseBuilders.addLast(builder);
        }

        private void respondNull() {
            respondNull = true;
        }

        @Override
        public CompletableFuture<Message> send(Message request) {
            lastRequest = request;
            if (respondNull) return CompletableFuture.completedFuture(null);
            Message response = new Message(MessageType.RESPONSE, "courseAdmin",
                    request.getAction());
            response.setUID(request.getUID());
            if (responseBuilders.isEmpty()) {
                response.setCode(MessageCode.SUCCESS);
            } else {
                responseBuilders.removeFirst().accept(response);
            }
            return CompletableFuture.completedFuture(response);
        }
    }
}
