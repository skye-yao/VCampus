package controller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import javafx.scene.control.ButtonType;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import model.course.CourseTermView;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import protocol.MessageCode;
import service.AdminCourseService;
import service.SocketAdminCourseService.AdminCourseServiceException;

/**
 * 无 JavaFX 依赖的课程目录控制器测试：注入假服务与 {@code Runnable::run} 的 FX 执行器。
 */
public final class AdminCourseCatalogControllerTest {
    private static final String CONFLICT_MESSAGE = "数据已被其他管理员修改";
    private static final String ARCHIVE_KEY = "course:101:archive";

    public static void main(String[] args) {
        testQueryAndStatusPassedUnchangedAndPreservedOnRefresh();
        testStatusAllIsSentAsNoFilter();
        testStaleGenerationCannotReplaceNewerResultOrErrorState();
        testListLoadFailurePreservesDisplayedRows();
        testWriteControlDisabledWhilePendingAndReenabledOnFailure();
        testSuccessfulWriteReloadsAuthoritativeData();
        testConflictReportsExactMessageReloadsAndDiscardsLatest();
        testCancelledDestructiveConfirmationDoesNotCallService();
        testDistinctWriteIntentsUseDistinctOperationIds();
        testDisplayedCoursesAreImmutableCopies();
        testTermFilterDrivesBothLoads();
        testOfferingTermTextMatchesThePicker();
        System.out.println("AdminCourseCatalogControllerTest: PASS");
    }

    private static void testQueryAndStatusPassedUnchangedAndPreservedOnRefresh() {
        ControlledService service = new ControlledService();
        service.setAuthoritative(List.of(course("101", "CS203", "数据结构", "ARCHIVED")));
        Recorder recorder = new Recorder();
        AdminCourseCatalogController controller = controller(service, recorder);

        controller.applyFilters("CS", "ARCHIVED");
        require(service.listCalls.size() == 1, "applying filters must trigger one load");
        require("CS|ARCHIVED|null|null".equals(service.listCalls.get(0)),
                "the selected query and status must reach the service unchanged, saw "
                        + service.listCalls);
        require(controller.courses().size() == 1, "the loaded catalog must be rendered");

        controller.refresh();
        require(service.listCalls.size() == 2, "refresh must issue a new load");
        require("CS|ARCHIVED|null|null".equals(service.listCalls.get(1)),
                "refresh must preserve the current query and status, saw " + service.listCalls);
        require("CS".equals(controller.query()), "query selection must be preserved");
        require("ARCHIVED".equals(controller.status()), "status selection must be preserved");
    }

    private static void testStatusAllIsSentAsNoFilter() {
        ControlledService service = new ControlledService();
        AdminCourseCatalogController controller = controller(service, new Recorder());
        controller.applyFilters("", "全部");
        require(service.listCalls.size() == 1, "applying 全部 must trigger one load");
        require("null|null|null|null".equals(service.listCalls.get(0)),
                "全部 and a blank query must be sent as unfiltered, saw " + service.listCalls);
    }

    private static void testStaleGenerationCannotReplaceNewerResultOrErrorState() {
        ControlledService service = new ControlledService();
        Recorder recorder = new Recorder();
        AdminCourseCatalogController controller = controller(service, recorder);

        CompletableFuture<List<AdminCourseView>> older = new CompletableFuture<>();
        CompletableFuture<List<AdminCourseView>> newer = new CompletableFuture<>();
        service.enqueueCourseResult(older);
        service.enqueueCourseResult(newer);
        controller.refresh();
        controller.refresh();
        require(controller.isLoading(), "two pending loads must keep the loading state");
        newer.complete(List.of(course("201", "CS301", "操作系统", "ACTIVE")));
        require(!controller.isLoading(), "the newest load must clear the loading state");
        require("CS301".equals(controller.courses().get(0).getCourseCode()),
                "the newest load must be displayed");
        older.complete(List.of(course("101", "CS203", "过期结果", "ACTIVE")));
        require("CS301".equals(controller.courses().get(0).getCourseCode()),
                "a stale success must not replace the newest result");

        CompletableFuture<List<AdminCourseView>> staleFailure = new CompletableFuture<>();
        CompletableFuture<List<AdminCourseView>> latest = new CompletableFuture<>();
        service.enqueueCourseResult(staleFailure);
        service.enqueueCourseResult(latest);
        controller.refresh();
        controller.refresh();
        latest.complete(List.of(course("301", "CS352", "人机交互", "ACTIVE")));
        staleFailure.completeExceptionally(new IllegalStateException("older request failed"));
        require("CS352".equals(controller.courses().get(0).getCourseCode()),
                "a stale failure must not replace the newest result");
        require(controller.errorText() == null,
                "a stale failure must not raise the error state, saw " + controller.errorText());
        require(!controller.isLoading(), "a stale failure must not keep the loading state");
        require(recorder.messages.isEmpty(),
                "a stale failure must not be reported to the user, saw " + recorder.messages);
    }

    /**
     * 刷新失败必须保留已经显示的课程行，并保留可重试的查询与状态。
     */
    private static void testListLoadFailurePreservesDisplayedRows() {
        ControlledService service = new ControlledService();
        service.setAuthoritative(List.of(course("101", "CS203", "数据结构", "ACTIVE")));
        Recorder recorder = new Recorder();
        AdminCourseCatalogController controller = controller(service, recorder);
        controller.applyFilters("CS", "ACTIVE");
        require(controller.courses().size() == 1,
                "the successful load must be displayed before the failure");

        List<AdminCourseView> displayed = controller.courses();
        int loadsBeforeFailure = service.listCalls.size();
        CompletableFuture<List<AdminCourseView>> failing = new CompletableFuture<>();
        service.enqueueCourseResult(failing);
        controller.refresh();
        require(service.listCalls.size() == loadsBeforeFailure + 1,
                "the failing refresh must issue exactly one new load");
        require(controller.isLoading(), "a pending refresh must report loading");

        failing.completeExceptionally(new IllegalStateException("network down"));

        require(!controller.isLoading(), "a failed refresh must clear the loading state");
        require("课程加载失败，请重试".equals(controller.errorText()),
                "a failed refresh must show the retryable error, saw " + controller.errorText());
        require(controller.courses() == displayed,
                "a failed refresh must keep the identical displayed snapshot, saw "
                        + controller.courses());
        require(controller.courses().size() == 1
                        && "101".equals(controller.courses().get(0).getCourseId()),
                "a failed refresh must keep the displayed rows, saw " + controller.courses());
        require("CS".equals(controller.query()),
                "a failed refresh must keep the query selection, saw " + controller.query());
        require("ACTIVE".equals(controller.status()),
                "a failed refresh must keep the status selection, saw " + controller.status());
        require(recorder.messages.isEmpty(),
                "a list load failure is inline only and must not raise an alert, saw "
                        + recorder.messages);

        service.setAuthoritative(List.of(course("101", "CS203", "数据结构", "ACTIVE"),
                course("201", "CS301", "操作系统", "ACTIVE")));
        controller.refresh();
        require("CS|ACTIVE|null|null".equals(service.listCalls.get(service.listCalls.size() - 1)),
                "retry must reuse the preserved query and status, saw " + service.listCalls);
        require(controller.errorText() == null,
                "a successful retry must clear the error state, saw " + controller.errorText());
        require(controller.courses().size() == 2,
                "a successful retry must render the recovered rows");
    }

    private static void testWriteControlDisabledWhilePendingAndReenabledOnFailure() {
        ControlledService service = new ControlledService();
        service.setAuthoritative(List.of(course("101", "CS203", "数据结构", "ACTIVE")));
        Recorder recorder = new Recorder();
        AdminCourseCatalogController controller = controller(service, recorder);
        controller.applyFilters("", "全部");

        AtomicBoolean disabled = new AtomicBoolean(false);
        controller.registerWriteControl(ARCHIVE_KEY, disabled::set);
        require(!disabled.get(), "a registered idle control must start enabled");

        AtomicReference<String> operationId = new AtomicReference<>();
        CompletableFuture<AdminOperationResultView<AdminCourseView>> pending =
                new CompletableFuture<>();
        controller.executeWrite(ARCHIVE_KEY, id -> {
            operationId.set(id);
            return pending;
        }, null);
        require(disabled.get(), "the initiating control must be disabled while pending");
        require(controller.isWritePending(ARCHIVE_KEY), "the write must be tracked as pending");
        UUID.fromString(operationId.get());

        AtomicReference<String> duplicate = new AtomicReference<>();
        controller.executeWrite(ARCHIVE_KEY, id -> {
            duplicate.set(id);
            return new CompletableFuture<>();
        }, null);
        require(duplicate.get() == null, "a duplicate click must not start a second write");

        pending.completeExceptionally(new IllegalStateException("network down"));
        require(!disabled.get(), "an ordinary failure must re-enable the control");
        require(!controller.isWritePending(ARCHIVE_KEY), "a finished write must not stay pending");
        require("101".equals(controller.courses().get(0).getCourseId()),
                "an ordinary failure must retain the displayed rows");
        require(recorder.lastMessage().startsWith("network down"),
                "an ordinary failure must surface a stable error, saw " + recorder.lastMessage());
        require(!CONFLICT_MESSAGE.equals(recorder.lastMessage()),
                "an ordinary failure must not report a conflict");
    }

    private static void testSuccessfulWriteReloadsAuthoritativeData() {
        ControlledService service = new ControlledService();
        service.setAuthoritative(List.of(course("101", "CS203", "数据结构", "ACTIVE")));
        AdminCourseCatalogController controller = controller(service, new Recorder());
        controller.applyFilters("", "全部");
        int loads = service.listCalls.size();

        controller.executeWrite(ARCHIVE_KEY,
                id -> CompletableFuture.completedFuture(null), null);

        require(service.listCalls.size() == loads + 1,
                "a successful write must reload the authoritative list");
        require(controller.courses().size() == 1, "the reloaded list must be rendered");
        require(!controller.isWritePending(ARCHIVE_KEY), "a successful write must not stay pending");
    }

    private static void testConflictReportsExactMessageReloadsAndDiscardsLatest() {
        ControlledService service = new ControlledService();
        service.setAuthoritative(List.of(course("101", "CS203", "数据结构", "ARCHIVED")));
        Recorder recorder = new Recorder();
        AdminCourseCatalogController controller = controller(service, recorder);
        controller.applyFilters("CS", "ARCHIVED");
        int loads = service.listCalls.size();

        CompletableFuture<AdminOperationResultView<AdminCourseView>> pending =
                new CompletableFuture<>();
        controller.executeWrite("course:101:update", id -> pending, null);
        AdminCourseView latest = course("999", "CS999", "他人修改", "ACTIVE");
        pending.completeExceptionally(new CompletionException(
                new AdminCourseServiceException(MessageCode.CONFLICT, "版本冲突", latest)));

        require(CONFLICT_MESSAGE.equals(recorder.lastMessage()),
                "a conflict must report exactly " + CONFLICT_MESSAGE + ", saw "
                        + recorder.lastMessage());
        require(service.listCalls.size() == loads + 1,
                "a conflict must trigger an authoritative reload");
        require("CS|ARCHIVED|null|null".equals(service.listCalls.get(service.listCalls.size() - 1)),
                "a conflict reload must preserve the filters, saw " + service.listCalls);
        require(controller.courses().size() == 1,
                "the authoritative reload must replace the displayed rows");
        require("101".equals(controller.courses().get(0).getCourseId()),
                "a conflict must not install exception.latest as local state");
        require(!"999".equals(controller.courses().get(0).getCourseId()),
                "a conflict must discard the server latest entity");
        require(!controller.isWritePending("course:101:update"),
                "a conflicting write must not stay pending");
    }

    private static void testCancelledDestructiveConfirmationDoesNotCallService() {
        List<String> confirmations = new ArrayList<>();
        List<String> mutations = new ArrayList<>();
        ControlledService service = new ControlledService();
        AdminCourseCatalogController controller = new AdminCourseCatalogController(
                service, (title, message) -> {
                    confirmations.add(title + "|" + message);
                    return ButtonType.CANCEL;
                }, new Recorder()::accept, Runnable::run);

        controller.executeDestructiveWrite(ARCHIVE_KEY, "归档课程", "确认归档“数据结构”？",
                id -> {
                    mutations.add("archive");
                    return CompletableFuture.completedFuture(null);
                });

        require(confirmations.size() == 1, "a destructive action must ask for confirmation");
        require(mutations.isEmpty(),
                "a cancelled confirmation must not call the service");
        require(!controller.isWritePending(ARCHIVE_KEY),
                "a cancelled confirmation must not leave a pending write");

        List<String> accepted = new ArrayList<>();
        List<String> ordinaryConfirmations = new ArrayList<>();
        AdminCourseCatalogController agreeing = new AdminCourseCatalogController(
                service, (title, message) -> {
                    ordinaryConfirmations.add(title);
                    return ButtonType.OK;
                }, new Recorder()::accept, Runnable::run);
        agreeing.executeDestructiveWrite(ARCHIVE_KEY, "归档课程", "确认归档？", id -> {
            accepted.add("archive");
            return CompletableFuture.completedFuture(null);
        });
        require(accepted.size() == 1, "a confirmed destructive action must call the service");

        agreeing.executeWrite("course:101:restore",
                id -> CompletableFuture.completedFuture(null), null);
        require(ordinaryConfirmations.size() == 1,
                "restore must not require a destructive confirmation");
    }

    private static void testDistinctWriteIntentsUseDistinctOperationIds() {
        ControlledService service = new ControlledService();
        AdminCourseCatalogController controller = controller(service, new Recorder());
        AtomicReference<String> archiveId = new AtomicReference<>();
        AtomicReference<String> cancelId = new AtomicReference<>();
        controller.executeWrite(ARCHIVE_KEY,
                id -> {
                    archiveId.set(id);
                    return CompletableFuture.completedFuture(null);
                }, null);
        controller.executeWrite("offering:1001:cancel",
                id -> {
                    cancelId.set(id);
                    return CompletableFuture.completedFuture(null);
                }, null);
        UUID.fromString(archiveId.get());
        UUID.fromString(cancelId.get());
        require(!archiveId.get().equals(cancelId.get()),
                "each distinct write intent must use a new operation id");
    }

    private static void testDisplayedCoursesAreImmutableCopies() {
        ControlledService service = new ControlledService();
        service.setAuthoritative(List.of(course("101", "CS203", "数据结构", "ACTIVE")));
        AdminCourseCatalogController controller = controller(service, new Recorder());
        controller.applyFilters("", "全部");
        try {
            controller.courses().add(course("202", "CS204", "编译原理", "ACTIVE"));
            throw new AssertionError("displayed courses must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    /**
     * 学期下拉的选项来自服务端回显的 displayName；下拉变化必须带着学期重新加载。
     */
    private static void testTermFilterDrivesBothLoads() {
        ControlledService service = new ControlledService();
        service.setTerms(List.of(
                new CourseTermView(2027, 3, "2027-2028 春学期"),
                new CourseTermView(2026, 2, "2026-2027 秋学期")));
        AdminCourseCatalogController controller = controller(service, new Recorder());
        controller.loadTerms();

        require(service.courseCalls.contains("null|null|2027|3"),
                "the newest term must be selected by default, saw " + service.courseCalls);
        require(controller.terms().size() == 2, "both terms must be offered");

        controller.selectTerm(1);
        require(service.courseCalls.contains("null|null|2026|2"),
                "selecting a term must reload the course list with that term, saw "
                        + service.courseCalls);
    }

    /**
     * 教学班行上的学期文案必须与下拉项同源，否则同一屏上会出现两种写法。
     */
    private static void testOfferingTermTextMatchesThePicker() {
        require("2026-2027 秋学期".equals(
                        AdminCourseCatalogController.termText(offering(2026, 2))),
                "the offering row must use the same wording as the term picker");
        require("2026-2027 暑期学校".equals(
                        AdminCourseCatalogController.termText(offering(2026, 1))),
                "the row must degrade with the shared labels, not a second spelling");
    }

    private static AdminCourseCatalogController controller(
            ControlledService service, Recorder recorder) {
        return new AdminCourseCatalogController(
                service, (title, message) -> ButtonType.OK, recorder::accept, Runnable::run);
    }

    private static AdminCourseView course(String id, String code, String name, String status) {
        return new AdminCourseView(id, code, name, "必修", 3.0, 48, "简介", "无",
                true, true, status, 0, 1);
    }

    /** 学期文案只取决于学年与学期，其余字段填占位值即可。 */
    private static AdminOfferingView offering(int academicYear, int semester) {
        return new AdminOfferingView("1001", "CS203-01", "101", academicYear, semester,
                60, 0, "OPEN", null, null, null, null, "UNSCHEDULED", 1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Recorder {
        private final List<String> messages = new ArrayList<>();

        private void accept(String title, String message) {
            messages.add(message);
        }

        private String lastMessage() {
            return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        }
    }

    private static final class ControlledService implements AdminCourseService {
        private final Deque<CompletableFuture<List<AdminCourseView>>> courseResults =
                new ArrayDeque<>();
        private final List<String> listCalls = new ArrayList<>();
        private final List<String> writeCalls = new ArrayList<>();
        private List<AdminCourseView> authoritative = List.of();

        private List<CourseTermView> terms = List.of();
        final List<String> courseCalls = new ArrayList<>();

        void setTerms(List<CourseTermView> next) {
            terms = List.copyOf(next);
        }

        @Override
        public CompletableFuture<List<CourseTermView>> listOfferingTerms() {
            return CompletableFuture.completedFuture(terms);
        }

        private void setAuthoritative(List<AdminCourseView> courses) {
            authoritative = List.copyOf(courses);
        }

        private void enqueueCourseResult(CompletableFuture<List<AdminCourseView>> result) {
            courseResults.addLast(result);
        }

        /** 接口上的两参方法仍是抽象方法，所以假服务必须继续实现它；它表达的就是"不限定学期"。 */
        @Override
        public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status) {
            return listCourses(query, status, null, null);
        }

        /**
         * 学期由新签名承载，服务端回显的学期会出现在这里；两参/四参两条路径共用这一份记录，
         * 断言里的第四段是学期而不是另一次调用。
         */
        @Override
        public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status,
                Integer academicYear, Integer semester) {
            listCalls.add(query + "|" + status + "|" + academicYear + "|" + semester);
            courseCalls.add(query + "|" + status + "|" + academicYear + "|" + semester);
            if (!courseResults.isEmpty()) return courseResults.removeFirst();
            return CompletableFuture.completedFuture(authoritative);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(
                CourseEditorRequestDTO request) {
            writeCalls.add("createCourse");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(
                CourseEditorRequestDTO request) {
            writeCalls.add("updateCourse");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
                String courseId, int expectedVersion, String operationId) {
            writeCalls.add("archiveCourse:" + courseId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
                String courseId, int expectedVersion, String operationId) {
            writeCalls.add("restoreCourse:" + courseId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId) {
            return listOfferings(courseId, null, null);
        }

        @Override
        public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId,
                Integer academicYear, Integer semester) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> createOffering(
                OfferingEditorRequestDTO request) {
            writeCalls.add("createOffering");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> updateOffering(
                OfferingEditorRequestDTO request) {
            writeCalls.add("updateOffering");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> cancelOffering(
                String offeringId, int expectedVersion, String operationId) {
            writeCalls.add("cancelOffering:" + offeringId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
                String offeringId, int expectedVersion, String operationId) {
            writeCalls.add("deleteDraftOffering:" + offeringId);
            return CompletableFuture.completedFuture(null);
        }
    }
}
