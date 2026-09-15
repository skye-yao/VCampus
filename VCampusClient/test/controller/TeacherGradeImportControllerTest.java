package controller;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.teacher.ConfirmGradeImportRequestDTO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeImportCorrectionDTO;
import dto.course.teacher.GradeImportPreviewDTO;
import dto.course.teacher.GradeImportRowIssueDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.PreviewGradeImportRequestDTO;
import dto.course.teacher.ReviseGradeImportRequestDTO;
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
import dto.course.teacher.WriteGradeBookRequestDTO;
import javafx.event.ActionEvent;
import model.course.teacher.GradeBookEditorModel;
import model.course.teacher.GradeBookEditorModel.Row;
import protocol.MessageCode;
import service.MockTeacherCourseService;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;
import service.TeacherFileTransport;

/**
 * Excel 导入在成绩表里的预览、纠错与取消（设计 §9）——无工具包测试。
 *
 * <p>覆盖最容易悄悄做错的那几件事：导入预览用的是**同一张成绩表**（行对象不换、不新建表格）；
 * 取消导入必须把导入前的编辑副本连同 {@code dirty} 标志一起恢复；修正必须调用 revise 而不是本地
 * 删红框；迟到的旧预览不能覆盖新状态；确认按钮取最新服务端预览的有效性；确认导入绝不触发提交审批；
 * 离开上传页要取消在途 Future（传输层据此关闭短连接）。
 */
public final class TeacherGradeImportControllerTest {
    private static final String OFFERING = "9007199254740993";
    /** 另一个教学班：用来验证页面被复用（离开 A、打开 B）时迟到的响应落在谁身上。 */
    private static final String OTHER_OFFERING = "9007199254740995";
    private static final String TOKEN = "import-token-1";
    private static final String UPLOAD_TICKET = "upload-ticket-1";
    private static final String BOOK_VIEW = "/resources/fxml/TeacherGradeBookView.fxml";
    private static final String FEEDBACK_VIEW = "/resources/fxml/TeacherGradeImportFeedback.fxml";
    private static final String DETAIL_VIEW = "/resources/fxml/TeacherOfferingDetailView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";

    private TeacherGradeImportControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        previewRendersInTheSameTableAndKeepsUnimportedColumns();
        dirtyEditCopyIsRestoredOnCancelIncludingTheDirtyFlag();
        issueCellsGoThroughReviseAndALateResponseIsDiscarded();
        unknownStudentRowsAreResolvedByExplicitExclusion();
        confirmUsesTheLatestServerPreviewAndNeverSubmits();
        confirmConflictKeepsThePreviewAndTheEditingCopy();
        leavingDuringImportCancelsTheTransferAndRestoresTheCopy();
        leavingBeforeTheUploadIsDispatchedSpendsNoTicket();
        rapidCorrectionsAreCoalescedAndAlwaysUseTheFreshPreviewRevision();
        feedbackShowsCountsAndAbnormalNamesWithoutTheImportToken();
        downloadsChooseTheFileOnTheCallingThreadAndTransferInBackground();
        bottomBarFreezesTheSchemeAndSwapsItsButtons();
        exportFeedbackNeverLandsOnAnotherOffering();
        mockPreviewRevisionsIncrementAndRejectStaleOnes();
        importViewsDeclareTheirControllerIdsHandlersAndStyles();
        System.out.println("TeacherGradeImportControllerTest: PASS");
    }

    // ------------------------------------------------------------------ 同表预览

    /**
     * 预览就在原成绩表里：行对象不换（还是同一批 {@code Row}），导入进来的分数被合并进这些行，
     * 服务端报的问题长在对应格子上，异常学生的姓名在反馈文案里。候选里没有的组成保留原分数——
     * 文件缺列/空白绝不擦掉已有成绩。
     */
    private static void previewRendersInTheSameTableAndKeepsUnimportedColumns() {
        ImportService service = new ImportService();
        FakeDialogs dialogs = new FakeDialogs();
        TeacherGradeBookController controller = controller(service, dialogs);
        controller.showOffering(OFFERING);

        List<Row> before = controller.rows();
        Row target = before.get(0);
        String enrollmentId = target.enrollmentId();
        String dailyBefore = target.cell(GradeComponentCodeDTO.DAILY).text();
        String midtermBefore = target.cell(GradeComponentCodeDTO.MIDTERM).text();
        int weightBefore = controller.model().column(GradeComponentCodeDTO.DAILY).weightBasisPoints();
        require(!dailyBefore.isEmpty(), "夹具必须已经有一格平时成绩可用来验证合并");

        service.previewResponse = preview(1, candidateFor(controller, enrollmentId,
                        new GradeScoresDTO(new BigDecimal("95"), null,
                                new BigDecimal("66"), null)),
                2, 1, List.of(issue(3, target.studentUid(), target.studentName(),
                        GradeImportRowIssueDTO.FIELD_FINALTERM_SCORE, "abc", "期末成绩必须是 0-100 的数字")));
        controller.importController().startImport();
        settle(controller);

        require(controller.importController().importing(), "预览成功后必须处于导入态");
        require(sameRowObjects(before, controller.rows()),
                "预览不能换掉行对象：同一张表，新的 Row 对象意味着重建了表格");
        require(controller.tableRowCount() == before.size(),
                "预览不能让表格多出一行（未知学生不进这张表）");
        require("95".equals(target.cell(GradeComponentCodeDTO.DAILY).text()),
                "候选里的分数必须合并进这一行，收到 " + target.cell(GradeComponentCodeDTO.DAILY).text());
        require("66".equals(target.cell(GradeComponentCodeDTO.EXPERIMENT).text()),
                "候选里的实验分必须合并进来");
        require(midtermBefore.equals(target.cell(GradeComponentCodeDTO.MIDTERM).text()),
                "候选里为 null 的组成保留原分数（缺列不擦除原分数）");
        require(controller.model().dirty(), "预览内容尚未落库，模型必须标成未保存");
        require(weightBefore == controller.model().column(GradeComponentCodeDTO.DAILY)
                        .weightBasisPoints(),
                "方案列（权重）在预览里不动");
    }

    /** 有未保存修改的编辑副本：进入预览再取消，值、原文与 dirty 标志必须一起回来。 */
    private static void dirtyEditCopyIsRestoredOnCancelIncludingTheDirtyFlag() {
        ImportService service = new ImportService();
        FakeDialogs dialogs = new FakeDialogs();
        TeacherGradeBookController controller = controller(service, dialogs);
        controller.showOffering(OFFERING);
        require(!controller.dirty(), "刚加载的页面不是 dirty");

        Row first = controller.rows().get(0);
        controller.model().setScore(first.enrollmentId(), GradeComponentCodeDTO.DAILY, "77");
        require(controller.dirty(), "手工编辑之后必须是 dirty");
        String typed = first.cell(GradeComponentCodeDTO.DAILY).text();

        service.previewResponse = preview(1, candidateFor(controller, first.enrollmentId(),
                new GradeScoresDTO(new BigDecimal("100"), null, null, null)),
                0, 0, List.of());
        controller.importController().startImport();
        settle(controller);
        require(controller.dirty(), "预览期间仍然是未保存状态");

        controller.importController().cancelImport();
        settle(controller);

        require(!controller.importController().importing(), "取消之后不再处于导入态");
        Row restored = rowFor(controller, first.enrollmentId());
        require(typed.equals(restored.cell(GradeComponentCodeDTO.DAILY).text()),
                "取消必须恢复导入前的原文，收到 " + restored.cell(GradeComponentCodeDTO.DAILY).text());
        require(controller.dirty(),
                "取消导入必须恢复导入前的 dirty 标志：教师原本就有未保存的修改");
        require(controller.model().state() != null && controller.model().canEdit(),
                "取消恢复后依然是可编辑的草稿");
        require(service.cancelledTokens.equals(List.of(TOKEN)),
                "取消要把服务端预览令牌交还，收到 " + service.cancelledTokens);

        // 干净副本的另一半：导入前没有未保存修改，取消之后也不该凭空变成 dirty。
        ImportService clean = new ImportService();
        TeacherGradeBookController cleanController = controller(clean, new FakeDialogs());
        cleanController.showOffering(OFFERING);
        clean.previewResponse = preview(1, candidateFor(cleanController,
                        cleanController.rows().get(0).enrollmentId(),
                        new GradeScoresDTO(new BigDecimal("88"), null, null, null)),
                0, 0, List.of());
        cleanController.importController().startImport();
        settle(cleanController);
        cleanController.importController().cancelImport();
        settle(cleanController);
        require(!cleanController.dirty(),
                "导入前没有未保存修改的页面，取消之后也不能变成 dirty");
    }

    // ------------------------------------------------------------------ 修正与旧预览

    /**
     * 异常格子的修正走 revise：非异常格子根本改不动，异常格子改了也不算完——请求里带上这次修订
     * 后的完整修正状态，而红框只由服务端下一次预览消除；离开（取消）之后才到达的响应必须被丢弃，
     * 不能把一个已经作废的预览按回界面上。
     */
    private static void issueCellsGoThroughReviseAndALateResponseIsDiscarded() {
        ImportService service = new ImportService();
        TeacherGradeBookController controller = controller(service, new FakeDialogs());
        controller.showOffering(OFFERING);
        Row row = controller.rows().get(0);
        Row other = controller.rows().get(1);
        String field = GradeImportRowIssueDTO.FIELD_FINALTERM_SCORE;
        String secondField = GradeImportRowIssueDTO.FIELD_EXPERIMENT_SCORE;

        service.previewResponse = preview(1, candidateFor(controller, row.enrollmentId(),
                        new GradeScoresDTO(null, null, null, null)),
                2, 2, List.of(
                        issue(3, row.studentUid(), row.studentName(), field, "abc",
                                "期末成绩必须是 0-100 的数字"),
                        issue(4, row.studentUid(), row.studentName(), secondField, "9x",
                                "实验成绩必须是 0-100 的数字")));
        controller.importController().startImport();
        settle(controller);

        require(controller.importController().isCorrectableCell(row, GradeComponentCodeDTO.FINALTERM),
                "服务端报过问题的格子必须是可修正的");
        require(!controller.importController().isCorrectableCell(row, GradeComponentCodeDTO.DAILY),
                "同一行里没有问题的格子不算异常格");
        require(!controller.importController().isCorrectableCell(other,
                        GradeComponentCodeDTO.FINALTERM),
                "别的学生的格子不算异常格");
        require("期末成绩必须是 0-100 的数字".equals(
                        row.cell(GradeComponentCodeDTO.FINALTERM).error()),
                "服务端的说明必须显示在这一格上，而不是拿候选里的旧值冒充导入成功");

        // 改一个没有问题的格子：拒绝，不产生修正、更不产生本地修改。
        String dailyBefore = row.cell(GradeComponentCodeDTO.DAILY).text();
        controller.liveScoreEdit(row, GradeComponentCodeDTO.DAILY, "88");
        require(service.revises.isEmpty(), "普通格子的键入不能变成一次修订");
        require(dailyBefore.equals(row.cell(GradeComponentCodeDTO.DAILY).text()),
                "预览期间普通格子改不动，收到 " + row.cell(GradeComponentCodeDTO.DAILY).text());
        require(TeacherGradeImportController.NO_ISSUE_CELL_TEXT.equals(controller.feedbackText()),
                "拒绝的原因要说明清楚，收到 " + controller.feedbackText());

        // 修正异常格子：请求里必须带上这条修正，且红框仍由服务端问题维持。
        service.previewResponses.clear();
        CompletableFuture<GradeImportPreviewDTO> stale = new CompletableFuture<>();
        service.previewResponses.addLast(stale);   // 第一次修订挂起，稍后才返回
        service.previewResponses.addLast(CompletableFuture.completedFuture(
                preview(3, candidateFor(controller, row.enrollmentId(),
                                new GradeScoresDTO(null, null, null, new BigDecimal("91"))),
                        1, 0, List.of())));
        controller.liveScoreEdit(row, GradeComponentCodeDTO.FINALTERM, "91");
        ReviseGradeImportRequestDTO first = service.revises.get(0);
        require(first.getExpectedPreviewRevision() == 1 && first.getCorrections().size() == 1,
                "第一次修订必须带上一处修正与当时的预览版本，收到 " + first.getExpectedPreviewRevision());
        require(Map.of(field, "91").equals(first.getCorrections().get(0).getCorrectedCells()),
                "修正的键必须是服务端的字段名，收到 "
                        + first.getCorrections().get(0).getCorrectedCells());
        require(first.getCorrections().get(0).getRowNumber() == 3,
                "修正必须落在 Excel 数据行号上");
        require("91".equals(row.cell(GradeComponentCodeDTO.FINALTERM).text()),
                "教师敲的字立刻可见");
        require("期末成绩必须是 0-100 的数字".equals(
                        row.cell(GradeComponentCodeDTO.FINALTERM).error()),
                "本地敲字不能把红框抹掉：只有服务端的下一次预览能消除它");

        // 修订还没回来就取消导入：此后到达的响应是把已经作废的预览按回界面的唯一途径，必须被丢弃。
        controller.importController().cancelImport();
        settle(controller);
        require(!controller.importController().importing()
                        && controller.importController().preview() == null,
                "取消之后不能还留着预览状态");
        stale.complete(preview(9, candidateFor(controller, row.enrollmentId(),
                new GradeScoresDTO(null, null, null, new BigDecimal("91"))), 1, 0, List.of()));
        settle(controller);
        require(controller.importController().preview() == null
                        && !controller.importController().importing(),
                "取消之后到达的响应必须被丢弃，不能把作废的预览按回界面");
        require(!controller.dirty() && controller.model().state() != null,
                "取消之后模型停在导入前的干净副本上");
    }

    /** 未知学生的行不在成绩表里：通过错误列表明确排除之后才算解决，排除同样走 revise。 */
    private static void unknownStudentRowsAreResolvedByExplicitExclusion() {
        ImportService service = new ImportService();
        TeacherGradeBookController controller = controller(service, new FakeDialogs());
        controller.showOffering(OFFERING);
        int rowsBefore = controller.rows().size();

        service.previewResponse = preview(1, candidateFor(controller,
                        controller.rows().get(0).enrollmentId(),
                        new GradeScoresDTO(new BigDecimal("60"), null, null, null)),
                5, 2, List.of(
                        issue(9, "00009999", "陌生人", GradeImportRowIssueDTO.FIELD_STUDENT_UID, "00009999",
                                "学号不在本教学班名单中"),
                        issue(11, "00005678", "张三", GradeImportRowIssueDTO.FIELD_DAILY_SCORE, "abc",
                                "平时成绩必须是 0-100 的数字")));
        controller.importController().startImport();
        settle(controller);
        require(controller.rows().size() == rowsBefore,
                "未知学生不会在这张表里多出一行");
        require(!controller.importController().confirmEnabled(),
                "还有未解决异常行时确认按钮不可用");
        require(TeacherGradeImportController.summaryText(controller.importController().preview())
                        .contains("陌生人"),
                "异常姓名必须出现在摘要里，未知学生也不例外");

        // 先排除未知学生行；服务端说还剩一行没解决，确认仍然不可用。
        service.previewResponses.clear();
        service.previewResponses.addLast(CompletableFuture.completedFuture(preview(2,
                candidateFor(controller, controller.rows().get(0).enrollmentId(),
                        new GradeScoresDTO(new BigDecimal("60"), null, null, null)),
                5, 1, List.of(issue(11, "00005678", "张三",
                        GradeImportRowIssueDTO.FIELD_DAILY_SCORE, "abc",
                        "平时成绩必须是 0-100 的数字")))));
        controller.importController().setExcluded(9, true);
        settle(controller);
        ReviseGradeImportRequestDTO excluded = service.revises.get(0);
        require(excluded.getExcludedRows().equals(List.of(9)),
                "明确排除的行号必须原样发给服务端，收到 " + excluded.getExcludedRows());
        require(!controller.importController().confirmEnabled(),
                "服务端仍报异常时确认按钮仍不可用（结论只来自最新预览）");

        // 排除最后一行：服务端返回零异常，确认才可用。
        service.previewResponses.clear();
        service.previewResponses.addLast(CompletableFuture.completedFuture(preview(3,
                candidateFor(controller, controller.rows().get(0).enrollmentId(),
                        new GradeScoresDTO(new BigDecimal("60"), null, null, null)),
                5, 0, List.of())));
        controller.importController().setExcluded(11, true);
        settle(controller);
        require(service.revises.get(1).getExcludedRows().size() == 2,
                "修订请求带的是这份修订后的完整排除集合，收到 "
                        + service.revises.get(1).getExcludedRows());
        require(controller.importController().confirmEnabled(),
                "服务端确认没有未解决异常行之后，确认按钮才可用");
    }

    // ------------------------------------------------------------------ 确认

    /** 确认成功：用服务端返回的草稿替换编辑内容，回到普通编辑，而且绝不触发提交审批。 */
    private static void confirmUsesTheLatestServerPreviewAndNeverSubmits() {
        ImportService service = new ImportService();
        TeacherGradeBookController controller = controller(service, new FakeDialogs());
        controller.showOffering(OFFERING);
        Row row = controller.rows().get(0);

        service.previewResponse = preview(4, candidateFor(controller, row.enrollmentId(),
                new GradeScoresDTO(new BigDecimal("92"), null, null, null)), 0, 0, List.of());
        service.confirmResult = new TeacherOperationResultDTO<>("op-1", "导入已保存到成绩草稿",
                service.delegate.getGradeBook(OFFERING).join(), false);
        controller.importController().startImport();
        settle(controller);
        require(controller.importController().confirmEnabled(), "没有异常时确认可用");

        controller.importController().confirmImport();
        settle(controller);

        ConfirmGradeImportRequestDTO confirm = service.confirms.get(0);
        require(confirm.getOperationId() != null && !confirm.getOperationId().isBlank(),
                "确认请求必须带一个幂等 operationId");
        require(confirm.getExpectedPreviewRevision() == 4,
                "确认请求必须锁定它确认的那一版预览，收到 "
                        + confirm.getExpectedPreviewRevision());
        require(service.confirms.size() == 1, "只应发出一次确认请求");
        require(controller.importController().importing() == false,
                "确认成功后必须退出导入态，回到普通编辑");
        require(!controller.dirty(), "确认成功用服务端草稿替换模型，dirty 随之清零");
        require(service.submits.isEmpty(),
                "确认导入绝不是提交审批：submitGradeBook 不能被调用");
        require(service.saved.size() == 0,
                "确认导入只能走 confirmGradeImport 这一条写路径");
        require(TeacherGradeImportController.CONFIRM_SUCCESS_TEXT.equals(controller.feedbackText()),
                "确认成功要有明确的提示，收到 " + controller.feedbackText());
    }

    /** 确认时版本冲突：保留预览与导入前的副本，提示带上服务端当前版本，等待教师决定。 */
    private static void confirmConflictKeepsThePreviewAndTheEditingCopy() {
        ImportService service = new ImportService();
        TeacherGradeBookController controller = controller(service, new FakeDialogs());
        controller.showOffering(OFFERING);
        Row row = controller.rows().get(0);
        controller.model().setScore(row.enrollmentId(), GradeComponentCodeDTO.DAILY, "77");

        service.previewResponse = preview(1, candidateFor(controller, row.enrollmentId(),
                new GradeScoresDTO(new BigDecimal("92"), null, null, null)), 0, 0, List.of());
        service.confirmFailure = new TeacherCourseServiceException(MessageCode.CONFLICT,
                "成绩草稿版本已变化，请重新导入", List.of(), null,
                service.delegate.getGradeBook(OFFERING).join());
        controller.importController().startImport();
        settle(controller);
        controller.importController().confirmImport();
        settle(controller);

        require(controller.importController().importing(),
                "冲突之后预览必须保留，不能悄悄退出导入态");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("重新加载")
                        && controller.feedbackText().contains("v"),
                "冲突提示要说明重新加载与服务端版本，收到 " + controller.feedbackText());
        require(service.submits.isEmpty(), "冲突不是提交失败，不能因此触发提交");
        // 冲突之后仍然可以选择取消，并且恢复导入前的副本（含 dirty）。
        controller.importController().cancelImport();
        settle(controller);
        require("77".equals(rowFor(controller, row.enrollmentId())
                        .cell(GradeComponentCodeDTO.DAILY).text()) && controller.dirty(),
                "冲突后取消同样要恢复到导入前的编辑副本");
    }

    /** 导入途中离开上传页：取消在途 Future（传输层据此关闭短连接）并恢复导入前的副本。 */
    private static void leavingDuringImportCancelsTheTransferAndRestoresTheCopy() {
        ImportService service = new ImportService();
        FakeDialogs dialogs = new FakeDialogs();
        FakeTransport transport = new FakeTransport();
        TeacherGradeBookController controller = controller(service, transport, dialogs);
        controller.showOffering(OFFERING);
        Row row = controller.rows().get(0);
        controller.model().setScore(row.enrollmentId(), GradeComponentCodeDTO.DAILY, "55");

        transport.hold = true;   // 上传挂在半空：这正是「途中离开」要处理的时刻
        controller.importController().startImport();
        waitUntil(() -> transport.uploadFuture != null, "上传没有被派发");
        require(controller.importController().busy(), "传输在途时必须处于忙状态");

        boolean allowed = controller.requestLeave();

        require(transport.uploadFuture.isCancelled(),
                "离开必须取消在途传输的 Future（传输层据此关闭短连接）");
        require(!controller.importController().importing() && !controller.importController().busy(),
                "离开必须丢弃预览与在途状态");
        require("55".equals(rowFor(controller, row.enrollmentId())
                        .cell(GradeComponentCodeDTO.DAILY).text()) && controller.dirty(),
                "离开上传页要恢复到导入前的编辑副本（含 dirty）");
        require(allowed == confirmationAnswer(),
                "恢复之后是否允许离开由恢复出来的 dirty 状态决定");
    }

    /**
     * 上传派发之前的那段窗口（算指纹、申请票据的往返）里离开：不能再去开短连接。
     *
     * <p>{@code CompletableFuture.cancel} 只让当前那一段以后不再继续，已经在跑的中间段会照常执行完，
     * 所以在中间段里「先确认这次导入还作数、再派发」才是唯一挡得住后续网络动作的地方。这条断言
     * 看的正是后果：没有它，票据会被花掉、一条没人在等的上传会把孤儿文件留在服务端。
     */
    private static void leavingBeforeTheUploadIsDispatchedSpendsNoTicket() {
        ImportService service = new ImportService();
        FakeTransport transport = new FakeTransport();
        TeacherGradeBookController controller = controller(service, transport, new FakeDialogs());
        controller.showOffering(OFFERING);
        Row row = controller.rows().get(0);
        controller.model().setScore(row.enrollmentId(), GradeComponentCodeDTO.DAILY, "55");

        service.holdUploadTicket();     // 卡在「申请上传票据」这一步，还没轮到传输
        controller.importController().startImport();
        waitUntil(() -> service.uploadRequested, "导入链没有走到申请上传票据这一步");

        controller.importController().cancelOnLeave();     // 教师在票据回来之前点了返回
        service.completeHeldUploadTicket();
        settle(controller);

        require(transport.lastTicket == null,
                "离开之后绝不能再去兑换票据开短连接");
        require(!controller.importController().importing()
                        && controller.importController().preview() == null,
                "离开之后不能凭空出现预览");
        require("55".equals(rowFor(controller, row.enrollmentId())
                        .cell(GradeComponentCodeDTO.DAILY).text()) && controller.dirty(),
                "导入前的编辑副本仍然完整恢复");
        require(!TeacherGradeImportController.UPLOAD_FAILURE_TEXT.equals(controller.feedbackText()),
                "被取消的导入不该报成上传失败，收到 " + controller.feedbackText());
    }

    /**
     * 连续打字：修订在途时只累积，不各自带着同一个基版本去撞服务端；响应落地后用刚拿到的
     * previewRevision 把这一刻的完整修正状态一次性冲刷出去。
     *
     * <p>这正是「每个击键发一次 revise」会踩的坑：服务端严格要求 {@code expectedPreviewRevision} 相等，
     * 而版本只有在响应回来时才前进——两个请求带同一个基版本时只有一个能被接受，教师此后所有纠错都会
     * 卡在「导入预览已更新」上（页面上并没有「重新加载预览」这个操作）。
     */
    private static void rapidCorrectionsAreCoalescedAndAlwaysUseTheFreshPreviewRevision() {
        ImportService service = new ImportService();
        TeacherGradeBookController controller = controller(service, new FakeDialogs());
        controller.showOffering(OFFERING);
        Row row = controller.rows().get(0);
        String finalterm = GradeImportRowIssueDTO.FIELD_FINALTERM_SCORE;

        // 三份候选都先算好：键入非法原文之后模型会拒绝再构造写内容（这正是模型该做的事）。
        GradeBookContentDTO opened = candidateFor(controller, row.enrollmentId(),
                new GradeScoresDTO(null, null, null, null));
        GradeBookContentDTO afterFirst = candidateFor(controller, row.enrollmentId(),
                new GradeScoresDTO(null, null, null, new BigDecimal("9.1")));
        GradeBookContentDTO afterAll = candidateFor(controller, row.enrollmentId(),
                new GradeScoresDTO(null, null, null, new BigDecimal("91.2")));
        List<GradeImportRowIssueDTO> stillBroken = List.of(issue(3, row.studentUid(),
                row.studentName(), finalterm, "abc", "期末成绩必须是 0-100 的数字"));

        service.previewResponse = preview(1, opened, 1, 1, stillBroken);
        controller.importController().startImport();
        settle(controller);

        CompletableFuture<GradeImportPreviewDTO> firstRevise = new CompletableFuture<>();
        service.previewResponses.addLast(firstRevise);
        service.previewResponses.addLast(
                CompletableFuture.completedFuture(preview(3, afterAll, 1, 0, List.of())));

        // 三连击：只有第一个请求出去，后面两个只在本地累积。
        controller.liveScoreEdit(row, GradeComponentCodeDTO.FINALTERM, "9");
        controller.liveScoreEdit(row, GradeComponentCodeDTO.FINALTERM, "91");
        controller.liveScoreEdit(row, GradeComponentCodeDTO.FINALTERM, "912");
        require(service.revises.size() == 1,
                "修订在途时不能再派发第二个请求，收到 " + service.revises.size() + " 个");
        require(service.revises.get(0).getExpectedPreviewRevision() == 1,
                "第一个修订以当前预览版本为基");

        firstRevise.complete(preview(2, afterFirst, 1, 1, stillBroken));
        settle(controller);

        require(service.revises.size() == 2,
                "响应落地后要把在途期间累积的修正冲刷出去，收到 " + service.revises.size() + " 个");
        ReviseGradeImportRequestDTO flushed = service.revises.get(1);
        require(flushed.getExpectedPreviewRevision() == 2,
                "冲刷必须带刚刚拿到的预览版本（v2），收到 " + flushed.getExpectedPreviewRevision());
        require(Map.of(finalterm, "912").equals(flushed.getCorrections().get(0).getCorrectedCells()),
                "冲刷的必须是最后一次修正后的完整状态，收到 "
                        + flushed.getCorrections().get(0).getCorrectedCells());
        require(controller.importController().preview().getPreviewRevision() == 3
                        && controller.importController().confirmEnabled(),
                "最新预览（v3，零异常）落地后确认才可用，收到 v"
                        + controller.importController().preview().getPreviewRevision());
        require(controller.feedbackText() != null
                        && !controller.feedbackText().contains("导入预览已更新"),
                "整个过程中不能出现「预览已更新」这种页面上无从操作的提示，收到 "
                        + controller.feedbackText());
    }

    // ------------------------------------------------------------------ 反馈与文件选择

    /** 反馈文案：总记录/有效/异常 + 异常姓名；任何提示里都不出现 importToken。 */
    private static void feedbackShowsCountsAndAbnormalNamesWithoutTheImportToken() {
        GradeImportPreviewDTO preview = preview(1, null, 12, 2, List.of(
                issue(3, "00005678", "张三", GradeImportRowIssueDTO.FIELD_FINALTERM_SCORE, "abc",
                        "期末成绩必须是 0-100 的数字"),
                issue(5, "00005679", "李四", GradeImportRowIssueDTO.FIELD_DAILY_SCORE, "x",
                        "平时成绩必须是 0-100 的数字")));
        String summary = TeacherGradeImportController.summaryText(preview);
        require(summary.contains("总记录 12") && summary.contains("有效 10")
                        && summary.contains("异常 2"),
                "摘要必须给出总记录/有效/异常，收到 " + summary);
        require(summary.contains("张三") && summary.contains("李四"),
                "摘要必须列出异常学生姓名，收到 " + summary);
        require(!summary.contains(TOKEN) && !summary.contains("token-" + 1),
                "importToken 是内部句柄，绝不能出现在给教师看的文案里");
        require(!TeacherGradeImportController.issueLine(preview.getIssues().get(0)).contains(TOKEN),
                "单行异常文案同样不能带令牌");
        require(TeacherGradeImportController.issueLine(preview.getIssues().get(0))
                        .contains("期末成绩") && TeacherGradeImportController
                        .issueLine(preview.getIssues().get(0)).contains("abc"),
                "单行异常要能在错误列表里定位到字段与原文");

        // 弹窗关闭之后同表预览必须还在：这里用注入的展示器断言「展示」与「取消」是两件事。
        ImportService service = new ImportService();
        List<GradeImportPreviewDTO> shown = new ArrayList<>();
        TeacherGradeBookController controller = controller(service, new FakeDialogs());
        controller.importController().setFeedbackPresenter(shown::add);
        controller.showOffering(OFFERING);
        service.previewResponse = preview(1, candidateFor(controller,
                controller.rows().get(0).enrollmentId(), new GradeScoresDTO(null, null, null,
                        new BigDecimal("91"))), 2, 1, List.of(issue(3, "00005678", "张三",
                        GradeImportRowIssueDTO.FIELD_DAILY_SCORE, "x", "平时成绩必须是 0-100 的数字")));
        controller.importController().startImport();
        settle(controller);
        require(shown.size() == 1, "预览回来要展示一次异常明细");
        require(controller.importController().importing(),
                "展示异常明细不等于取消导入：同表预览必须保留");
        controller.importController().showIssues();
        require(shown.size() == 2, "关闭弹窗之后还能再打开异常明细");
    }

    /**
     * 文件选择与传输的线程分工：选择器与覆盖确认在调用线程（生产上是 FX 线程），
     * 票据申请之后的文件传输在后台线程；覆盖确认排在 FileChooser 之后、传输之前。
     */
    private static void downloadsChooseTheFileOnTheCallingThreadAndTransferInBackground()
            throws Exception {
        Thread caller = Thread.currentThread();
        Path target = Files.createTempFile("vcampus-import-test", ".xlsx");
        FakeDialogs dialogs = new FakeDialogs();
        dialogs.saveTarget = target;
        FakeTransport transport = new FakeTransport();
        List<String> order = new ArrayList<>();
        ImportService service = new ImportService();
        service.ticketOrder = order;
        transport.order = order;

        CompletableFuture<Path> download = TeacherGradeImportController.downloadTicketToFile(
                dialogs, transport, message -> {
                    order.add("confirm");
                    return true;
                }, () -> {
                    order.add("ticket");
                    return CompletableFuture.completedFuture(ticketDto());
                }, TeacherGradeImportController.TEMPLATE_FILENAME);

        require(dialogs.callingThreads.equals(List.of(caller)),
                "FileChooser 必须在调用线程（生产上是 FX 线程）里跑，收到 "
                        + dialogs.callingThreads);
        require(order.equals(List.of("confirm", "ticket", "transfer")),
                "顺序必须是：选文件 → 覆盖确认 → 申请票据 → 传输，收到 " + order);
        require(transport.callingThread != null && transport.callingThread != caller,
                "文件传输必须在后台线程，而不是调用线程");
        require(target.equals(download.join()), "下载成功后回到用户选定的那个文件");
        require(Files.exists(target) == false || Files.size(target) == 0,
                "假传输不会真的写文件，真实验证在传输层自己的测试里");

        // 已存在的目标 + 用户拒绝覆盖：一个请求都不发。
        FakeTransport refused = new FakeTransport();
        Path existing = Files.createTempFile("vcampus-import-existing", ".xlsx");
        CompletableFuture<Path> cancelled = TeacherGradeImportController.downloadTicketToFile(
                new FakeDialogs(existing), refused, message -> false,
                () -> CompletableFuture.completedFuture(ticketDto()),
                TeacherGradeImportController.TEMPLATE_FILENAME);
        require(cancelled.join() == null, "拒绝覆盖时不应产生输出文件");
        require(refused.lastTicket == null, "拒绝覆盖之后不能开始传输");

        // 用户在 FileChooser 里取消：同样什么都不做。
        FakeTransport unused = new FakeTransport();
        require(TeacherGradeImportController.downloadTicketToFile(new FakeDialogs(), unused,
                        message -> true, () -> CompletableFuture.completedFuture(ticketDto()),
                        TeacherGradeImportController.TEMPLATE_FILENAME).join() == null,
                "取消选文件不应产生输出文件");
        require(unused.lastTicket == null, "取消选文件之后不能开始传输");
        Files.deleteIfExists(target);
        Files.deleteIfExists(existing);
    }

    // ------------------------------------------------------------------ 同一张成绩表的页面接线

    /** 底部按钮、方案冻结、保存/提交被挡住：导入预览期间教师的可行动作只剩修正、排除、取消、确认。 */
    private static void bottomBarFreezesTheSchemeAndSwapsItsButtons() {
        ImportService service = new ImportService();
        TeacherGradeBookController controller = controller(service, new FakeDialogs());
        controller.showOffering(OFFERING);
        require(!controller.importController().importing(), "打开页面时没有导入预览");

        service.previewResponse = preview(1, candidateFor(controller,
                        controller.rows().get(0).enrollmentId(),
                        new GradeScoresDTO(new BigDecimal("70"), null, null, null)),
                0, 0, List.of());
        controller.importController().startImport();
        settle(controller);

        int weightBefore = controller.model().column(GradeComponentCodeDTO.DAILY).weightBasisPoints();
        controller.applyWeight(GradeComponentCodeDTO.DAILY, "50");
        controller.applyEnabled(GradeComponentCodeDTO.DAILY, false);
        require(TeacherGradeBookController.FROZEN_SCHEME_TEXT.equals(controller.feedbackText()),
                "导入预览期间手动改权重必须被拒绝并说明原因，收到 " + controller.feedbackText());
        require(weightBefore == controller.model().column(GradeComponentCodeDTO.DAILY)
                        .weightBasisPoints(),
                "被拒绝的权重修改不能真的落到模型上");

        controller.save();
        require(service.saved.isEmpty(), "导入预览期间保存草稿必须被挡住");
        controller.requestSubmit();
        require(!controller.confirmingSubmit(), "导入预览期间不能进入提交确认态");
        require(service.submits.isEmpty(), "导入预览期间不能提交");

        controller.importController().cancelImport();
        settle(controller);
        require(!controller.importController().importing(), "取消之后回到普通编辑");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("取消导入"),
                "取消后要有明确提示，收到 " + controller.feedbackText());

        // 工作台复用同一个控制器实例：离开再进入同一个教学班，导入入口必须重新可用。
        controller.release();
        require(!controller.importController().active(), "页面卸下之后不再接受导入操作");
        controller.showOffering(OFFERING);
        require(controller.importController().active() && controller.model() != null,
                "重新进入成绩表要重新激活同一套导入编排");
    }

    /**
     * 详情页的导出：页面的**复用模式**下，迟到的响应不能写到一个别的教学班上。
     *
     * <p>这条用例必须走「离开 A → 打开 B → A 的下载才回来」这条路径，而不是「离开就再也不回来」：
     * {@code showOffering} 先 {@code release()} 又把 {@code active}/{@code offeringId} 填回来，
     * 所以只判「是不是活动页面、有没有教学班」拦不住它——拦得住它的只有一并前进的代际。
     */
    private static void exportFeedbackNeverLandsOnAnotherOffering() throws Exception {
        ImportService service = new ImportService();
        // 目标文件刻意不存在：详情页的覆盖确认是真实对话框（无工具包环境里不能弹），
        // 这条用例验证的是「响应迟到」，不是覆盖确认。
        FakeDialogs dialogs = FakeDialogs.savingTo(Files
                .createTempDirectory("vcampus-export-test").resolve("学生名单.xlsx"));
        FakeTransport transport = new FakeTransport();
        transport.holdDownload = true;
        TeacherOfferingDetailController detail = new TeacherOfferingDetailController(service,
                Runnable::run, transport, dialogs);
        detail.showOffering(OFFERING);
        detail.selectTab(1);

        detail.handleExport(null);
        require(detail.exporting(), "导出期间页面处于导出态");
        require(transport.downloadFuture != null, "导出必须真的开始传输");
        require(service.exports.size() == 1, "导出要用当前筛选条件申请票据");
        CompletableFuture<Void> download = transport.downloadFuture;

        // 在教学班 A 的下载还在途中，离开 A、打开 B——页面本身被复用，active 与 offeringId 都会
        // 重新有值，这正是「只判 active/offeringId」会漏掉的形态。
        detail.showOffering(OTHER_OFFERING);
        require(detail.offeringId().equals(OTHER_OFFERING), "页面已经切到另一个教学班");
        require(!detail.exporting(), "切班必须把导出态清干净");

        download.complete(null);   // A 的下载这时候才完成

        require(detail.exportFeedbackText() == null,
                "A 的导出结果不能渲染到 B 的页面上，收到 " + detail.exportFeedbackText());
        require(!detail.exporting(), "B 的页面不能被 A 的导出拖进导出态");
        require(detail.offeringId().equals(OTHER_OFFERING) && detail.active(),
                "B 仍然是当前教学班，没有被迟到的响应改动");

        // 反过来：同一个教学班上正常完成的导出必须照常给反馈（守卫不能把正常路径一起挡掉）。
        FakeTransport plain = new FakeTransport();
        TeacherOfferingDetailController alone = new TeacherOfferingDetailController(service,
                Runnable::run, plain, dialogs);
        alone.showOffering(OFFERING);
        alone.selectTab(1);
        alone.handleExport(null);
        // 传输是在后台线程上派发的，反馈因此也是稍后才落到页面上。
        waitUntil(() -> alone.exportFeedbackText() != null,
                "正常完成的导出必须给出反馈");
        require(alone.exportFeedbackText().contains("学生名单已保存到"),
                "正常路径必须照常提示保存位置，收到 " + alone.exportFeedbackText());
        require(!alone.exporting(), "导出完成后回到非导出态");
    }

    /**
     * mock 的预览版本语义必须与真实服务一致（首次 1、每次修订严格加一、基版本不等就拒绝）——
     * 否则用 mock 驱动的界面路径永远看不到「基版本拿旧了」这类缺陷，测试只能靠脚本化响应假装。
     */
    private static void mockPreviewRevisionsIncrementAndRejectStaleOnes() {
        MockTeacherCourseService mock = new MockTeacherCourseService();
        GradeBookContentDTO base = new GradeBookEditorModel(
                mock.getGradeBook(OFFERING).join()).content();
        TeacherFileTicketDTO ticket = mock.beginGradeUpload(new TeacherFileUploadRequestDTO(OFFERING,
                base.getExpectedRevision(), "成绩.xlsx", base.getRows().size(),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")).join();

        GradeImportPreviewDTO first = mock.previewGradeImport(
                new PreviewGradeImportRequestDTO(ticket.getTicket(), base)).join();
        require(first.getPreviewRevision() == 1,
                "首次预览的版本必须是 1，收到 " + first.getPreviewRevision());

        GradeImportPreviewDTO second = mock.reviseGradeImport(new ReviseGradeImportRequestDTO(
                first.getImportToken(), 1, List.of(), List.of(7))).join();
        require(second.getPreviewRevision() == 2,
                "每次修订必须严格加一，收到 " + second.getPreviewRevision());

        try {
            mock.reviseGradeImport(new ReviseGradeImportRequestDTO(first.getImportToken(), 1,
                    List.of(), List.of())).join();
            throw new AssertionError("带旧基版本的修订必须被拒绝");
        } catch (CompletionException failure) {
            TeacherCourseServiceException error =
                    (TeacherCourseServiceException) failure.getCause();
            require(error.getCode() == MessageCode.CONFLICT
                            && error.getMessage().contains("导入预览已更新"),
                    "旧基版本要以冲突拒绝，收到 " + error.getCode() + " / " + error.getMessage());
        }

        require(mock.confirmGradeImport(new ConfirmGradeImportRequestDTO(
                        java.util.UUID.randomUUID().toString(), first.getImportToken(), 1, 0))
                        .handle((value, failure) -> failure != null).join(),
                "带旧基版本的确认同样必须被拒绝");
        require(mock.confirmGradeImport(new ConfirmGradeImportRequestDTO(
                        java.util.UUID.randomUUID().toString(), first.getImportToken(), 2,
                        base.getExpectedRevision())).join().getValue() != null,
                "带最新版本的确认才写草稿");

        // 上传票据单次领取：同一张票不能再换一份预览。
        require(mock.previewGradeImport(new PreviewGradeImportRequestDTO(ticket.getTicket(), base))
                        .handle((value, failure) -> failure != null).join(),
                "一次性上传票据不能被重复兑换");
    }

    // ------------------------------------------------------------------ FXML 契约

    /**
     * 三个视图的结构契约：每个 fx:id 都有对应字段，每个 onAction 都有处理函数，所有 styleClass
     * 都在 teacher-course.css 里有选择器；反馈弹窗的控制器（{@code TeacherGradeImportController$Feedback}）
     * 必须真的能按 FXML 里的名字解析出来——否则弹窗只会在运行时炸掉。
     */
    private static void importViewsDeclareTheirControllerIdsHandlersAndStyles() throws Exception {
        require(Class.forName("controller.TeacherGradeImportController$Feedback") != null,
                "反馈弹窗的控制器类必须能按 FXML 里的名字解析");
        require(TeacherGradeImportController.Feedback.TITLE != null
                        && !TeacherGradeImportController.Feedback.TITLE.isBlank(),
                "反馈弹窗必须有固定标题");

        String css = readResource(CSS);
        verifyBindings(parseView(FEEDBACK_VIEW), TeacherGradeImportController.Feedback.class,
                FEEDBACK_VIEW);
        verifyBindings(parseView(BOOK_VIEW), TeacherGradeBookController.class, BOOK_VIEW);
        verifyBindings(parseView(DETAIL_VIEW), TeacherOfferingDetailController.class, DETAIL_VIEW);

        for (String view : List.of(FEEDBACK_VIEW, BOOK_VIEW, DETAIL_VIEW)) {
            Document document = parseView(view);
            NodeList elements = document.getElementsByTagName("*");
            int classes = 0;
            for (int index = 0; index < elements.getLength(); index++) {
                String value = ((Element) elements.item(index)).getAttribute("styleClass");
                if (value.isEmpty()) continue;
                for (String token : value.split(",")) {
                    String name = token.trim();
                    if (name.isEmpty()) continue;
                    classes++;
                    require(css.contains("." + name),
                            view + " 的 styleClass " + name + " 在 teacher-course.css 里没有选择器");
                }
            }
            require(classes > 0, view + " 必须使用 teacher-course.css 的样式类");
        }

        Document book = parseView(BOOK_VIEW);
        require(textOfButton(book, "取消导入") != null && textOfButton(book, "确认导入") != null,
                "成绩表底部必须有取消导入与确认导入两个按钮");
        require(textOfButton(book, "下载成绩模板") != null && textOfButton(book, "导出名单") != null,
                "成绩表底部必须接通模板下载与名单导出");
        for (String text : List.of("取消导入", "确认导入", "异常明细")) {
            Element button = textOfButton(book, text);
            require("false".equals(button.getAttribute("visible"))
                            && "false".equals(button.getAttribute("managed")),
                    text + " 按钮只应在导入预览期间出现");
        }
        Element exportButton = textOfButton(parseView(DETAIL_VIEW), "导出");
        require(exportButton != null && "handleExport".equals(
                        exportButton.getAttribute("onAction").substring(1)),
                "详情页的导出按钮必须真的接到处理函数上");
        require(!exportButton.hasAttribute("disable"),
                "导出按钮不能是永久禁用的占位：T5 的这一项在本阶段接通");
    }

    // ------------------------------------------------------------------ 辅助

    private static TeacherGradeBookController controller(TeacherCourseService service,
            TeacherGradeImportController.FileDialogs dialogs) {
        return controller(service, new FakeTransport(), dialogs);
    }

    private static TeacherGradeBookController controller(TeacherCourseService service,
            TeacherFileTransport transport, TeacherGradeImportController.FileDialogs dialogs) {
        TeacherGradeBookController controller = new TeacherGradeBookController(service, transport,
                Runnable::run, message -> confirmationAnswer(), dialogs);
        // 无工具包环境里不能真的建 JavaFX 弹窗节点；展示器换成记录器，弹窗自身的结构由
        // importViewsDeclareTheirControllerIdsHandlersAndStyles 断言。
        controller.importController().setFeedbackPresenter(preview -> { });
        return controller;
    }

    /** 离开确认的固定回答：允许离开。 */
    private static boolean confirmationAnswer() {
        return true;
    }

    /** 导入链在真实后台线程池里跑，测试必须等它落定。 */
    private static void settle(TeacherGradeBookController controller) {
        TeacherGradeImportController imports = controller.importController();
        waitUntil(() -> !imports.busy(),
                "导入流程没有在超时前落定（busy=" + imports.busy() + "）");
    }

    private static void waitUntil(BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 10000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError(message);
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(message);
            }
        }
    }

    /** 候选：以导入前的编辑内容为底，替换一行学生的分数（其余行原样）。 */
    private static GradeBookContentDTO candidateFor(TeacherGradeBookController controller,
            String enrollmentId, GradeScoresDTO scores) {
        GradeBookContentDTO base = controller.model().content();
        List<GradeRowInputDTO> rows = new ArrayList<>();
        for (GradeRowInputDTO row : base.getRows()) {
            rows.add(row.getEnrollmentId().equals(enrollmentId)
                    ? new GradeRowInputDTO(enrollmentId, scores) : row);
        }
        return new GradeBookContentDTO(base.getOfferingId(), base.getExpectedRevision(),
                base.getRosterDigest(), base.getScheme(), rows);
    }

    private static GradeImportPreviewDTO preview(int revision, GradeBookContentDTO candidate,
            int totalRows, int errorRows, List<GradeImportRowIssueDTO> issues) {
        int valid = totalRows - errorRows;
        return new GradeImportPreviewDTO(TOKEN, revision, candidate, totalRows, valid, errorRows,
                issues, "2026-09-16T00:10:00Z");
    }

    private static GradeImportRowIssueDTO issue(int rowNumber, String uid, String name, String field,
            String raw, String message) {
        return new GradeImportRowIssueDTO(rowNumber, uid, name, field, raw, message, false);
    }

    private static TeacherFileTicketDTO ticketDto() {
        return new TeacherFileTicketDTO(UPLOAD_TICKET, TeacherFileTicketDTO.DIRECTION_UPLOAD,
                8889, 12L, 5L * 1024 * 1024,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "2026-09-16T00:10:00Z");
    }

    /** 恢复快照会重建编辑行，因此断言前必须按学籍号重新取一次当前行。 */
    private static Row rowFor(TeacherGradeBookController controller, String enrollmentId) {
        for (Row row : controller.rows()) {
            if (row.enrollmentId().equals(enrollmentId)) return row;
        }
        throw new AssertionError("成绩表里没有这个学生: " + enrollmentId);
    }

    /** 两张视图里的行是不是同一批对象（逐位比较身份）。 */
    private static boolean sameRowObjects(List<Row> before, List<Row> after) {
        if (before.size() != after.size()) return false;
        for (int index = 0; index < before.size(); index++) {
            if (before.get(index) != after.get(index)) return false;
        }
        return true;
    }

    private static void verifyBindings(Document view, Class<?> controller, String label) {
        NodeList elements = view.getElementsByTagName("*");
        int ids = 0;
        int actions = 0;
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            String id = element.getAttribute("fx:id");
            if (!id.isEmpty()) {
                ids++;
                require(findField(controller, id) != null,
                        label + " 的 fx:id=\"" + id + "\" 没有对应字段");
            }
            String action = element.getAttribute("onAction");
            if (action.startsWith("#")) action = action.substring(1);
            if (!action.isEmpty()) {
                actions++;
                require(hasActionMethod(controller, action),
                        label + " 的 onAction=\"#" + action + "\" 没有对应处理函数");
            }
        }
        require(ids > 0 && actions > 0,
                label + " 必须声明 fx:id 与 onAction，收到 " + ids + " ids / " + actions + " actions");
    }

    private static Document parseView(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream = TeacherGradeImportControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = TeacherGradeImportControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Element textOfButton(Document view, String text) {
        NodeList buttons = view.getElementsByTagName("Button");
        for (int index = 0; index < buttons.getLength(); index++) {
            Element button = (Element) buttons.item(index);
            if (text.equals(button.getAttribute("text"))) return button;
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) return field;
            }
        }
        return null;
    }

    private static boolean hasActionMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterCount() == 0) return true;
                if (method.getParameterCount() == 1
                        && javafx.event.Event.class.isAssignableFrom(
                                method.getParameterTypes()[0])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 文件选择替身：记录调用线程与顺序，返回脚本化的路径。 */
    private static final class FakeDialogs implements TeacherGradeImportController.FileDialogs {
        private final List<Thread> callingThreads = new ArrayList<>();
        private Path uploadSource;
        private Path saveTarget;

        private FakeDialogs() {
            try {
                uploadSource = Files.createTempFile("vcampus-import-source", ".xlsx");
                Files.write(uploadSource, new byte[] {1, 2, 3, 4});
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        private FakeDialogs(Path saveTarget) {
            this();
            this.saveTarget = saveTarget;
        }

        private static FakeDialogs savingTo(Path saveTarget) {
            return new FakeDialogs(saveTarget);
        }

        @Override
        public Path chooseUploadSource() {
            callingThreads.add(Thread.currentThread());
            return uploadSource;
        }

        @Override
        public Path chooseSaveTarget(String suggestedFileName) {
            callingThreads.add(Thread.currentThread());
            return saveTarget;
        }
    }

    /** 传输替身：记录票据/线程，可把上传挂在半空以模拟「导入途中」。 */
    private static final class FakeTransport implements TeacherFileTransport {
        private TeacherFileTicketDTO lastTicket;
        private Thread callingThread;
        private CompletableFuture<Void> uploadFuture;
        private boolean hold;
        private boolean holdDownload;
        private CompletableFuture<Void> downloadFuture;
        /** 可选的调用顺序记录：证明覆盖确认排在票据与传输之前。 */
        private List<String> order;

        @Override
        public CompletableFuture<Void> upload(TeacherFileTicketDTO ticket, Path file) {
            lastTicket = ticket;
            callingThread = Thread.currentThread();
            if (order != null) order.add("transfer");
            uploadFuture = hold ? new CompletableFuture<>() : CompletableFuture.completedFuture(null);
            return uploadFuture;
        }

        @Override
        public CompletableFuture<Void> download(TeacherFileTicketDTO ticket, Path file) {
            lastTicket = ticket;
            callingThread = Thread.currentThread();
            if (order != null) order.add("transfer");
            downloadFuture = holdDownload ? new CompletableFuture<>()
                    : CompletableFuture.completedFuture(null);
            return downloadFuture;
        }
    }

    /**
     * 记录型服务：读路径委托给确定性 mock（真实的 24 人成绩表夹具），导入链路的每一步都可以
     * 脚本化返回、挂起或失败，并记录客户端真正发了什么。
     */
    private static final class ImportService implements TeacherCourseService {
        private final MockTeacherCourseService delegate = new MockTeacherCourseService();
        private final Deque<CompletableFuture<GradeImportPreviewDTO>> previewResponses =
                new ArrayDeque<>();
        private GradeImportPreviewDTO previewResponse;
        private RuntimeException confirmFailure;
        private TeacherOperationResultDTO<TeacherGradeBookDTO> confirmResult;
        private final List<ReviseGradeImportRequestDTO> revises = new ArrayList<>();
        private final List<ConfirmGradeImportRequestDTO> confirms = new ArrayList<>();
        private final List<WriteGradeBookRequestDTO> saved = new ArrayList<>();
        private final List<WriteGradeBookRequestDTO> submits = new ArrayList<>();
        private final List<String> cancelledTokens = new ArrayList<>();
        private final List<String[]> exports = new ArrayList<>();
        private List<String> ticketOrder = new ArrayList<>();
        private TeacherFileUploadRequestDTO upload;
        private PreviewGradeImportRequestDTO previewRequest;
        private boolean uploadRequested;
        private CompletableFuture<TeacherFileTicketDTO> heldUploadTicket;

        @Override
        public CompletableFuture<List<CourseTermDTO>> listTerms() {
            return delegate.listTerms();
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
                int academicYear, int semester, String query, int page, int size) {
            return delegate.listOfferings(academicYear, semester, query, page, size);
        }

        @Override
        public CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId) {
            return delegate.getOffering(offeringId);
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
                String offeringId, String query, Integer enrollmentStatus, int page, int size) {
            return delegate.listOfferingStudents(offeringId, query, enrollmentStatus, page, size);
        }

        @Override
        public CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(
                String offeringId) {
            return delegate.listOfferingSchedules(offeringId);
        }

        @Override
        public CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
                int academicYear, int semester, Integer week) {
            return delegate.loadTeachingSchedule(academicYear, semester, week);
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherGradeOfferingDTO>> listGradeOfferings(
                int academicYear, int semester, int page, int size) {
            return delegate.listGradeOfferings(academicYear, semester, page, size);
        }

        @Override
        public CompletableFuture<TeacherGradeBookDTO> getGradeBook(String offeringId) {
            return delegate.getGradeBook(offeringId);
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> saveGradeDraft(
                WriteGradeBookRequestDTO write) {
            saved.add(write);
            return delegate.saveGradeDraft(write);
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> submitGradeBook(
                WriteGradeBookRequestDTO write) {
            submits.add(write);
            return delegate.submitGradeBook(write);
        }

        @Override
        public CompletableFuture<TeacherFileTicketDTO> requestGradeTemplate(String offeringId) {
            ticketOrder.add("ticket");
            return CompletableFuture.completedFuture(ticketDto());
        }

        @Override
        public CompletableFuture<TeacherFileTicketDTO> beginGradeUpload(
                TeacherFileUploadRequestDTO request) {
            upload = request;
            uploadRequested = true;
            if (heldUploadTicket != null) return heldUploadTicket;
            return CompletableFuture.completedFuture(ticketDto());
        }

        @Override
        public CompletableFuture<TeacherFileTicketDTO> requestRosterExport(
                String offeringId, String query, Integer enrollmentStatus) {
            exports.add(new String[] {offeringId, query,
                    enrollmentStatus == null ? null : enrollmentStatus.toString()});
            return CompletableFuture.completedFuture(ticketDto());
        }

        /** 把上传票据那张请求挂住，用来验证「票据还没回来时离开」的窗口。 */
        private void holdUploadTicket() {
            heldUploadTicket = new CompletableFuture<>();
        }

        private void completeHeldUploadTicket() {
            if (heldUploadTicket == null) throw new AssertionError("没有挂起的上传票据请求");
            heldUploadTicket.complete(ticketDto());
        }

        @Override
        public CompletableFuture<GradeImportPreviewDTO> previewGradeImport(
                PreviewGradeImportRequestDTO request) {
            previewRequest = request;
            if (previewResponses.isEmpty()) {
                return CompletableFuture.completedFuture(previewResponse);
            }
            return previewResponses.removeFirst();
        }

        @Override
        public CompletableFuture<GradeImportPreviewDTO> reviseGradeImport(
                ReviseGradeImportRequestDTO request) {
            revises.add(request);
            if (previewResponses.isEmpty()) {
                return CompletableFuture.completedFuture(previewResponse);
            }
            return previewResponses.removeFirst();
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> confirmGradeImport(
                ConfirmGradeImportRequestDTO request) {
            confirms.add(request);
            if (confirmFailure != null) {
                CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> failed =
                        new CompletableFuture<>();
                failed.completeExceptionally(confirmFailure);
                return failed;
            }
            return CompletableFuture.completedFuture(confirmResult);
        }

        @Override
        public CompletableFuture<Void> cancelGradeImport(String importToken) {
            cancelledTokens.add(importToken);
            return CompletableFuture.completedFuture(null);
        }
    }
}
