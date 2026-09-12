package service;

import com.google.gson.Gson;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.catalog.AdminCourseDTO;
import dto.course.admin.catalog.AdminOfferingDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.ClientSession;

public final class SocketAdminCourseServiceTest {
    private static final String TOKEN = "token-123";
    private static final String COURSE_ID = "9007199254740993";
    private static final String OFFERING_ID = "9007199254740995";

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
