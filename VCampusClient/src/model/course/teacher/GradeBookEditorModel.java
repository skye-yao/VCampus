package model.course.teacher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import course.grade.GradeCalculator;
import course.grade.GradePointScale;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;

/**
 * 成绩编辑表的内存模型（设计 §5.4）：与 JavaFX 控件完全解耦，只负责“用户输入 → 合法请求内容”。
 *
 * <p>核心契约是原文与解析结果分离：每个分数格子同时保存 {@code text}（用户看到的原文）、
 * {@code value}（解析出的合法 {@link BigDecimal}）和 {@code error}（非法原因）。三者分开的意义在于
 * “空”和“写错”是两件事——留空表示未录入（发送时是 null，服务端保持 NULL，不是 0 分），
 * 写错则是 {@code value == null && error != null}，任何写路径都不会把它变成 null 或 0，
 * 而是直接拒绝发送（{@link #hasErrors()}、{@link #content()} 抛异常、页面禁用保存/提交）。
 * 半成品文本（如 “8.”、“.”、“1e2”、“-1”、“88.555”）一律按非法处理，绝不猜测用户想输入什么。
 *
 * <p>权重同理：输入的是百分比原文（“30”=30.00%），解析成整数万分比（3000）后才进入请求；
 * 全程 BigDecimal 与 int，任何分数或权重通路都不出现 double（double 会把 88.55 变成 88.54999…）。
 *
 * <p>禁用组成不参与任何计算，也不进入请求：它的分数按 null 发送，服务端对禁用项保持草稿里的旧值
 * （{@code mergeDisabled}），因此“禁用 → 重新启用”能恢复原值；禁用的列即使留着非法文本也不阻塞
 * 保存——它本来就不会被发送，而“把非法文本变成 null”在这里是安全的，因为禁用项的值不生效。
 *
 * <p>总评与绩点的预览调用 {@code GradeCalculator}/{@code GradePointScale}（与提交事务同一份纯规则），
 * 权重未配齐或缺启用项分数时两者都是 null，界面显示占位符而不是伪造的总评。
 * 服务端在提交事务里会独立重算，这里的预览只是显示，不构成任何信任。
 */
public final class GradeBookEditorModel {
    /** 权重万分比总数：10000 表示 100.00%。与 {@code GradeCalculator} 同一把尺。 */
    public static final int TOTAL_WEIGHT_BASIS_POINTS = 10000;
    /** 单个组成的权重上限（100.00%）；超过它不可能与其它非负权重凑成 10000。 */
    public static final BigDecimal MAX_WEIGHT_PERCENT = new BigDecimal("100");

    static final String SCORE_FORMAT_TEXT = "分数必须是 0..100 的数字，最多两位小数";
    static final String WEIGHT_FORMAT_TEXT = "权重必须是 0..100 的百分数，最多两位小数";
    static final String ERRORS_BLOCK_TEXT = "存在非法输入，请先修正标红的单元格";
    static final String READ_ONLY_BLOCK_TEXT = "当前状态为只读，不能保存或提交";
    static final String MISSING_SCORES_TEXT = "提交前必须补齐所有启用组成的成绩（缺失不能按 0 分提交）";

    /** 只接受十进制原文：整数或最多两位小数。符号、指数、千分位、结尾小数点都按非法文本处理。 */
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]{1,2})?");
    private static final BigDecimal MIN_SCORE = BigDecimal.ZERO;
    private static final BigDecimal MAX_SCORE = new BigDecimal("100");

    /** 一格分数输入：原文、解析结果与错误原因三者分开保存。 */
    public static final class ScoreCell {
        private String text = "";
        private BigDecimal value;
        private String error;

        private ScoreCell() {
        }

        /** 用户看到的原文，原样保留，界面不会被模型改写。 */
        public String text() {
            return text;
        }

        /** 合法分数；留空或非法时为 null。 */
        public BigDecimal value() {
            return value;
        }

        /** 非法原因；合法或留空时为 null。 */
        public String error() {
            return error;
        }

        public boolean valid() {
            return error == null;
        }

        /** 已录入：有合法分数。空与 0 分的区别就在这里（0 分是 entered，留空不是）。 */
        public boolean entered() {
            return value != null;
        }
    }

    /** 一列组成：启用开关 + 权重原文/解析结果。 */
    public static final class Column {
        private final GradeComponentCodeDTO code;
        private boolean enabled;
        private String weightText = "";
        private int weightBasisPoints;
        private String weightError;

        private Column(GradeComponentCodeDTO code) {
            this.code = Objects.requireNonNull(code, "Component code is required");
        }

        public GradeComponentCodeDTO code() {
            return code;
        }

        public boolean enabled() {
            return enabled;
        }

        public String weightText() {
            return weightText;
        }

        /** 解析出的万分比；禁用时仍然保留用户输入的值，只是不参与发送与计算。 */
        public int weightBasisPoints() {
            return weightBasisPoints;
        }

        public String weightError() {
            return weightError;
        }

        /** 实际发给服务端的权重：禁用项必须是 0（正式提交规则要求禁用项权重为 0）。 */
        public int sentWeightBasisPoints() {
            return enabled ? weightBasisPoints : 0;
        }

        public GradeComponentDTO toDto() {
            return new GradeComponentDTO(code, enabled, sentWeightBasisPoints());
        }
    }

    /** 一行学生：身份 + 四个分数格子 + 服务端下发的行级错误（原样展示，不在客户端重写）。 */
    public static final class Row {
        private final String enrollmentId;
        private final String studentUid;
        private final String studentName;
        private final Map<GradeComponentCodeDTO, ScoreCell> cells =
                new EnumMap<>(GradeComponentCodeDTO.class);
        private final List<String> serverErrors;

        private Row(TeacherGradeRowDTO row) {
            this.enrollmentId = row.getEnrollmentId();
            this.studentUid = row.getStudentUid();
            this.studentName = row.getStudentName();
            this.serverErrors = List.copyOf(row.getErrors());
            GradeScoresDTO scores = row.getScores();
            for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
                ScoreCell cell = new ScoreCell();
                cell.text = scoreText(scoreOf(code, scores));
                cell.value = scoreOf(code, scores);
                cells.put(code, cell);
            }
        }

        public String enrollmentId() {
            return enrollmentId;
        }

        public String studentUid() {
            return studentUid;
        }

        public String studentName() {
            return studentName;
        }

        public ScoreCell cell(GradeComponentCodeDTO code) {
            ScoreCell cell = cells.get(code);
            if (cell == null) throw new IllegalArgumentException("未知的成绩组成: " + code);
            return cell;
        }

        public List<String> serverErrors() {
            return serverErrors;
        }
    }

    private final String offeringId;
    private final List<Column> columns = new ArrayList<>();
    private final Map<GradeComponentCodeDTO, Column> columnsByCode =
            new EnumMap<>(GradeComponentCodeDTO.class);
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Row> rowsByEnrollment = new LinkedHashMap<>();

    private int revision;
    private String rosterDigest;
    private String state;
    private boolean canEdit;
    private String correctionReason;
    private String lastSubmissionId;
    private String reviewComment;
    private boolean rosterChangedSinceSubmission;
    private boolean dirty;

    /**
     * 用服务端快照重建编辑模型。方案缺省为四项全启用、权重 0（与服务端虚拟草稿一致）；
     * 分数原文直接来自 DTO，因此“还没录入”保持空白，而不是显示 0。
     */
    public GradeBookEditorModel(TeacherGradeBookDTO book) {
        Objects.requireNonNull(book, "Grade book is required");
        this.offeringId = book.getOfferingId();
        apply(book);
    }

    /** 用服务端返回的最新快照覆盖编辑状态；脏标记随之清零（保存/提交成功后走这条路径）。 */
    public void applyServerSnapshot(TeacherGradeBookDTO book) {
        Objects.requireNonNull(book, "Grade book is required");
        apply(book);
        dirty = false;
    }

    private void apply(TeacherGradeBookDTO book) {
        revision = book.getRevision();
        rosterDigest = book.getRosterDigest();
        state = book.getState();
        canEdit = book.isCanEdit();
        correctionReason = book.getCorrectionReason();
        lastSubmissionId = book.getLastSubmissionId();
        reviewComment = book.getReviewComment();
        rosterChangedSinceSubmission = book.isRosterChangedSinceSubmission();

        columns.clear();
        columnsByCode.clear();
        GradeSchemeDTO scheme = book.getScheme();
        List<GradeComponentDTO> components = scheme == null ? defaultComponents()
                : scheme.getComponents();
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            Column column = new Column(code);
            GradeComponentDTO component = componentOf(components, code);
            column.enabled = component == null || component.isEnabled();
            column.weightBasisPoints = component == null ? 0 : component.getWeightBasisPoints();
            column.weightText = percentText(column.weightBasisPoints);
            column.weightError = null;
            columns.add(column);
            columnsByCode.put(code, column);
        }

        rows.clear();
        rowsByEnrollment.clear();
        for (TeacherGradeRowDTO row : book.getRows()) {
            Row editorRow = new Row(row);
            rows.add(editorRow);
            rowsByEnrollment.put(editorRow.enrollmentId(), editorRow);
        }
    }

    // ------------------------------------------------------------------ 身份与状态

    public String offeringId() {
        return offeringId;
    }

    /** 客户端看到的草稿版本；写请求原样回传，服务端据此拒绝过期写入。 */
    public int revision() {
        return revision;
    }

    public String rosterDigest() {
        return rosterDigest;
    }

    /** DRAFT/PENDING/APPROVED/REJECTED。 */
    public String state() {
        return state;
    }

    public boolean canEdit() {
        return canEdit;
    }

    public String correctionReason() {
        return correctionReason;
    }

    public String lastSubmissionId() {
        return lastSubmissionId;
    }

    public boolean rosterChangedSinceSubmission() {
        return rosterChangedSinceSubmission;
    }

    public List<Column> columns() {
        return List.copyOf(columns);
    }

    public Column column(GradeComponentCodeDTO code) {
        Column column = columnsByCode.get(code);
        if (column == null) throw new IllegalArgumentException("未知的成绩组成: " + code);
        return column;
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }

    public Row row(String enrollmentId) {
        Row row = rowsByEnrollment.get(enrollmentId);
        if (row == null) throw new IllegalArgumentException("成绩表里没有该学生: " + enrollmentId);
        return row;
    }

    // ------------------------------------------------------------------ 编辑

    /** 输入一格分数：原文原样保留，解析结果与错误原因立即更新；内容真的变化才算 dirty。 */
    public void setScore(String enrollmentId, GradeComponentCodeDTO code, String text) {
        ScoreCell cell = row(enrollmentId).cell(code);
        String next = text == null ? "" : text.trim();
        if (next.equals(cell.text)) return;
        cell.text = next;
        parseScore(cell);
        dirty = true;
    }

    /** 启用/禁用一列组成；禁用不会清空该列的分数原文，重新启用即可恢复。 */
    public void setEnabled(GradeComponentCodeDTO code, boolean enabled) {
        Column column = column(code);
        if (column.enabled == enabled) return;
        column.enabled = enabled;
        dirty = true;
    }

    /** 输入一列权重（百分比原文），例如 “30” = 30.00% = 3000 万分比。 */
    public void setWeightText(GradeComponentCodeDTO code, String text) {
        Column column = column(code);
        String next = text == null ? "" : text.trim();
        if (next.equals(column.weightText)) return;
        column.weightText = next;
        parseWeight(column);
        dirty = true;
    }

    /** 本地修改过且尚未被服务端快照覆盖。 */
    public boolean dirty() {
        return dirty;
    }

    private static void parseScore(ScoreCell cell) {
        cell.value = null;
        cell.error = null;
        if (cell.text.isEmpty()) {
            // 留空 = 尚未录入：发送时是 null，服务端保持 NULL，绝不是 0 分。
            return;
        }
        if (!DECIMAL.matcher(cell.text).matches()) {
            cell.error = SCORE_FORMAT_TEXT;
            return;
        }
        BigDecimal parsed = new BigDecimal(cell.text);
        if (parsed.compareTo(MIN_SCORE) < 0 || parsed.compareTo(MAX_SCORE) > 0) {
            cell.error = SCORE_FORMAT_TEXT;
            return;
        }
        cell.value = parsed;
    }

    private static void parseWeight(Column column) {
        column.weightBasisPoints = 0;
        column.weightError = null;
        if (column.weightText.isEmpty()) {
            column.weightError = WEIGHT_FORMAT_TEXT;
            return;
        }
        if (!DECIMAL.matcher(column.weightText).matches()) {
            column.weightError = WEIGHT_FORMAT_TEXT;
            return;
        }
        BigDecimal percent = new BigDecimal(column.weightText);
        if (percent.compareTo(MAX_WEIGHT_PERCENT) > 0) {
            column.weightError = WEIGHT_FORMAT_TEXT;
            return;
        }
        // 百分数 → 万分比：把小数点右移两位。两位小数之内这一步永远精确，不需要四舍五入。
        column.weightBasisPoints = percent.movePointRight(2).intValueExact();
    }

    // ------------------------------------------------------------------ 校验

    /**
     * 是否存在会阻塞写请求的非法输入：启用列的权重文本与启用列的分数格子。
     * 禁用列的分数不会进入请求（服务端保留草稿旧值），因此它的残留文本不阻塞保存。
     */
    public boolean hasErrors() {
        for (Column column : columns) {
            if (column.enabled && column.weightError != null) return true;
        }
        for (Row row : rows) {
            for (Column column : columns) {
                if (column.enabled && row.cell(column.code).error != null) return true;
            }
        }
        return false;
    }

    /** 保存草稿是否被本地校验挡住；null 表示可以发送。 */
    public String saveBlockReason() {
        if (!canEdit) return READ_ONLY_BLOCK_TEXT;
        String error = firstError();
        return error == null ? null : error;
    }

    /**
     * 提交是否被本地校验挡住；null 表示可以发送。
     *
     * <p>权重规则直接调用 {@link GradeCalculator#validateScheme} 的正式提交档（与服务端同一份规则、
     * 同一句错误文案），缺分检查在本地补齐更明确的提示，避免一次注定被拒绝的请求。
     */
    public String submitBlockReason() {
        if (!canEdit) return READ_ONLY_BLOCK_TEXT;
        String error = firstError();
        if (error != null) return error;
        try {
            GradeCalculator.validateScheme(scheme(), true);
        } catch (IllegalArgumentException weightProblem) {
            return weightProblem.getMessage();
        }
        if (hasMissingEnabledScores()) return MISSING_SCORES_TEXT;
        return null;
    }

    /**
     * 第一条非法输入的定位文案（含学生姓名与组成名，权重错误含组成名）；没有错误时返回 null。
     *
     * <p>只回一句“请修正标红的单元格”会让人在四个权重框之间找不到北，所以这里把“哪里错了、
     * 错成什么样”直接说出来；界面同时给对应的权重框标红。多个错误只报第一条，避免提示被刷屏。
     */
    private String firstError() {
        for (Column column : columns) {
            if (column.enabled && column.weightError != null) {
                return ERRORS_BLOCK_TEXT + "：" + componentLabel(column.code) + "权重——"
                        + column.weightError;
            }
        }
        for (Row row : rows) {
            for (Column column : columns) {
                ScoreCell cell = row.cell(column.code);
                if (column.enabled && cell.error != null) {
                    return ERRORS_BLOCK_TEXT + "：" + row.studentName() + " 的"
                            + componentLabel(column.code) + "——" + cell.error;
                }
            }
        }
        return null;
    }

    /** 组成的中文名；界面上的列名、方案条与错误文案共用它，避免出现两套说法。 */
    public static String componentLabel(GradeComponentCodeDTO code) {
        if (code == null) return "未知组成";
        return switch (code) {
            case DAILY -> "平时";
            case MIDTERM -> "期中";
            case EXPERIMENT -> "实验";
            case FINALTERM -> "期末";
        };
    }

    /** 任一学生缺少任一启用组成的分数。 */
    public boolean hasMissingEnabledScores() {
        for (Row row : rows) {
            for (Column column : columns) {
                if (column.enabled && !row.cell(column.code).entered()) return true;
            }
        }
        return false;
    }

    /**
     * 批次状态说明（审核状态、审核意见、更正原因、名单变化）；普通可编辑草稿返回 null。
     *
     * <p>被驳回的草稿对教师仍是可编辑的，但恰恰是这时候最需要看到管理员的审核意见，所以这里不按
     * {@code canEdit} 一刀切，而是按“有没有需要向教师交代的批次状态”决定：只有
     * 可编辑 + DRAFT 的普通草稿才什么都不显示。
     */
    public String stateNotice() {
        boolean readOnly = !canEdit;
        if (!readOnly && "DRAFT".equals(state)) return null;
        StringBuilder notice = new StringBuilder(
                readOnly ? "当前成绩表为只读状态：" : "上一次提交未通过：");
        notice.append(stateLabel(state));
        if (lastSubmissionId != null) {
            notice.append("（批次 ").append(lastSubmissionId).append("）");
        }
        if (reviewComment != null && !reviewComment.isBlank()) {
            notice.append("　审核意见：").append(reviewComment);
        }
        if (correctionReason != null && !correctionReason.isBlank()) {
            notice.append("　更正原因：").append(correctionReason);
        }
        if (!readOnly) {
            notice.append("　可以修改后重新提交。");
        }
        if (rosterChangedSinceSubmission) {
            notice.append("　提交之后名单有变化，新学生尚未纳入已提交批次。");
        }
        return notice.toString();
    }

    /** 最后一次批次的审核意见；没有批次或批次没有意见时为 null。 */
    public String reviewComment() {
        return reviewComment;
    }

    /** 状态文案；界面的只读提示与批次状态列共用它，避免两处出现两套说法。 */
    public static String stateLabel(String state) {
        if (state == null) return "未知";
        return switch (state) {
            case "DRAFT" -> "草稿";
            case "PENDING" -> "已提交待审核";
            case "APPROVED" -> "审核已通过";
            case "REJECTED" -> "审核未通过（已驳回）";
            default -> state;
        };
    }

    // ------------------------------------------------------------------ 预览

    /** 该行按当前方案（含未保存的编辑）算出的总评；权重未配齐或缺启用项分数时为 null。 */
    public BigDecimal rowTotal(Row row) {
        try {
            return GradeCalculator.total(scheme(), rowScores(row));
        } catch (IllegalArgumentException notComputable) {
            return null;
        }
    }

    /** 该行的绩点；总评为 null 时为 null。 */
    public BigDecimal rowGradePoint(Row row) {
        BigDecimal total = rowTotal(row);
        return total == null ? null : GradePointScale.gradePointFor(total);
    }

    // ------------------------------------------------------------------ 写请求

    /** 当前完整编辑内容；存在非法输入时拒绝构造，绝不把非法格子降级成 null 或 0。 */
    public GradeBookContentDTO content() {
        if (!canEdit) throw new IllegalStateException(READ_ONLY_BLOCK_TEXT);
        String error = firstError();
        if (error != null) throw new IllegalStateException(error);
        List<GradeRowInputDTO> inputs = new ArrayList<>();
        for (Row row : rows) {
            inputs.add(new GradeRowInputDTO(row.enrollmentId(), rowScores(row)));
        }
        return new GradeBookContentDTO(offeringId, revision, rosterDigest, scheme(), inputs);
    }

    /** 写请求：operationId 由调用方生成（每次新写入一个 UUID，重放才复用同一个）。 */
    public WriteGradeBookRequestDTO writeRequest(String operationId) {
        return new WriteGradeBookRequestDTO(operationId, content());
    }

    /** 当前方案（含未保存的开关与权重）。禁用项的权重按 0 发送，重新启用时用原文恢复。 */
    public GradeSchemeDTO scheme() {
        List<GradeComponentDTO> components = new ArrayList<>();
        for (Column column : columns) {
            components.add(column.toDto());
        }
        return new GradeSchemeDTO(components);
    }

    /**
     * 一行学生的发送分数：启用项用解析出的值（留空是 null），禁用项一律 null。
     * 非法格子到不了这里（{@link #content()} 已经拒绝），所以 null 只表示“未录入/不适用”。
     */
    private GradeScoresDTO rowScores(Row row) {
        return new GradeScoresDTO(
                cellValue(row, GradeComponentCodeDTO.DAILY),
                cellValue(row, GradeComponentCodeDTO.MIDTERM),
                cellValue(row, GradeComponentCodeDTO.EXPERIMENT),
                cellValue(row, GradeComponentCodeDTO.FINALTERM));
    }

    private BigDecimal cellValue(Row row, GradeComponentCodeDTO code) {
        Column column = column(code);
        return column.enabled ? row.cell(code).value() : null;
    }

    // ------------------------------------------------------------------ 工具

    private static List<GradeComponentDTO> defaultComponents() {
        List<GradeComponentDTO> components = new ArrayList<>();
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            components.add(new GradeComponentDTO(code, true, 0));
        }
        return components;
    }

    private static GradeComponentDTO componentOf(List<GradeComponentDTO> components,
            GradeComponentCodeDTO code) {
        for (GradeComponentDTO component : components) {
            if (component != null && component.getCode() == code) return component;
        }
        return null;
    }

    /** 展示用的分数原文：空保持空，0.00 显示成 0，88.50 显示成 88.5，绝不改变数值。 */
    static String scoreText(BigDecimal score) {
        return score == null ? "" : score.stripTrailingZeros().toPlainString();
    }

    /** 万分比 → 百分比原文：3000 → “30”，3050 → “30.5”，0 → “0”。 */
    static String percentText(int basisPoints) {
        return BigDecimal.valueOf(basisPoints).movePointLeft(2)
                .stripTrailingZeros().toPlainString();
    }

    private static BigDecimal scoreOf(GradeComponentCodeDTO code, GradeScoresDTO scores) {
        if (scores == null) return null;
        return switch (code) {
            case DAILY -> scores.getDailyScore();
            case MIDTERM -> scores.getMidtermScore();
            case EXPERIMENT -> scores.getExperimentScore();
            case FINALTERM -> scores.getFinaltermScore();
        };
    }
}
