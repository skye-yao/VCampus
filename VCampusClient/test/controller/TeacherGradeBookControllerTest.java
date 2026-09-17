package controller;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import app.ClientMain;
import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.StartGradeRevisionRequestDTO;
import dto.course.teacher.TeacherFileTicketDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import javafx.event.ActionEvent;
import model.course.teacher.GradeBookEditorModel.Row;
import protocol.MessageCode;
import service.MockTeacherCourseService;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;
import service.TeacherFileTransport;
import util.PageLeaveGuard;

/**
 * 成绩编辑表页面（设计 §5.4）的无工具包测试：编辑/保存/提交的状态机、离开保护、窗体关闭时
 * 的取消，以及两个新 FXML 的结构契约。
 *
 * <p>重点覆盖界面最容易悄悄做错的三件事：非法或半成品单元格绝不能作为 0 分或缺分发送；
 * 保存失败必须保留用户已经输入的内容；提交要二次确认且重复点击只发一个请求（同一个 operationId）。
 * 另外断言“取消离开”会消费关闭事件，而不只是弹了一个框。
 */
public final class TeacherGradeBookControllerTest {
    private static final String OFFERING = "9007199254740993";
    private static final String PENDING_OFFERING = "9007199254740997";
    private static final String REJECTED_OFFERING = "9007199254740999";
    private static final String EMPTY_OFFERING = "9007199254740995";
    /**
     * 已通过批次的代码内夹具：mock 的成绩夹具刻意不种“已通过”这一种（2025/3 与 2025/2 各只有
     * 两个教学班，冒烟测试逐条钉住了这两个列表），所以这里由假服务直接给出这份快照。
     */
    private static final String APPROVED_OFFERING = "9007199254740901";
    /** 刻意不等于被驳回夹具的批次号（{@code MockTeacherCourseService} 用的是 "9601"）：两者相同会让
     *  “重开不改变最后一次批次”的断言退化成同一个字符串比两次，从而永远为真。 */
    private static final String APPROVED_BATCH = "9701";
    private static final String APPROVED_ENROLLMENT = "9001";
    /** 被驳回夹具那一次提交的批次号，与 {@code MockTeacherCourseService.GRADE_SUBMISSION_ID} 一致。 */
    private static final String REJECTED_BATCH = "9601";
    /** 与 MockTeacherCourseService 的已驳回夹具一致：只读提示里应出现这句话。 */
    private static final String REJECTED_REVIEW_COMMENT = "总分与平时分不一致，请核对后重新提交";
    private static final String GRADE_VIEW = "/resources/fxml/TeacherGradeView.fxml";
    private static final String GRADE_BOOK_VIEW = "/resources/fxml/TeacherGradeBookView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";

    private TeacherGradeBookControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        loadingShowsServerScoresAndKeepsBlanks();
        liveEditsReachTheModelWithoutASecondConfirmation();
        bulkPasteIsClampedToTheRosterAndTheEditableColumns();
        invalidCellBlocksTheWriteAndKeepsTheTypedText();
        invalidWeightNamesTheComponentAndMarksTheField();
        failedSaveKeepsTheEditsAndTheDirtyState();
        submitNeedsASecondClickAndOnlySendsOneRequest();
        incompleteWeightsBlockSubmitWithTheSharedRule();
        cancelledLeaveKeepsThePageAndRefusedCloseIsConsumed();
        allowedLeaveReleasesThePageAndClearsTheGuard();
        releasedPageIgnoresLateResponses();
        readOnlyBookShowsTheReviewStateAndBlocksWrites();
        rejectedBookOffersTheExplicitReopenEntry();
        aFailedReopenRetriesWithTheSameOperationId();
        pendingBookOffersNoEditableVersionEntry();
        approvedBookIsReachableOnlyThroughTheCorrectionEntry();
        theCorrectionEntryCarriesTheProposedScoresThroughTheOrdinarySave();
        aCorrectionForAStudentWhoLeftTheRosterWritesNothing();
        exportingADirtyPageConfirmsFirstAndSendsNothingWhenRefused();
        gradeViewsDeclareTheirControllerIdsAndHandlers();
        theStatusLineSitsOnTheButtonRow();
        everyStyleClassExistsInTheStylesheet();
        System.out.println("TeacherGradeBookControllerTest: PASS");
    }

    // ------------------------------------------------------------------ 加载与编辑

    /** 载入真实 mock 夹具：分数来自服务端、未录入保持空、缺启用项分数的行没有总评。 */
    private static void loadingShowsServerScoresAndKeepsBlanks() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);

        controller.showOffering(OFFERING);

        require(controller.active(), "打开教学班后页面必须处于激活状态");
        require(controller.rows().size() == 24,
                "只加载正常修读的学生（退课历史不参与成绩编辑），收到 "
                        + controller.rows().size());
        require(controller.model().canEdit(), "草稿夹具必须是可编辑的");
        require(controller.tableRowCount() == controller.rows().size(),
                "渲染后表格行数必须与模型一致");
        Row first = controller.rows().get(0);
        require("70".equals(first.cell(GradeComponentCodeDTO.DAILY).text()),
                "分数原文来自服务端快照，收到 " + first.cell(GradeComponentCodeDTO.DAILY).text());
        Row withMissingExperiment = missingExperimentRow(controller);
        require("".equals(withMissingExperiment.cell(GradeComponentCodeDTO.EXPERIMENT).text()),
                "缺分必须保持空白，不能显示 0");
        require(!withMissingExperiment.cell(GradeComponentCodeDTO.EXPERIMENT).entered(),
                "缺分不是“已录入”");
        require(controller.model().rowTotal(withMissingExperiment) == null,
                "缺启用项分数时该行没有总评");
        require(!controller.dirty(), "刚加载的页面不是 dirty");

        TeacherGradeBookController empty = controller(new RecordingService(), message -> true);
        empty.showOffering(EMPTY_OFFERING);
        require(empty.rows().isEmpty(), "空班必须渲染成 0 行");
    }

    /**
     * 输入实时生效：每个键都直接写进模型，派生列（总评）与非法判定立刻跟上，中间没有任何
     * “还要按一次回车确认”的动作；{@code Esc} 才把这一格恢复成编辑前的原文。
     *
     * <p>这是 Excel 式录入的核心契约：模型里永远是最新的输入，所以“离开单元格”只需要收起编辑器。
     */
    private static void liveEditsReachTheModelWithoutASecondConfirmation() {
        TeacherGradeBookController controller = controller(new RecordingService(), message -> true);
        controller.showOffering(OFFERING);
        completeScheme(controller);
        Row row = missingExperimentRow(controller);
        String enrollmentId = row.enrollmentId();

        // 缺实验分时总评是占位符（预览绝不伪造数字）。
        controller.liveScoreEdit(row, GradeComponentCodeDTO.EXPERIMENT, "90");
        require("90".equals(row.cell(GradeComponentCodeDTO.EXPERIMENT).text()),
                "实时写入必须立刻落在模型上，收到 " + row.cell(GradeComponentCodeDTO.EXPERIMENT));
        require(controller.dirty(), "实时写入就是未保存的修改");
        require(controller.model().rowTotal(row) != null,
                "补齐缺分之后总评必须立刻能算出来");

        // 超界分数：原文保留、立刻判为非法并挡住保存——只标红，不弹窗、不要用户再确认一次。
        controller.liveScoreEdit(row, GradeComponentCodeDTO.EXPERIMENT, "105");
        require("105".equals(row.cell(GradeComponentCodeDTO.EXPERIMENT).text()),
                "超界分数必须原样留在格子里");
        require(row.cell(GradeComponentCodeDTO.EXPERIMENT).error() != null,
                "满分 100 时 105 必须立刻判为非法");
        require(controller.model().saveBlockReason() != null, "非法输入必须挡住保存");
        require(controller.model().rowTotal(row) == null, "非法分不能参与总评");

        // Esc：恢复编辑前的原文。
        controller.revertScore(row, GradeComponentCodeDTO.EXPERIMENT, "90");
        require("90".equals(row.cell(GradeComponentCodeDTO.EXPERIMENT).text()),
                "Esc 必须把这一格恢复成编辑前的原文");
        require(controller.model().saveBlockReason() == null, "恢复之后又能保存了");

        // 空格子等于“未录入”，不是 0 分。
        controller.liveScoreEdit(row, GradeComponentCodeDTO.EXPERIMENT, "");
        require(!row.cell(GradeComponentCodeDTO.EXPERIMENT).entered(),
                "清空这一格必须是未录入，而不是 0 分");
        require(enrollmentId.equals(row.enrollmentId()), "行身份不能被编辑改掉");
    }

    /**
     * 批量粘贴：从当前格向右下铺开，行夹在名单长度内、列只落在四个成绩列里可编辑的那些上；
     * 空格子照写（等于清空目标格），非法值原样留下并挡住保存——绝不静默丢弃。
     */
    private static void bulkPasteIsClampedToTheRosterAndTheEditableColumns() {
        // 落点规划本身是纯计算：1×4 的块从最后一列出发时右边一格无处可去，必须被夹住。
        List<TeacherGradeBookController.PasteTarget> clamped =
                TeacherGradeBookController.planPaste(0, 3, List.of(List.of("80", "81")), 24,
                        List.of(true, true, true, true));
        require(clamped.size() == 1 && clamped.get(0).code() == GradeComponentCodeDTO.FINALTERM,
                "越出四个成绩列的格子必须被丢弃，收到 " + clamped);
        List<TeacherGradeBookController.PasteTarget> overRoster =
                TeacherGradeBookController.planPaste(23, 0, List.of(List.of("80"), List.of("81")), 24,
                        List.of(true, true, true, true));
        require(overRoster.size() == 1, "超出名单长度的行必须被丢弃，收到 " + overRoster);
        List<TeacherGradeBookController.PasteTarget> skipped =
                TeacherGradeBookController.planPaste(0, 1, List.of(List.of("80", "81", "82")), 24,
                        List.of(true, true, false, true));
        require(skipped.size() == 2
                        && skipped.get(1).code() == GradeComponentCodeDTO.FINALTERM,
                "禁用列必须跳过不写，收到 " + skipped);

        // 真的粘一次：2×2 从期中开始，值落在 期中/实验 两列、前两行。
        TeacherGradeBookController controller = controller(new RecordingService(), message -> true);
        controller.showOffering(OFFERING);
        controller.pasteScoreBlock(controller.rows().get(0), GradeComponentCodeDTO.MIDTERM,
                "88\t77\r\n66\t55\r\n");
        require("88".equals(
                        controller.rows().get(0).cell(GradeComponentCodeDTO.MIDTERM).text())
                        && "77".equals(
                        controller.rows().get(0).cell(GradeComponentCodeDTO.EXPERIMENT).text())
                        && "66".equals(
                        controller.rows().get(1).cell(GradeComponentCodeDTO.MIDTERM).text())
                        && "55".equals(
                        controller.rows().get(1).cell(GradeComponentCodeDTO.EXPERIMENT).text()),
                "2×2 块必须向右下铺开，实际 " + controller.rows().get(0).cell(
                        GradeComponentCodeDTO.MIDTERM).text() + "/"
                        + controller.rows().get(0).cell(GradeComponentCodeDTO.EXPERIMENT).text()
                        + "/" + controller.rows().get(1).cell(
                        GradeComponentCodeDTO.MIDTERM).text() + "/"
                        + controller.rows().get(1).cell(
                        GradeComponentCodeDTO.EXPERIMENT).text());
        require(controller.rows().get(0).cell(GradeComponentCodeDTO.DAILY).text().equals("70"),
                "粘贴不得碰起点左边的格子");

        // 非法值照样落进格子里并挡住保存：粘贴不是把它们丢掉的借口。
        controller.pasteScoreBlock(controller.rows().get(2), GradeComponentCodeDTO.DAILY, "300\n");
        require("300".equals(
                        controller.rows().get(2).cell(GradeComponentCodeDTO.DAILY).text()),
                "粘贴进来的非法值必须原样保留");
        require(controller.model().saveBlockReason() != null, "粘贴进来的非法值必须挡住保存");

        // 块里的空格子 = 清空：一列成绩里常见的“这一格没有”，不能理解成“跳过这一格”。
        controller.pasteScoreBlock(controller.rows().get(0), GradeComponentCodeDTO.DAILY, "60\t");
        require("60".equals(controller.rows().get(0).cell(GradeComponentCodeDTO.DAILY).text())
                        && !controller.rows().get(0).cell(GradeComponentCodeDTO.MIDTERM).entered(),
                "块里的空格子必须把目标格清空（未录入），而不是跳过，实际 "
                        + controller.rows().get(0).cell(GradeComponentCodeDTO.DAILY).text() + "/"
                        + controller.rows().get(0).cell(GradeComponentCodeDTO.MIDTERM).text());

        // 剪贴板本来就是空的：什么都不该发生（不是“把所有格子清空”）。
        String before = controller.rows().get(1).cell(GradeComponentCodeDTO.DAILY).text();
        controller.pasteScoreBlock(controller.rows().get(1), GradeComponentCodeDTO.DAILY, "");
        require(before.equals(controller.rows().get(1).cell(GradeComponentCodeDTO.DAILY).text()),
                "空剪贴板不得改动任何单元格");
    }

    /** 非法文本：原文保留、本地挡住写请求、一个字节都不发给服务端，并指出是哪个格子。 */
    private static void invalidCellBlocksTheWriteAndKeepsTheTypedText() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(OFFERING);

        controller.model().setScore(controller.rows().get(0).enrollmentId(),
                GradeComponentCodeDTO.DAILY, "88.");
        controller.save();

        require(service.saves.isEmpty(),
                "非法单元格必须让保存一个请求都不发出，收到 " + service.saves.size());
        require("88.".equals(controller.rows().get(0).cell(GradeComponentCodeDTO.DAILY).text()),
                "非法文本必须原样留在输入框里");
        require(controller.feedbackText() != null && controller.feedbackText().contains("非法"),
                "非法输入必须给出可读的提示，收到 " + controller.feedbackText());
        require(controller.feedbackText().contains("平时"),
                "提示必须点名是哪一个组成，收到 " + controller.feedbackText());
        require(controller.dirty(), "非法输入仍然是未保存的修改");

        controller.confirmSubmit();
        require(service.submits.isEmpty(), "非法单元格同样挡住提交");
    }

    /**
     * 非法权重：提示必须点名组成，并且只有出错的那一列会被标红。
     *
     * <p>标红的决定（{@link TeacherGradeBookController#weightFieldHasError}）在这里断言；控件上的
     * 样式类本身要有 JavaFX 工具包才能构造，属于 T6 的 GUI 列（与“表格单元格的接线没有直接测试”
     * 同一类限制）。
     */
    private static void invalidWeightNamesTheComponentAndMarksTheField() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(OFFERING);

        controller.model().setWeightText(GradeComponentCodeDTO.FINALTERM, "abc");
        controller.model().setWeightText(GradeComponentCodeDTO.EXPERIMENT, "20");
        controller.save();

        require(service.saves.isEmpty(), "非法权重必须挡住保存");
        require(controller.feedbackText() != null && controller.feedbackText().contains("期末"),
                "提示必须点名是哪一个组成，收到 " + controller.feedbackText());
        require(TeacherGradeBookController.weightFieldHasError(
                        controller.model().column(GradeComponentCodeDTO.FINALTERM)),
                "出错的权重列必须被标记（界面据此标红）");
        require(!TeacherGradeBookController.weightFieldHasError(
                        controller.model().column(GradeComponentCodeDTO.DAILY)),
                "没有出错的那一列不能被标记");

        // 禁用列里的残留非法文本不会进入请求，因此也不该让用户去修它。
        controller.model().setEnabled(GradeComponentCodeDTO.FINALTERM, false);
        require(!TeacherGradeBookController.weightFieldHasError(
                        controller.model().column(GradeComponentCodeDTO.FINALTERM)),
                "禁用的列不标记：它的权重按 0 发送，残留文本不生效");
        require(controller.model().saveBlockReason() == null,
                "禁用该列之后草稿又可以保存了，收到 " + controller.model().saveBlockReason());

        // 改正之后不再标记。
        controller.model().setEnabled(GradeComponentCodeDTO.FINALTERM, true);
        controller.model().setWeightText(GradeComponentCodeDTO.FINALTERM, "30");
        require(!TeacherGradeBookController.weightFieldHasError(
                        controller.model().column(GradeComponentCodeDTO.FINALTERM)),
                "改正之后不能再标记");
    }

    /** 保存失败保留编辑：内容与 dirty 都不变，只显示失败原因。 */
    private static void failedSaveKeepsTheEditsAndTheDirtyState() {
        RecordingService service = new RecordingService();
        service.saveFailure = new TeacherCourseServiceException(MessageCode.ERROR, "服务端内部错误");
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(OFFERING);
        String enrollmentId = controller.rows().get(0).enrollmentId();
        int revisionBefore = controller.model().revision();

        controller.model().setScore(enrollmentId, GradeComponentCodeDTO.DAILY, "95.5");
        controller.save();

        require(service.saves.size() == 1, "失败的保存确实发出过一次请求");
        require("95.5".equals(
                        controller.rows().get(0).cell(GradeComponentCodeDTO.DAILY).text()),
                "保存失败后用户输入必须原样保留");
        require(controller.dirty(), "保存失败后仍然是未保存状态");
        require(controller.model().revision() == revisionBefore,
                "保存失败不能推进本地版本");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("已保留"),
                "失败提示必须说明修改被保留，收到 " + controller.feedbackText());

        // 重试复用同一个 operationId：响应丢失时服务端会重放，而不是产生第二份写入。
        controller.save();
        require(service.saves.size() == 2 && service.saves.get(0).getOperationId()
                        .equals(service.saves.get(1).getOperationId()),
                "同一次保存流程重试必须复用 operationId");

        // 成功之后编辑内容被服务端快照取代，dirty 清零，且下一次保存用新的 operationId。
        service.saveFailure = null;
        controller.save();
        require(!controller.dirty(), "保存成功后必须回到干净状态");
        require("95.5".equals(
                        controller.rows().get(0).cell(GradeComponentCodeDTO.DAILY).text()),
                "保存成功后显示服务端返回的值");
        controller.model().setScore(enrollmentId, GradeComponentCodeDTO.DAILY, "96");
        controller.save();
        require(!service.saves.get(2).getOperationId()
                        .equals(service.saves.get(3).getOperationId()),
                "保存成功后必须换一个新的 operationId");
    }

    /** 提交：第一次点击只是确认，第二次才发请求；在途期间重复点击只发一个。 */
    private static void submitNeedsASecondClickAndOnlySendsOneRequest() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(OFFERING);
        completeScheme(controller);
        fillEveryScore(controller);

        controller.requestSubmit();
        require(controller.confirmingSubmit(), "第一次点击必须进入确认态");
        require(service.submits.isEmpty(), "确认态不能发出任何请求");

        service.holdSubmit();
        controller.confirmSubmit();
        require(controller.submitting(), "确认后必须处于提交中");
        require(service.submits.size() == 1,
                "确认一次只能发一个提交请求，收到 " + service.submits.size());

        controller.confirmSubmit();
        controller.requestSubmit();
        require(service.submits.size() == 1,
                "提交在途时重复点击不能再发请求，收到 " + service.submits.size());
        require(service.submits.get(0).getOperationId()
                        .equals(controller.pendingSubmitOperationId()),
                "在途提交的 operationId 必须是这一次确认里生成的那一个");

        service.finishSubmit();
        require(!controller.submitting(), "服务端返回后必须结束提交中状态");
        require("PENDING".equals(controller.model().state()),
                "提交成功后状态必须变成待审核，收到 " + controller.model().state());
        require(!controller.model().canEdit(), "提交成功后成绩表必须变成只读");
        require(!controller.dirty(), "提交成功后没有未保存的修改");
        require(controller.pendingSubmitOperationId() == null,
                "提交成功后必须作废这次流程的 operationId");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("已提交"),
                "提交成功必须给出反馈，收到 " + controller.feedbackText());

        // 取消确认不发送请求，也不改变编辑内容。
        service.submits.clear();
        TeacherGradeBookController second = controller(new RecordingService(), message -> true);
        second.showOffering(OFFERING);
        completeScheme(second);
        fillEveryScore(second);
        second.requestSubmit();
        second.cancelSubmit();
        require(!second.confirmingSubmit(), "取消后必须退出确认态");
        require(second.pendingSubmitOperationId() == null, "取消必须作废这次确认的幂等 ID");
    }

    /** 权重未配齐：提交被共享计算规则挡住并说明原因，草稿保存不受影响。 */
    private static void incompleteWeightsBlockSubmitWithTheSharedRule() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(EMPTY_OFFERING);

        controller.model().setWeightText(GradeComponentCodeDTO.DAILY, "60");
        controller.requestSubmit();
        controller.confirmSubmit();

        require(service.submits.isEmpty(), "权重未配齐不能发出提交请求");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("权重"),
                "必须说明是权重问题，收到 " + controller.feedbackText());
        require(!controller.confirmingSubmit(), "被本地校验拒绝后必须退出确认态");

        controller.save();
        require(service.saves.size() == 1, "草稿仍然允许保存未配齐的权重");
    }

    // ------------------------------------------------------------------ 离开保护

    /** 有未保存修改时取消离开：守卫返回 false，页面留在原地，关闭事件被消费。 */
    private static void cancelledLeaveKeepsThePageAndRefusedCloseIsConsumed() {
        List<String> asked = new ArrayList<>();
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> {
            asked.add(message);
            return false;
        });
        try {
            controller.showOffering(OFFERING);
            controller.model().setScore(controller.rows().get(0).enrollmentId(),
                    GradeComponentCodeDTO.DAILY, "91");
            require(PageLeaveGuard.active() == controller,
                    "页面成为当前页后必须注册为唯一的活动守卫");

            ActionEvent closeEvent = new ActionEvent();
            ClientMain.requestWindowClose(closeEvent);

            require(closeEvent.isConsumed(),
                    "页面拒绝离开时关闭事件必须被消费掉");
            require(controller.active(), "被拒绝的关闭不能让页面进入关闭状态");
            require(PageLeaveGuard.active() == controller, "被拒绝后守卫仍然有效");
            require(asked.size() == 1 && asked.get(0).contains("未保存"),
                    "必须问过一次并说明未保存的修改，收到 " + asked);

            // 没有修改时不再询问，直接放行。
            controller.model().applyServerSnapshot(service.bookSnapshot(OFFERING));
            require(!controller.dirty(), "快照后应回到干净状态");
            require(controller.requestLeave(), "没有未保存修改时必须直接允许离开");
            require(asked.size() == 1, "干净状态下不应该再弹确认，收到 " + asked);
        } finally {
            PageLeaveGuard.clear();
        }
    }

    /**
     * 同意离开：守卫放行后页面释放、取消在途请求，再由调用方注销（工作台/场景切换负责 clear）。
     *
     * <p><b>窗口关闭的放行路径没有被任何测试覆盖</b>：{@code ClientMain.requestWindowClose} 在守卫
     * 放行之后要弹退出确认框、再走登出与 {@code System.exit(0)}；本类是无工具包的测试，既弹不出框，
     * 按下“确定”还会把测试 JVM 关掉。被拒绝的那一半（消费关闭事件、页面留在原地）在
     * {@link #cancelledLeaveKeepsThePageAndRefusedCloseIsConsumed} 里。
     */
    private static void allowedLeaveReleasesThePageAndClearsTheGuard() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        try {
            controller.showOffering(OFFERING);
            controller.model().setScore(controller.rows().get(0).enrollmentId(),
                    GradeComponentCodeDTO.DAILY, "91");

            require(controller.requestLeave(), "用户确认后页面守卫必须允许离开");
            controller.onClosed();
            PageLeaveGuard.clear(controller);
            require(!controller.active(), "允许离开后页面必须被释放");
            require(PageLeaveGuard.active() == null, "释放后不能留下全局守卫");
        } finally {
            PageLeaveGuard.clear();
        }
    }

    /** 页面被释放后，迟到在途响应不能写界面，也不能把编辑内容换成服务端的旧快照。 */
    private static void releasedPageIgnoresLateResponses() {
        RecordingService service = new RecordingService();
        CompletableFuture<TeacherGradeBookDTO> pending = new CompletableFuture<>();
        service.pendingBook = pending;
        TeacherGradeBookController controller = controller(service, message -> true);

        controller.showOffering(OFFERING);
        controller.release();
        pending.complete(service.bookSnapshot(OFFERING));

        require(controller.model() == null,
                "释放后的迟到响应不能构建编辑模型（页面已不显示）");
        require(!controller.active(), "释放之后页面保持非激活");
    }

    /** 只读状态（待审核）：显示审核状态、禁用两个写入入口，也不注册成可写的编辑页。 */
    private static void readOnlyBookShowsTheReviewStateAndBlocksWrites() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(PENDING_OFFERING);

        require(!controller.model().canEdit(), "待审核的教学班必须只读");
        require(controller.model().stateNotice() != null
                        && controller.model().stateNotice().contains("待审核"),
                "只读状态必须显示审核状态，收到 " + controller.model().stateNotice());
        controller.save();
        controller.requestSubmit();
        controller.confirmSubmit();
        require(service.saves.isEmpty() && service.submits.isEmpty(),
                "只读状态下两个写入口都不能发请求");
        require(!controller.dirty(), "只读状态下没有可保存的修改");

        // 被驳回：可编辑，但管理员的审核意见必须显示出来（这才是教师需要看到的那句话）。
        RecordingService rejectedService = new RecordingService();
        TeacherGradeBookController rejected = controller(rejectedService, message -> true);
        rejected.showOffering(REJECTED_OFFERING);
        require(rejected.model().canEdit(), "被驳回的草稿必须仍然可编辑");
        require(REJECTED_REVIEW_COMMENT.equals(rejected.model().reviewComment()),
                "审核意见必须从服务端快照映射到模型，收到 " + rejected.model().reviewComment());
        require(rejected.model().stateNotice() != null
                        && rejected.model().stateNotice().contains(REJECTED_REVIEW_COMMENT),
                "被驳回时必须显示审核意见，收到 " + rejected.model().stateNotice());
        require(rejected.model().stateNotice().contains("重新提交"),
                "被驳回时还要说明可以改后重提，收到 " + rejected.model().stateNotice());

        // 保存一次之后草稿重开（state 回到 DRAFT）：教师正在照着意见改，审核意见必须还在。
        // 该夹具的名单为空，因此用一次真实的权重修改来产生编辑内容。
        rejected.model().setWeightText(GradeComponentCodeDTO.DAILY, "35");
        require(rejected.dirty(), "改权重之后必须是未保存状态");
        rejected.save();
        require(!rejectedService.saves.isEmpty(), "被驳回的草稿必须可以保存");
        require("DRAFT".equals(rejected.model().state()) && rejected.model().canEdit(),
                "保存之后草稿重开，收到 " + rejected.model().state());
        require(rejected.model().stateNotice() != null
                        && rejected.model().stateNotice().contains(REJECTED_REVIEW_COMMENT),
                "重开之后审核意见不能消失，收到 " + rejected.model().stateNotice());
    }

    // ------------------------------------------------------------------ 新的版本入口

    /**
     * 被驳回的批次：显式「重新编辑」。在确认框上取消什么都不发生（不建草稿、不发请求）；确认之后
     * 由服务端按那一批的冻结快照重开草稿，页面据此回到可编辑状态。
     *
     * <p>提示语必须如实描述这套语义：提交时未启用的组成没有进过批次，教师为它们输入的值不会回来。
     * 承诺“未保存的修改都会保留”会是一句与服务器行为矛盾的话。
     */
    private static void rejectedBookOffersTheExplicitReopenEntry() {
        List<String> leaveAsks = new ArrayList<>();
        List<String> reopenAsks = new ArrayList<>();
        RecordingService declining = new RecordingService();
        TeacherGradeBookController cancelled =
                new TeacherGradeBookController(declining, Runnable::run, message -> {
                    leaveAsks.add(message);
                    return false;
                });
        // 重新编辑有自己的确认框：标题是它正在问的那件事，不能借用「未保存的成绩」。
        cancelled.setReopenConfirmation(message -> {
            reopenAsks.add(message);
            return false;
        });
        cancelled.showOffering(REJECTED_OFFERING);

        require(cancelled.canReopenRejected(), "被驳回的批次必须给出重新编辑入口");
        require(!cancelled.canRequestCorrection(), "被驳回不是更正，不能给出申请修改入口");

        cancelled.reopenRejected();

        require(reopenAsks.size() == 1 && leaveAsks.isEmpty(),
                "重新编辑必须走它自己的确认框，而不是离开/重新加载那一个，收到 "
                        + reopenAsks + "/" + leaveAsks);
        require(cancelled.pendingReopenOperationId() == null,
                "在确认框上取消不能消耗任何 operationId");
        require(declining.reopens.isEmpty(), "在确认框上取消不能发出任何请求");
        require("REJECTED".equals(cancelled.model().state()) && !cancelled.dirty(),
                "取消之后批次状态与编辑内容都不能变，收到 " + cancelled.model().state());

        require(TeacherGradeBookController.REOPEN_PROMPT_TEXT.contains("未启用的组成")
                        && TeacherGradeBookController.REOPEN_PROMPT_TEXT.contains("不会回来"),
                "重开提示必须如实说明按被驳回批次重建的语义，收到 "
                        + TeacherGradeBookController.REOPEN_PROMPT_TEXT);

        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(REJECTED_OFFERING);
        int revisionBefore = controller.model().revision();
        require(REJECTED_BATCH.equals(controller.model().lastSubmissionId()),
                "已驳回的夹具必须带着它那一批的批次号，收到 "
                        + controller.model().lastSubmissionId());

        controller.reopenRejected();

        require(service.reopens.size() == 1,
                "确认后必须恰好发一次重开请求，收到 " + service.reopens.size());
        StartGradeRevisionRequestDTO request = service.reopens.get(0);
        require(REJECTED_OFFERING.equals(request.getOfferingId())
                        && REJECTED_BATCH.equals(request.getSourceSubmissionId())
                        && request.getExpectedRevision() == revisionBefore,
                "重开请求必须指向本班最后一次被驳回的批次与当前版本，收到 "
                        + request.getOfferingId() + "/" + request.getSourceSubmissionId() + "/"
                        + request.getExpectedRevision());
        require(request.getReason() == null, "驳回重开不要求原因，也不该伪造一个");

        require("DRAFT".equals(controller.model().state()) && controller.model().canEdit(),
                "重开之后必须回到可编辑的草稿，收到 " + controller.model().state());
        // 只认那个字面量：写成 `A.equals(x) || B.equals(x)` 而两个常量恰好相同，就等于什么都没断言。
        require(REJECTED_BATCH.equals(controller.model().lastSubmissionId()),
                "重开不改变最后一次批次，收到 " + controller.model().lastSubmissionId());
        require(!controller.canReopenRejected(), "已经重开的草稿不能再重开");
        require(controller.pendingReopenOperationId() == null,
                "重开成功之后这一次意图的 operationId 必须作废");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("重新提交"),
                "重开成功必须给出反馈，收到 " + controller.feedbackText());
        require(!controller.dirty(), "重开本身不是未保存的修改");
    }

    /**
     * 重开失败之后重试必须复用同一个 operationId：服务端可能已经打开了草稿，只是响应在网络上丢了，
     * 换一个新 ID 再按一次只会拿到「成绩草稿已经打开」的冲突——而这操作其实早就成功了。
     */
    private static void aFailedReopenRetriesWithTheSameOperationId() {
        RecordingService service = new RecordingService();
        service.reopenFailure = new TeacherCourseServiceException(MessageCode.ERROR, "连接中断");
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(REJECTED_OFFERING);

        controller.reopenRejected();

        require(service.reopens.size() == 1, "第一次确认必须发出一次请求");
        String first = service.reopens.get(0).getOperationId();
        require(first != null && first.equals(controller.pendingReopenOperationId()),
                "在途重开的 operationId 必须是这一次确认里生成的那一个");
        require("REJECTED".equals(controller.model().state()),
                "重开失败不能改变批次状态，收到 " + controller.model().state());
        require(controller.feedbackText() != null && controller.feedbackText().contains("重试"),
                "重开失败必须给出可重试的反馈，收到 " + controller.feedbackText());

        controller.reopenRejected();

        require(service.reopens.size() == 2
                        && first.equals(service.reopens.get(1).getOperationId()),
                "原样重试必须复用同一个 operationId，收到 "
                        + service.reopens.get(1).getOperationId());

        service.reopenFailure = null;
        controller.reopenRejected();
        require("DRAFT".equals(controller.model().state()) && controller.pendingReopenOperationId()
                        == null,
                "成功之后这一次意图的 operationId 必须作废");
    }

    /** 待审核只读：两个版本入口一个都不出现，而且调用它们真的发不出任何请求。 */
    private static void pendingBookOffersNoEditableVersionEntry() {
        RecordingService service = new RecordingService();
        List<Row> opened = new ArrayList<>();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.setCorrectionOpener(opened::add);
        controller.showOffering(PENDING_OFFERING);

        require(!controller.canReopenRejected() && !controller.canRequestCorrection(),
                "待审核的批次一个可编辑入口都不能有");

        controller.reopenRejected();
        controller.beginCorrection();

        require(service.reopens.isEmpty() && service.corrections.isEmpty() && opened.isEmpty(),
                "待审核时两个版本入口都不能发出任何请求");
    }

    /** 已通过的批次：唯一可编辑入口是「申请修改」，并且它从表格里选中的那一位学生打开。 */
    private static void approvedBookIsReachableOnlyThroughTheCorrectionEntry() {
        RecordingService service = new RecordingService();
        service.approvedBook = approvedBook();
        List<Row> opened = new ArrayList<>();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.setCorrectionOpener(opened::add);
        controller.showOffering(APPROVED_OFFERING);

        require("APPROVED".equals(controller.model().state()),
                "夹具必须是已通过的批次，收到 " + controller.model().state());
        require(!controller.model().canEdit(), "已通过的批次是只读的");
        require(!controller.canReopenRejected(), "已通过不是驳回，不能给出重新编辑");
        require(!controller.canRequestCorrection(), "没有选中学生时不能更正");

        controller.beginCorrection();
        require(opened.isEmpty(), "没有选中学生时不能打开更正表单");

        controller.selectRow(controller.rows().get(0));
        require(controller.canRequestCorrection(), "选中学生之后更正入口必须可用");
        controller.beginCorrection();
        require(opened.size() == 1 && opened.get(0) == controller.rows().get(0),
                "更正必须从选中的那一位学生打开，收到 " + opened);
    }

    /**
     * 更正的落点：服务端建立草稿 → 页面变成可编辑的草稿 → 拟修改的分数写进编辑模型 → 仍然走
     * 普通的「保存草稿」。本页不新增第二条写库通路，也绝不单独写一行已发布的成绩。
     */
    private static void theCorrectionEntryCarriesTheProposedScoresThroughTheOrdinarySave() {
        RecordingService service = new RecordingService();
        service.approvedBook = approvedBook();
        service.saveResult = new TeacherOperationResultDTO<>("op-correction", "成绩草稿已保存",
                correctedBook(), false);
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.setCorrectionOpener(row -> controller.applyCorrection(
                new TeacherGradeCorrectionDialogController.CorrectionOutcome(correctedBook(),
                        row.enrollmentId(), Map.of(
                                GradeComponentCodeDTO.DAILY, "70",
                                GradeComponentCodeDTO.MIDTERM, "65",
                                GradeComponentCodeDTO.EXPERIMENT, "88",
                                GradeComponentCodeDTO.FINALTERM, "80"))));
        controller.showOffering(APPROVED_OFFERING);
        controller.selectRow(controller.rows().get(0));

        controller.beginCorrection();

        require("DRAFT".equals(controller.model().state()) && controller.model().canEdit(),
                "更正草稿建立后必须变成可编辑的草稿，收到 " + controller.model().state());
        require("实验分录入有误".equals(controller.model().correctionReason()),
                "更正原因必须从服务端快照映射到模型，收到 "
                        + controller.model().correctionReason());
        Row row = controller.rows().get(0);
        require("88".equals(row.cell(GradeComponentCodeDTO.EXPERIMENT).text()),
                "拟修改的分数必须写进编辑模型，收到 "
                        + row.cell(GradeComponentCodeDTO.EXPERIMENT).text());
        require("70".equals(row.cell(GradeComponentCodeDTO.DAILY).text())
                        && "80".equals(row.cell(GradeComponentCodeDTO.FINALTERM).text()),
                "没改动的组成保持原值，收到 " + row.cell(GradeComponentCodeDTO.DAILY).text()
                        + "/" + row.cell(GradeComponentCodeDTO.FINALTERM).text());
        require(controller.dirty(), "拟修改的分数是未保存的修改");
        require(TeacherGradeBookController.CORRECTION_STARTED_TEXT.equals(
                        controller.feedbackText()),
                "真把分数写进模型时才可以说「已写入成绩表」，收到 " + controller.feedbackText());

        controller.save();

        require(service.saves.size() == 1,
                "更正之后必须走普通的保存草稿通路，收到 " + service.saves.size());
        require(service.submits.isEmpty(), "更正本身不是一次提交");
        GradeRowInputDTO sent = null;
        for (GradeRowInputDTO candidate : service.saves.get(0).getContent().getRows()) {
            if (row.enrollmentId().equals(candidate.getEnrollmentId())) sent = candidate;
        }
        require(sent != null && sent.getScores().getExperimentScore() != null
                        && sent.getScores().getExperimentScore()
                                .compareTo(new BigDecimal("88")) == 0,
                "拟修改的实验分必须由普通保存请求送出，收到 "
                        + (sent == null ? "没有这一行" : sent.getScores().getExperimentScore()));
        require(!controller.dirty(), "保存成功后必须回到干净状态");
    }

    /**
     * 更正之后名单里已经没有这名学生（例如他在表单打开与确认之间退课）：草稿照样建立、页面照样变成
     * 可编辑的草稿，但**一个分数都不许写**，提示也必须说清楚这一点。
     *
     * <p>这条路以前复用「拟修改的分数已写入成绩表」那句提示，教师于是被告知改到了，随后保存并提交一份
     * <b>不含</b>这次更正的批次。两条分支现在各自钉一句不同的文案：只有真写了才说「已写入」，
     * 什么都没写的那条必须说「没有写入」并点名学生已不在名单里。
     */
    private static void aCorrectionForAStudentWhoLeftTheRosterWritesNothing() {
        RecordingService service = new RecordingService();
        service.approvedBook = approvedBook();
        TeacherGradeBookController controller = controller(service, message -> true);
        // 表单打开时选中的是名单里的学生，确认时他已经退课：模型里因此找不到这个 enrollmentId。
        String leftTheRoster = "9999";
        List<String> opened = new ArrayList<>();
        controller.setCorrectionOpener(row -> {
            opened.add(row.enrollmentId());
            controller.applyCorrection(new TeacherGradeCorrectionDialogController.CorrectionOutcome(
                    correctedBook(), leftTheRoster, Map.of(
                            GradeComponentCodeDTO.DAILY, "70",
                            GradeComponentCodeDTO.MIDTERM, "65",
                            GradeComponentCodeDTO.EXPERIMENT, "88",
                            GradeComponentCodeDTO.FINALTERM, "80")));
        });
        controller.showOffering(APPROVED_OFFERING);
        controller.selectRow(controller.rows().get(0));

        controller.beginCorrection();

        require(opened.equals(List.of(APPROVED_ENROLLMENT)),
                "更正仍然从选中的那一位学生打开，收到 " + opened);
        require("DRAFT".equals(controller.model().state()) && controller.model().canEdit(),
                "服务端的更正草稿照样建立、页面照样变成可编辑，收到 " + controller.model().state());
        require(TeacherGradeBookController.CORRECTION_STARTED_ROSTER_GONE_TEXT.equals(
                        controller.feedbackText()),
                "学生不在名单里时必须如实说明这次没有写，收到 " + controller.feedbackText());
        require(!TeacherGradeBookController.CORRECTION_STARTED_TEXT.equals(
                        controller.feedbackText()),
                "这一条路不能复用「拟修改的分数已写入成绩表」那句提示");
        require(controller.feedbackText().contains("名单")
                        && controller.feedbackText().contains("没有写入"),
                "提示必须点名学生已不在名单、并明说分数没有写入，收到 " + controller.feedbackText());
        Row row = controller.rows().get(0);
        require("75".equals(row.cell(GradeComponentCodeDTO.EXPERIMENT).text()),
                "拟修改的分数一个都不许写进编辑模型，收到 "
                        + row.cell(GradeComponentCodeDTO.EXPERIMENT).text());
        require(!controller.dirty(), "什么都没写，页面必须仍然是干净的");
    }

    /**
     * 导出成绩前的确认（用户裁决「仍可导出，但先弹确认框」）：导出不被禁止，但必须先把「导的是哪一份」
     * 说清楚——文件里永远是服务端那份**已保存的草稿**，屏幕上还没保存的编辑一个都不在。
     *
     * <p>被拒绝时一个字节都不许发出去：不选文件、不申请票据。
     */
    private static void exportingADirtyPageConfirmsFirstAndSendsNothingWhenRefused() throws Exception {
        RecordingService service = new RecordingService();
        List<String> asked = new ArrayList<>();
        boolean[] answer = {false};
        StubDialogs dialogs = new StubDialogs(
                Files.createTempDirectory("vcampus-export-gate").resolve("学生成绩.xlsx"));
        TeacherGradeBookController controller = exportController(service, dialogs, message -> {
            asked.add(message);
            return answer[0];
        });
        controller.showOffering(OFFERING);
        controller.model().setScore(controller.rows().get(0).enrollmentId(),
                GradeComponentCodeDTO.DAILY, "88");
        require(controller.dirty(), "本用例要的就是「有未保存的修改」这一种页面");

        controller.handleExportGrades(null);

        require(asked.equals(List.of(TeacherGradeBookController.EXPORT_PROMPT_TEXT)),
                "有未保存的修改时导出必须先问一次，收到 " + asked);
        require(TeacherGradeBookController.EXPORT_PROMPT_TEXT.contains("已保存")
                        && TeacherGradeBookController.EXPORT_PROMPT_TEXT.contains("不包含"),
                "确认文案必须说清楚导的是已保存的草稿、不含未保存的修改，收到 "
                        + TeacherGradeBookController.EXPORT_PROMPT_TEXT);
        require(service.gradeExports.isEmpty(),
                "拒绝确认之后不得申请导出票据，收到 " + service.gradeExports);
        require(dialogs.saveTargetChoices == 0,
                "拒绝确认之后不得先让教师选文件，实际选了 " + dialogs.saveTargetChoices + " 次");

        // 同意之后照常导出：确认框只是把话说清楚，它不拦「导出」这件事。
        answer[0] = true;
        controller.handleExportGrades(null);
        require(service.gradeExports.equals(List.of(OFFERING)),
                "同意之后必须照常申请导出票据，收到 " + service.gradeExports);
        require(dialogs.saveTargetChoices == 1,
                "同意之后才选文件，实际选了 " + dialogs.saveTargetChoices + " 次");

        // 干净页面不提问：这句话只有在存在未保存的修改时才有意义。
        RecordingService cleanService = new RecordingService();
        List<String> cleanAsked = new ArrayList<>();
        TeacherGradeBookController clean = exportController(cleanService,
                new StubDialogs(Files.createTempDirectory("vcampus-export-clean")
                        .resolve("学生成绩.xlsx")), message -> {
                    cleanAsked.add(message);
                    return true;
                });
        clean.showOffering(OFFERING);
        require(!clean.dirty(), "刚打开的页面必须是干净的");
        clean.handleExportGrades(null);
        require(cleanAsked.isEmpty(), "没有未保存的修改时不得提问，收到 " + cleanAsked);
        require(cleanService.gradeExports.equals(List.of(OFFERING)),
                "干净页面必须照常导出，收到 " + cleanService.gradeExports);
    }

    // ------------------------------------------------------------------ 视图契约

    /** 两个新视图：fx:controller、fx:id 与 onAction 全部能在对应控制器上解析。 */
    private static void gradeViewsDeclareTheirControllerIdsAndHandlers() throws Exception {
        Document listView = parseView(GRADE_VIEW);
        require("controller.TeacherGradeController"
                        .equals(listView.getDocumentElement().getAttribute("fx:controller")),
                "成绩列表视图必须由 TeacherGradeController 控制");
        verifyBindings(listView, TeacherGradeController.class, "TeacherGradeController");
        require(elementsWithTag(listView, "TableColumn").size() == 7,
                "成绩列表必须给出 7 列（教学班/课程代码/人数/状态/已录入/缺失/操作）");

        Document bookView = parseView(GRADE_BOOK_VIEW);
        require("controller.TeacherGradeBookController"
                        .equals(bookView.getDocumentElement().getAttribute("fx:controller")),
                "成绩编辑表必须由 TeacherGradeBookController 控制");
        verifyBindings(bookView, TeacherGradeBookController.class, "TeacherGradeBookController");
        require(elementsWithTag(bookView, "TableColumn").size() == 9,
                "成绩编辑表必须包含学号/姓名/四个成绩列/总评/绩点/错误共 9 列");
        for (String text : List.of("平时", "期中", "实验", "期末")) {
            require(columnWithText(bookView, text) != null,
                    "四个成绩列必须始终存在: " + text);
        }
        require(elementsWithTag(bookView, "CheckBox").size() == 4,
                "每一列都要有启用开关");
        require(elementsWithTag(bookView, "TextField").size() == 4,
                "每一列都要有百分比权重输入");
        require(elementWithId(bookView, "gradeBookNoticeLabel") != null,
                "只读状态必须有显示审核意见的位置");
        require(elementWithId(bookView, "gradeBookConfirmSubmitButton") != null
                        && elementWithId(bookView, "gradeBookCancelSubmitButton") != null,
                "提交必须有二次确认与取消入口");
        for (String id : List.of("gradeBookReopenButton", "gradeBookCorrectionButton")) {
            Element entry = elementWithId(bookView, id);
            require(entry != null && "false".equals(entry.getAttribute("visible"))
                            && "false".equals(entry.getAttribute("managed")),
                    id + " 必须默认隐藏：两个版本入口各自只在被驳回/已通过时才出现");
        }
    }

    /**
     * 状态提示与三个导入入口同排：{@code gradeBookFeedbackLabel} 必须是按钮行 HBox 的孩子，
     * 位置在「导入 Excel」之后、撑开右侧导入态的 {@code Region} 之前，并且仍然只有它原来的
     * fx:id／styleClass／wrapText。它不再挂在根 VBox 末尾（那正是“页面最底部”那一行）。
     */
    private static void theStatusLineSitsOnTheButtonRow() throws Exception {
        Document bookView = parseView(GRADE_BOOK_VIEW);
        Element feedback = elementWithId(bookView, "gradeBookFeedbackLabel");
        require(feedback != null, "成绩编辑表必须有状态提示标签");
        require("teacher-course-feedback-text".equals(feedback.getAttribute("styleClass")),
                "状态提示必须保留原样式类，收到 " + feedback.getAttribute("styleClass"));
        require("true".equals(feedback.getAttribute("wrapText")),
                "状态提示必须保留 wrapText");
        require("false".equals(feedback.getAttribute("visible"))
                        && "false".equals(feedback.getAttribute("managed")),
                "状态提示必须默认隐藏（没有提示时不占位）");

        Element row = ownerElement(feedback);
        require("HBox".equals(row.getTagName()), "状态提示必须直接挂在按钮行 HBox 上，收到 "
                + row.getTagName());
        for (String id : List.of("gradeBookDownloadTemplateButton", "gradeBookExportGradesButton",
                "gradeBookImportButton", "gradeBookImportSummaryLabel",
                "gradeBookImportIssuesButton", "gradeBookCancelImportButton",
                "gradeBookConfirmImportButton")) {
            require(row == ownerElement(elementWithId(bookView, id)),
                    id + " 必须仍然在同一个按钮行里（导入区不能被状态提示移位打散）");
        }
        List<Element> children = contentElements(row);
        int importButton = indexOfId(children, "gradeBookImportButton");
        int statusLine = indexOfId(children, "gradeBookFeedbackLabel");
        int grower = indexOfGrowRegion(children);
        require(importButton >= 0 && statusLine > importButton,
                "状态提示必须排在「导入 Excel」之后，收到 " + importButton + " / " + statusLine);
        require(grower >= 0 && statusLine < grower,
                "状态提示必须排在撑开导入态的 Region 之前（与三个入口平齐），收到 "
                        + statusLine + " / " + grower);

        // 根 VBox 里不再有它：状态提示已经离开“页面最底部”那一行。
        require(indexOfId(contentElements(bookView.getDocumentElement()),
                        "gradeBookFeedbackLabel") < 0,
                "状态提示不得再挂在根 VBox 上（那正是页面最底部那一行）");
    }

    /**
     * 节点的“归属容器”：FXML 里每个容器的孩子都包在一层 {@code <children>} 元素里，
     * 因此 DOM 父节点是它，再往上一级才是真正的容器（HBox/VBox）。
     */
    private static Element ownerElement(Element node) {
        Element parent = (Element) node.getParentNode();
        return "children".equals(parent.getTagName()) ? (Element) parent.getParentNode() : parent;
    }

    /** 容器里的孩子节点：剥掉 FXML 的 {@code <children>} 包装层。 */
    private static List<Element> contentElements(Element container) {
        List<Element> direct = childElements(container);
        return direct.size() == 1 && "children".equals(direct.get(0).getTagName())
                ? childElements(direct.get(0)) : direct;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element) children.add(element);
        }
        return children;
    }

    private static int indexOfId(List<Element> children, String id) {
        for (int index = 0; index < children.size(); index++) {
            if (id.equals(children.get(index).getAttribute("fx:id"))) return index;
        }
        return -1;
    }

    /** 按钮行里那一个 {@code HBox.hgrow="ALWAYS"} 的占位 {@code Region}。 */
    private static int indexOfGrowRegion(List<Element> children) {
        for (int index = 0; index < children.size(); index++) {
            Element child = children.get(index);
            if ("Region".equals(child.getTagName())
                    && "ALWAYS".equals(child.getAttribute("HBox.hgrow"))) {
                return index;
            }
        }
        return -1;
    }

    /** 所有 styleClass 都能在 teacher-course.css 里找到选择器。 */
    private static void everyStyleClassExistsInTheStylesheet() throws Exception {
        String css = readResource(CSS);
        for (String view : List.of(GRADE_VIEW, GRADE_BOOK_VIEW)) {
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
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 无工具包的控制器：确认函数注入固定回答。重新编辑有自己的确认框（标题不同），这里一并注入
     * 同一个回答——生产路径上它是 {@code AlertUtil} 的「重新编辑成绩表」，在测试里弹不出来。
     */
    private static TeacherGradeBookController controller(TeacherCourseService service,
            java.util.function.Function<String, Boolean> confirmation) {
        TeacherGradeBookController controller =
                new TeacherGradeBookController(service, Runnable::run, confirmation);
        controller.setReopenConfirmation(confirmation);
        return controller;
    }

    /**
     * 导出那条路要的控制器：导出先问「保存到哪里」，生产路径上弹的是真实 {@code FileChooser}
     * （无工具包环境里根本弹不出来），所以这里把选文件的端口也换成替身。传输端口只是为了满足构造
     * 函数的非空要求：票据没有真的申请成功时它一次都不会被调用。
     */
    private static TeacherGradeBookController exportController(TeacherCourseService service,
            TeacherGradeImportController.FileDialogs dialogs,
            java.util.function.Function<String, Boolean> confirmation) {
        TeacherGradeBookController controller = new TeacherGradeBookController(
                service, new StubTransport(), Runnable::run, confirmation, dialogs);
        controller.setReopenConfirmation(confirmation);
        return controller;
    }

    /** 把这个夹具的权重配齐成 30/20/20/30（等价于界面上依次输入百分比）。 */
    private static void completeScheme(TeacherGradeBookController controller) {
        controller.model().setWeightText(GradeComponentCodeDTO.DAILY, "30");
        controller.model().setWeightText(GradeComponentCodeDTO.MIDTERM, "20");
        controller.model().setWeightText(GradeComponentCodeDTO.EXPERIMENT, "20");
        controller.model().setWeightText(GradeComponentCodeDTO.FINALTERM, "30");
    }

    private static void fillEveryScore(TeacherGradeBookController controller) {
        for (Row row : controller.rows()) {
            for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
                controller.model().setScore(row.enrollmentId(), code, "85");
            }
        }
    }

    private static Row missingExperimentRow(TeacherGradeBookController controller) {
        for (Row row : controller.rows()) {
            if (!row.cell(GradeComponentCodeDTO.EXPERIMENT).entered()) return row;
        }
        throw new AssertionError("夹具里必须有一行缺实验分");
    }

    /** 已通过批次的快照：只读、带着那一批的批次号，等一位选中它的学生来发起更正。 */
    private static TeacherGradeBookDTO approvedBook() {
        return new TeacherGradeBookDTO(APPROVED_OFFERING, 6, "approved-digest", "APPROVED",
                fullScheme(), List.of(approvedRow()), APPROVED_BATCH, null, false, null, false,
                null);
    }

    /** 更正草稿建立后的快照：状态回到可编辑，并带上基础批次与更正原因。 */
    private static TeacherGradeBookDTO correctedBook() {
        return new TeacherGradeBookDTO(APPROVED_OFFERING, 7, "approved-digest", "DRAFT",
                fullScheme(), List.of(approvedRow()), APPROVED_BATCH, APPROVED_BATCH, true,
                "实验分录入有误", false, null);
    }

    private static TeacherGradeRowDTO approvedRow() {
        return new TeacherGradeRowDTO(APPROVED_ENROLLMENT, "00005678", "张三",
                new GradeScoresDTO(new BigDecimal("70"), new BigDecimal("65"),
                        new BigDecimal("75"), new BigDecimal("80")),
                new BigDecimal("73.5"), new BigDecimal("2.5"), true, List.of());
    }

    /** 30/20/20/30，合计 10000 万分比：与 mock 的成绩夹具同一套配齐的权重。 */
    private static GradeSchemeDTO fullScheme() {
        int[] weights = {3000, 2000, 2000, 3000};
        GradeComponentCodeDTO[] codes = GradeComponentCodeDTO.values();
        List<GradeComponentDTO> components = new ArrayList<>();
        for (int index = 0; index < codes.length; index++) {
            components.add(new GradeComponentDTO(codes[index], true, weights[index]));
        }
        return new GradeSchemeDTO(components);
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
            require(!element.hasAttribute("disabled"),
                    label + " 不能使用只读的 disabled 属性，要用 disable");
        }
        require(ids > 0 && actions > 0,
                label + " 必须声明 fx:id 与 onAction，收到 " + ids + " ids / " + actions
                        + " actions");
    }

    private static Document parseView(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream = TeacherGradeBookControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = TeacherGradeBookControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<Element> elementsWithTag(Document view, String tag) {
        List<Element> found = new ArrayList<>();
        NodeList elements = view.getElementsByTagName(tag);
        for (int index = 0; index < elements.getLength(); index++) {
            found.add((Element) elements.item(index));
        }
        return List.copyOf(found);
    }

    private static Element elementWithId(Document view, String id) {
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttribute("fx:id"))) return element;
        }
        return null;
    }

    private static Element columnWithText(Document view, String text) {
        for (Element column : elementsWithTag(view, "TableColumn")) {
            if (text.equals(column.getAttribute("text"))) return column;
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

    /**
     * 选文件替身：记下选过几次、建议的文件名是什么。目标路径由用例给一个<b>不存在</b>的位置，
     * 覆盖确认因此不会介入——这条路径上真正要观察的是「有没有走到这一步」。
     */
    private static final class StubDialogs implements TeacherGradeImportController.FileDialogs {
        private final Path target;
        private int saveTargetChoices;
        private String lastSuggestedName;

        StubDialogs(Path target) {
            this.target = target;
        }

        @Override
        public Path chooseUploadSource() {
            return null;
        }

        @Override
        public Path chooseSaveTarget(String suggestedFileName) {
            saveTargetChoices++;
            lastSuggestedName = suggestedFileName;
            return target;
        }
    }

    /** 传输替身：本组用例只看「有没有开始导出」，真到下载那一步立刻成功即可，绝不碰网络。 */
    private static final class StubTransport implements TeacherFileTransport {
        @Override
        public CompletableFuture<Void> upload(TeacherFileTicketDTO ticket, Path file) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> download(TeacherFileTicketDTO ticket, Path file) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 记录型成绩服务：读路径全部委托给确定性 mock，写路径可以只记录不落地（默认）或注入失败，
     * 也可以自行控制“何时返回”，用来验证重复点击不会发第二个请求。
     */
    private static final class RecordingService implements TeacherCourseService {
        private final MockTeacherCourseService delegate = new MockTeacherCourseService();
        private final List<WriteGradeBookRequestDTO> saves = new ArrayList<>();
        private final List<WriteGradeBookRequestDTO> submits = new ArrayList<>();
        private final List<StartGradeRevisionRequestDTO> reopens = new ArrayList<>();
        private final List<StartGradeRevisionRequestDTO> corrections = new ArrayList<>();
        /** 导出成绩真的申请过票据的教学班：导出被挡下时这里必须是空的。 */
        private final List<String> gradeExports = new ArrayList<>();
        private final Deque<CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>>>
                submitResponses = new ArrayDeque<>();
        private CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> heldSubmit;
        private RuntimeException saveFailure;
        /** 注入一次重开失败：服务端可能已经开好了草稿，只是响应没回来。 */
        private RuntimeException reopenFailure;
        private CompletableFuture<TeacherGradeBookDTO> pendingBook;
        /** 已通过批次的代码内快照；mock 的成绩夹具没有这一种状态（见 APPROVED_OFFERING 的说明）。 */
        private TeacherGradeBookDTO approvedBook;
        /** 保存草稿的固定回复：用来断言“更正走的是普通保存通路”，而不依赖 mock 的名单校验。 */
        private TeacherOperationResultDTO<TeacherGradeBookDTO> saveResult;

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
            if (pendingBook != null) return pendingBook;
            if (approvedBook != null && offeringId.equals(approvedBook.getOfferingId())) {
                return CompletableFuture.completedFuture(approvedBook);
            }
            return delegate.getGradeBook(offeringId);
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> saveGradeDraft(
                WriteGradeBookRequestDTO write) {
            saves.add(write);
            if (saveFailure != null) return failed(saveFailure);
            if (saveResult != null) return CompletableFuture.completedFuture(saveResult);
            return delegate.saveGradeDraft(write);
        }

        /** 导出成绩：先记一笔再交给 mock——它按归属校验并真的签一张票据，导出那条路照常走完。 */
        @Override
        public CompletableFuture<TeacherFileTicketDTO> requestGradeExport(String offeringId) {
            gradeExports.add(offeringId);
            return delegate.requestGradeExport(offeringId);
        }

        /** 两个版本入口同样记一笔再交给 mock：它的状态机就是这两个入口的最小模型。 */
        @Override
        public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>>
                reopenRejectedGradeBook(StartGradeRevisionRequestDTO request) {
            reopens.add(request);
            if (reopenFailure != null) return failed(reopenFailure);
            return delegate.reopenRejectedGradeBook(request);
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>>
                beginGradeCorrection(StartGradeRevisionRequestDTO request) {
            corrections.add(request);
            return delegate.beginGradeCorrection(request);
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> submitGradeBook(
                WriteGradeBookRequestDTO write) {
            submits.add(write);
            if (!submitResponses.isEmpty()) {
                heldSubmit = submitResponses.removeFirst();
                return heldSubmit;
            }
            return delegate.submitGradeBook(write);
        }

        /** 只读快照：用来把页面恢复成“没有未保存修改”的状态。 */
        private TeacherGradeBookDTO bookSnapshot(String offeringId) {
            return delegate.getGradeBook(offeringId).join();
        }

        /**
         * 让下一次提交悬在半空，从而能观察“在途期间的重复点击”。
         *
         * <p>完成时把记录下来的那次请求交给 mock 真正执行：它和服务端一样重新校验权重与缺分，
         * 因此挂起的响应不是伪造的成功结果。
         */
        private void holdSubmit() {
            submitResponses.addLast(new CompletableFuture<>());
        }

        private void finishSubmit() {
            if (heldSubmit == null) throw new AssertionError("没有待完成的提交");
            heldSubmit.complete(delegate.submitGradeBook(submits.get(0)).join());
            heldSubmit = null;
        }

        private static <T> CompletableFuture<T> failed(Throwable failure) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(failure);
            return future;
        }
    }
}
