package model.course.teacher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import model.course.teacher.GradeBookEditorModel.Column;
import model.course.teacher.GradeBookEditorModel.Row;
import model.course.teacher.GradeBookEditorModel.ScoreCell;

/**
 * 成绩编辑模型的输入、校验与写请求契约（设计 §5.4），不依赖 JavaFX 工具包与数据库。
 *
 * <p>核心断言是“原文与解析结果分离”：非法或半成品文本既不能变成 0 分，也不能变成“未录入”，
 * 而是带着错误原因挡住写请求；留空才表示未录入（null），输入 0 是明确的 0 分。
 * 其余覆盖两位小数、禁用组成、权重未配齐、dirty 与提交前的权重/缺分校验。
 */
public final class GradeBookEditorModelTest {
    private static final String OFFERING_ID = "9007199254740993";
    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ROW_A = "50031";
    private static final String ROW_B = "50032";
    /** 四项都填 85：加权后总评 85.00、绩点 3.5，用来验证共享计算规则被真正调用。 */
    private static final String FILL_SCORE = "85";

    private GradeBookEditorModelTest() {
    }

    public static void main(String[] args) {
        illegalTextIsKeptAndBlocksTheWrite();
        twoDecimalScoresAreAcceptedAndMoreAreRejected();
        blankIsNullAndZeroIsAnExplicitZero();
        componentSwitchesSendNullScoresAndZeroWeight();
        incompleteWeightsBlockSubmitButNotTheDraft();
        dirtyTracksRealEditsAndOnlyAServerSnapshotClearsIt();
        writeRequestCarriesVersionDigestAndParsedScoresOnly();
        System.out.println("GradeBookEditorModelTest: PASS");
    }

    /** 非法文本保留原文并挡住写请求：既不落成 null（未录入）也不落成 0。 */
    private static void illegalTextIsKeptAndBlocksTheWrite() {
        GradeBookEditorModel model = model();

        for (String illegal : List.of("abc", "8.", ".5", "1e2", "-1", "1,5", "88.555", "101",
                "１００")) {
            model.setScore(ROW_A, GradeComponentCodeDTO.DAILY, illegal);
            ScoreCell cell = model.row(ROW_A).cell(GradeComponentCodeDTO.DAILY);
            require(illegal.equals(cell.text()),
                    "非法文本必须原样保留，收到 " + cell.text());
            require(cell.value() == null,
                    "非法文本不能解析出分数值，收到 " + cell.value());
            require(cell.error() != null, "非法文本必须带错误原因: " + illegal);
            require(!cell.valid(), "非法文本不是合法输入");
            require(model.hasErrors(), "非法文本必须让模型报告错误: " + illegal);
            require(model.saveBlockReason() != null, "非法文本必须挡住保存: " + illegal);
            require(model.submitBlockReason() != null, "非法文本必须挡住提交: " + illegal);

            boolean refused = false;
            try {
                model.content();
            } catch (IllegalStateException expected) {
                refused = true;
            }
            require(refused, "非法文本必须让 content() 拒绝构造写请求: " + illegal);
        }

        // 修好之后又能写：非法状态不是终态。
        model.setScore(ROW_A, GradeComponentCodeDTO.DAILY, "88.5");
        require(!model.hasErrors() && model.saveBlockReason() == null,
                "改正之后模型必须恢复可写");
        require(new BigDecimal("88.5").compareTo(
                        model.row(ROW_A).cell(GradeComponentCodeDTO.DAILY).value()) == 0,
                "改正之后解析出正确的分数");
    }

    /** 最多两位小数：一位/两位小数照收，第三位按非法拒绝（数据库会静默取整）。 */
    private static void twoDecimalScoresAreAcceptedAndMoreAreRejected() {
        GradeBookEditorModel model = model();

        model.setScore(ROW_A, GradeComponentCodeDTO.MIDTERM, "88.5");
        require(new BigDecimal("88.5").compareTo(
                        model.row(ROW_A).cell(GradeComponentCodeDTO.MIDTERM).value()) == 0,
                "一位小数必须被接受");
        model.setScore(ROW_A, GradeComponentCodeDTO.MIDTERM, "88.50");
        ScoreCell twoDecimals = model.row(ROW_A).cell(GradeComponentCodeDTO.MIDTERM);
        require(twoDecimals.valid() && twoDecimals.value().scale() >= 1,
                "两位小数必须被接受");
        model.setScore(ROW_A, GradeComponentCodeDTO.MIDTERM, "0.05");
        require(new BigDecimal("0.05").compareTo(
                        model.row(ROW_A).cell(GradeComponentCodeDTO.MIDTERM).value()) == 0,
                "小于 1 的合法分数必须保留小数位");
        model.setScore(ROW_A, GradeComponentCodeDTO.MIDTERM, "88.555");
        require(model.row(ROW_A).cell(GradeComponentCodeDTO.MIDTERM).error() != null,
                "三位小数必须被拒绝，而不是被静默四舍五入");
        model.setScore(ROW_A, GradeComponentCodeDTO.MIDTERM, "100");
        require(model.row(ROW_A).cell(GradeComponentCodeDTO.MIDTERM).valid(),
                "100 是合法上界");
    }

    /** 留空 = 未录入（null），0 = 明确的 0 分：两者在模型里必须可区分。 */
    private static void blankIsNullAndZeroIsAnExplicitZero() {
        GradeBookEditorModel model = model();
        ScoreCell cell = model.row(ROW_A).cell(GradeComponentCodeDTO.EXPERIMENT);

        require(!cell.entered() && cell.value() == null && cell.valid(),
                "未录入的格子是空的、合法的，值为 null");
        require("".equals(cell.text()), "未录入的原文是空字符串");

        model.setScore(ROW_A, GradeComponentCodeDTO.EXPERIMENT, "0");
        require(cell.entered() && BigDecimal.ZERO.compareTo(cell.value()) == 0,
                "输入 0 必须解析成 0 分而不是 null");
        require(cell.valid(), "0 分是合法输入");

        model.setScore(ROW_A, GradeComponentCodeDTO.EXPERIMENT, "");
        require(!cell.entered() && cell.value() == null && cell.valid(),
                "清空必须回到未录入状态");

        model.setScore(ROW_A, GradeComponentCodeDTO.EXPERIMENT, "0.00");
        require(cell.entered() && BigDecimal.ZERO.compareTo(cell.value()) == 0
                        && "0.00".equals(cell.text()),
                "0.00 保留原文并解析成 0 分");
    }

    /**
     * 禁用组成：发送时分数是 null、权重是 0（草稿里已有的值不会被清掉），也不参与计算；
     * 禁用列残留的非法文本不阻塞保存——它本来就不会被发送，值由服务端保持原样。
     */
    private static void componentSwitchesSendNullScoresAndZeroWeight() {
        GradeBookEditorModel model = model();
        model.setScore(ROW_A, GradeComponentCodeDTO.EXPERIMENT, "93.5");
        model.setScore(ROW_B, GradeComponentCodeDTO.EXPERIMENT, "abc");
        model.setWeightText(GradeComponentCodeDTO.EXPERIMENT, "20");

        model.setEnabled(GradeComponentCodeDTO.EXPERIMENT, false);
        require(model.dirty(), "切换组成开关必须置 dirty");
        Column disabled = model.column(GradeComponentCodeDTO.EXPERIMENT);
        require(disabled.sentWeightBasisPoints() == 0,
                "禁用组成的发送权重必须是 0");
        require("20".equals(disabled.weightText()),
                "禁用不清空权重原文，重新启用即可恢复");
        require(!model.hasErrors(),
                "禁用列里的非法文本不阻塞保存（它不会被发送）");
        require(model.saveBlockReason() == null, "禁用后这份草稿可以保存");

        List<GradeRowInputDTO> rows = model.content().getRows();
        for (GradeRowInputDTO row : rows) {
            require(row.getScores().getExperimentScore() == null,
                    "禁用组成的分数必须按 null 发送，而不是 0 或旧值");
        }
        GradeSchemeDTO scheme = model.content().getScheme();
        require(weightOf(scheme, GradeComponentCodeDTO.EXPERIMENT) == 0,
                "禁用组成的权重必须按 0 发送");

        model.setEnabled(GradeComponentCodeDTO.EXPERIMENT, true);
        require("20".equals(model.column(GradeComponentCodeDTO.EXPERIMENT).weightText())
                        && model.column(GradeComponentCodeDTO.EXPERIMENT).weightBasisPoints() == 2000,
                "重新启用后权重原文与解析结果都恢复");
        require("93.5".equals(model.row(ROW_A).cell(GradeComponentCodeDTO.EXPERIMENT).text()),
                "重新启用后原来的分数原文还在");
        require(model.hasErrors(),
                "重新启用后非法文本又进入校验范围");
    }

    /** 权重未配齐：草稿可以保存，提交被 {@code GradeCalculator} 的规则挡住，且不显示伪造的总评。 */
    private static void incompleteWeightsBlockSubmitButNotTheDraft() {
        GradeBookEditorModel model = model();

        Column daily = model.column(GradeComponentCodeDTO.DAILY);
        require("0".equals(daily.weightText()) && daily.weightError() == null,
                "默认方案是合法的权重文本 0（合计未配齐但不是非法输入）");
        require(!model.hasErrors() && model.saveBlockReason() == null,
                "权重未配齐的草稿仍然可以保存");
        require(model.submitBlockReason() != null && model.submitBlockReason().contains("权重"),
                "权重未配齐必须挡住提交并说明权重问题，收到 " + model.submitBlockReason());
        require(model.rowTotal(model.row(ROW_A)) == null,
                "权重未配齐时不能显示伪造的总评");

        // 权重文本本身非法：连草稿都不能保存。
        model.setWeightText(GradeComponentCodeDTO.DAILY, "abc");
        require(model.column(GradeComponentCodeDTO.DAILY).weightError() != null,
                "非法权重文本必须带错误原因");
        require(model.saveBlockReason() != null, "非法权重必须挡住保存");
        model.setWeightText(GradeComponentCodeDTO.DAILY, "0");

        // 配齐 30/20/20/30 后仍缺分：提交被“补齐分数”挡住。
        model.setWeightText(GradeComponentCodeDTO.DAILY, "30");
        model.setWeightText(GradeComponentCodeDTO.MIDTERM, "20");
        model.setWeightText(GradeComponentCodeDTO.EXPERIMENT, "20");
        model.setWeightText(GradeComponentCodeDTO.FINALTERM, "30");
        require(model.submitBlockReason() != null
                        && model.submitBlockReason().contains("补齐"),
                "权重配齐但缺分时必须明确提示补齐，收到 " + model.submitBlockReason());
        for (Row row : model.rows()) {
            for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
                model.setScore(row.enrollmentId(), code, FILL_SCORE);
            }
        }
        require(model.submitBlockReason() == null,
                "权重配齐且分数完整后提交必须放行，收到 " + model.submitBlockReason());
        require(new BigDecimal("85").compareTo(model.rowTotal(model.row(ROW_A))) == 0,
                "总评按启用项加权并与服务端同一份规则，收到 "
                        + model.rowTotal(model.row(ROW_A)));
        require(new BigDecimal("3.5").compareTo(model.rowGradePoint(model.row(ROW_A))) == 0,
                "绩点按连续区间查表（85 → 3.5），收到 " + model.rowGradePoint(model.row(ROW_A)));

        // 只读状态（已提交待审核）下两个写入口都被挡住，并给出审核状态说明。
        GradeBookEditorModel readOnly = new GradeBookEditorModel(
                book(4, "PENDING", false, fullScheme(), rows()));
        require(!readOnly.canEdit() && readOnly.stateNotice() != null
                        && readOnly.stateNotice().contains("待审核"),
                "只读状态必须给出含审核状态的说明，收到 " + readOnly.stateNotice());
        require(readOnly.saveBlockReason() != null && readOnly.submitBlockReason() != null,
                "只读状态下保存与提交都被挡住");

        // 审核意见：只读（已通过）与被驳回（可编辑）都必须显示出来，普通草稿不显示。
        GradeBookEditorModel approved = new GradeBookEditorModel(pendingBook("APPROVED", false,
                "平时分与卷面分不符，已按卷面分发布"));
        require(approved.stateNotice() != null && approved.stateNotice().contains("审核已通过")
                        && approved.stateNotice().contains("平时分与卷面分不符"),
                "已通过批次必须显示管理员审核意见，收到 " + approved.stateNotice());

        GradeBookEditorModel rejected = new GradeBookEditorModel(pendingBook("REJECTED", true,
                "缺平时分，请补齐后重新提交"));
        require(rejected.canEdit(), "被驳回的草稿对教师仍然可编辑");
        require(rejected.stateNotice() != null
                        && rejected.stateNotice().contains("缺平时分，请补齐后重新提交")
                        && rejected.stateNotice().contains("重新提交"),
                "被驳回时必须显示审核意见并说明可以改后重提，收到 " + rejected.stateNotice());

        GradeBookEditorModel draft = model();
        require(draft.stateNotice() == null,
                "普通可编辑草稿没有要交代的批次状态，不显示提示");
        require(draft.reviewComment() == null, "没有批次的草稿没有审核意见");
    }

    /** dirty 只由真实修改置位，只由服务端快照清零；重复输入同样的文本不置位。 */
    private static void dirtyTracksRealEditsAndOnlyAServerSnapshotClearsIt() {
        GradeBookEditorModel model = new GradeBookEditorModel(
                book(3, "DRAFT", true, defaultScheme(), rowsWithDaily("88")));
        require(!model.dirty(), "刚加载的模型不是 dirty");

        model.setScore(ROW_A, GradeComponentCodeDTO.DAILY, "88");
        require(!model.dirty(), "输入与当前值相同的文本不算修改");
        model.setScore(ROW_A, GradeComponentCodeDTO.DAILY, "89");
        require(model.dirty(), "改分数必须置 dirty");
        model.setEnabled(GradeComponentCodeDTO.DAILY,
                !model.column(GradeComponentCodeDTO.DAILY).enabled());
        require(model.dirty(), "切换组成开关必须置 dirty");
        model.setEnabled(GradeComponentCodeDTO.DAILY, true);
        model.setWeightText(GradeComponentCodeDTO.DAILY, "25");
        require(model.dirty(), "改权重必须置 dirty");
        model.setWeightText(GradeComponentCodeDTO.DAILY,
                model.column(GradeComponentCodeDTO.DAILY).weightText());
        require(model.dirty(), "重复输入同样的权重不会把 dirty 清掉");

        model.applyServerSnapshot(book(4, "DRAFT", true, defaultScheme(), rowsWithDaily("89")));
        require(!model.dirty(), "服务端快照必须清零 dirty");
        require(model.revision() == 4, "服务端快照必须更新 revision，收到 " + model.revision());
        require("89".equals(model.row(ROW_A).cell(GradeComponentCodeDTO.DAILY).text()),
                "服务端快照必须覆盖编辑内容");
    }

    /** 写请求：版本与名单摘要原样带出，行只含 enrollmentId 与解析后的分数。 */
    private static void writeRequestCarriesVersionDigestAndParsedScoresOnly() {
        GradeBookEditorModel model = model();
        model.setScore(ROW_A, GradeComponentCodeDTO.DAILY, "88.5");
        model.setScore(ROW_B, GradeComponentCodeDTO.FINALTERM, "0");

        WriteGradeBookRequestDTO request = model.writeRequest("30000000-0000-0000-0000-000000000001");
        require("30000000-0000-0000-0000-000000000001".equals(request.getOperationId()),
                "operationId 必须原样带出");
        GradeBookContentDTO content = request.getContent();
        require(OFFERING_ID.equals(content.getOfferingId()), "offeringId 必须原样带出");
        require(content.getExpectedRevision() == 3,
                "expectedRevision 必须是客户端看到的版本，收到 " + content.getExpectedRevision());
        require(DIGEST.equals(content.getRosterDigest()), "rosterDigest 必须原样带出");
        require(content.getRows().size() == 2, "两行学生都必须带出");
        GradeRowInputDTO first = content.getRows().get(0);
        require(ROW_A.equals(first.getEnrollmentId()), "行必须带 enrollmentId");
        require(new BigDecimal("88.5").compareTo(first.getScores().getDailyScore()) == 0,
                "已录入的分数必须解析后再发送");
        require(first.getScores().getMidtermScore() == null
                        && first.getScores().getExperimentScore() == null,
                "未录入的组成必须发 null，不能补 0");
        GradeRowInputDTO second = content.getRows().get(1);
        require(second.getScores().getFinaltermScore() != null
                        && BigDecimal.ZERO.compareTo(second.getScores().getFinaltermScore()) == 0,
                "显式输入的 0 分必须作为 0 发送");
    }

    // ------------------------------------------------------------------ 夹具

    /** 一个可编辑草稿：两行学生、权重 0 的四项全启用方案、revision=3。 */
    private static GradeBookEditorModel model() {
        return new GradeBookEditorModel(book(3, "DRAFT", true, defaultScheme(), rows()));
    }

    private static GradeSchemeDTO defaultScheme() {
        List<GradeComponentDTO> components = new ArrayList<>();
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            components.add(new GradeComponentDTO(code, true, 0));
        }
        return new GradeSchemeDTO(components);
    }

    private static GradeSchemeDTO fullScheme() {
        List<GradeComponentDTO> components = new ArrayList<>();
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            int weight = switch (code) {
                case DAILY -> 3000;
                case MIDTERM -> 2000;
                case EXPERIMENT -> 2000;
                case FINALTERM -> 3000;
            };
            components.add(new GradeComponentDTO(code, true, weight));
        }
        return new GradeSchemeDTO(components);
    }

    private static List<TeacherGradeRowDTO> rows() {
        return rowsWithDaily(null);
    }

    /** 两行学生；给定 daily 时第一行带一个已录入的平时分，用来验证“原文与当前值相同”。 */
    private static List<TeacherGradeRowDTO> rowsWithDaily(String daily) {
        List<TeacherGradeRowDTO> rows = new ArrayList<>();
        rows.add(row(ROW_A, "00005678", "张三", daily));
        rows.add(row(ROW_B, "00005679", "李四", null));
        return rows;
    }

    private static TeacherGradeRowDTO row(String enrollmentId, String uid, String name,
            String daily) {
        return new TeacherGradeRowDTO(enrollmentId, uid, name,
                new GradeScoresDTO(daily == null ? null : new BigDecimal(daily), null, null, null),
                null, null, false, List.of());
    }

    private static TeacherGradeBookDTO book(int revision, String state, boolean canEdit,
            GradeSchemeDTO scheme, List<TeacherGradeRowDTO> rows) {
        return new TeacherGradeBookDTO(OFFERING_ID, revision, DIGEST, state, scheme, rows, null,
                null, canEdit, null, false);
    }

    /** 带批次的成绩表：state/canEdit/审核意见可指定，用来覆盖只读与驳回两种提示。 */
    private static TeacherGradeBookDTO pendingBook(String state, boolean canEdit,
            String reviewComment) {
        return new TeacherGradeBookDTO(OFFERING_ID, 4, DIGEST, state, fullScheme(), rows(),
                "9001", null, canEdit, null, false, reviewComment);
    }

    private static int weightOf(GradeSchemeDTO scheme, GradeComponentCodeDTO code) {
        for (GradeComponentDTO component : scheme.getComponents()) {
            if (component.getCode() == code) return component.getWeightBasisPoints();
        }
        return -1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
