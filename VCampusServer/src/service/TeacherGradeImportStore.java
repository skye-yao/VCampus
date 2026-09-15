package service;

import dto.course.teacher.GradeImportCorrectionDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherRosterRowDTO;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导入预览的令牌仓：10 分钟有效、可按教师与令牌检索、有界、可注入时钟。
 *
 * <p>上传成功的文件在预览阶段被解析成「原样行」与「原草稿快照」，解析完的文件立刻删除；这里保存
 * 的就是之后每次修订都要用到的服务端状态（原始解析行、名单、原草稿值、修正与排除记录），因此
 * 修订不必重新上传，也不会把客户端上一轮的中间状态叠加上去。
 *
 * <p>令牌绑定教师：另一个教师拿同一个令牌来读或改都会得到与「令牌已过期」完全相同的答复，
 * 既不泄露令牌是否存在，也不允许跨教师读取别人的班级内容。
 *
 * <p>有界：同一教师最多 {@value #MAX_PREVIEWS_PER_TEACHER} 份、全局最多 {@value #MAX_PREVIEWS} 份
 * 预览，超出时按最快过期的先淘汰。一份预览最大也不过 5000 行短文本，因此这个上界同时是内存上界：
 * 一个反复上传又不确认的客户端无法把服务端撑爆。过期条目在每次读写时顺手回收。
 *
 * <p>时钟可注入：10 分钟有效期不可能靠真实等待来验证。错误类型复用成绩模块已有的两种：
 * 「令牌不在了」是 {@link TeacherGradeImportService.NotFoundException}，「预览版本已变」是
 * {@link TeacherGradeBookService.ConflictException} —— 后者也是成绩草稿冲突的类型，Handler 因此
 * 只需认识一套错误映射，不会因为多了一个服务就多一种冲突响应。
 */
public final class TeacherGradeImportStore {

    /** 预览令牌有效期：10 分钟（设计第 9 节），与 2 分钟的文件票据是两张不同的票。 */
    public static final long PREVIEW_TTL_MILLIS = 10L * 60 * 1000;

    /** 同一教师同时存活的预览上限：超出时淘汰最快过期的那一份。 */
    public static final int MAX_PREVIEWS_PER_TEACHER = 8;

    /** 全部教师同时存活的预览上限。 */
    public static final int MAX_PREVIEWS = 64;

    private final Clock clock;
    private final Map<String, Preview> previews = new ConcurrentHashMap<>();

    public TeacherGradeImportStore() {
        this(Clock.systemUTC());
    }

    public TeacherGradeImportStore(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 登记一份新预览：生成令牌、版本从 1 开始、按注入时钟计算过期时刻，并先按上界淘汰旧预览。
     *
     * @param roster 该教学班当前的正常名单（学号/姓名/选课记录），与 {@code baseline} 同源
     * @param baseline 每位正常名单学生的「原草稿值」；文件缺列或空白时保留的就是这份值
     */
    public Preview open(String teacherUid, String offeringId, int baseRevision, String rosterDigest,
            GradeSchemeDTO scheme, List<TeacherSpreadsheetRow> rows,
            List<TeacherRosterRowDTO> roster, Map<Long, GradeScoresDTO> baseline) {
        Instant now = clock.instant();
        sweep(now);
        Preview preview = new Preview(UUID.randomUUID().toString(), teacherUid, offeringId,
                baseRevision, rosterDigest, scheme, now.plusMillis(PREVIEW_TTL_MILLIS), 1, rows,
                roster, baseline, List.of(), List.of());
        evict(teacherUid);
        previews.put(preview.importToken(), preview);
        return preview;
    }

    /**
     * 取一份仍然有效、属于该教师的预览。
     *
     * @throws TeacherGradeImportService.NotFoundException 令牌不存在、已过期或属于别的教师
     *         （三种情况给同一句话，不区分）
     */
    public Preview require(String teacherUid, String token) {
        Preview preview = find(teacherUid, token);
        if (preview == null) {
            throw new TeacherGradeImportService.NotFoundException(
                    TeacherGradeImportService.EXPIRED_PREVIEW);
        }
        return preview;
    }

    /** 同上，但令牌不存在/过期/属于别人时返回 {@code null}（确认导入据此判断是否走重放）。 */
    public Preview find(String teacherUid, String token) {
        sweep(clock.instant());
        if (token == null || token.isBlank()) {
            return null;
        }
        Preview preview = previews.get(token);
        if (preview == null || !preview.teacherUid().equals(teacherUid)) {
            return null;
        }
        return preview;
    }

    /**
     * 用一次修订替换预览状态：版本号加一，修正与排除记录整份换成这一轮的值。
     *
     * <p>用条件替换保留并发语义：同一份预览的两次并发修订只有一次成功，另一次拿到冲突而不是
     * 悄悄覆盖别人的结果。过期或已被取消的预览同样走冲突分支。
     */
    public Preview replace(Preview current, List<GradeImportCorrectionDTO> corrections,
            List<Integer> excludedRows) {
        Preview updated = new Preview(current.importToken(), current.teacherUid(),
                current.offeringId(), current.baseRevision(), current.rosterDigest(),
                current.scheme(), current.expiresAt(), current.previewRevision() + 1, current.rows(),
                current.roster(), current.baseline(), corrections, excludedRows);
        if (!previews.replace(current.importToken(), current, updated)) {
            throw new TeacherGradeBookService.ConflictException(
                    "导入预览已更新或已过期，请重新加载预览后重试", null);
        }
        return updated;
    }

    /** 取消导入：只丢弃属于该教师的令牌，重复取消或令牌已被消费都是无操作。 */
    public void discard(String teacherUid, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        previews.computeIfPresent(token,
                (key, preview) -> preview.teacherUid().equals(teacherUid) ? null : preview);
    }

    /** 当前存活的预览数，供测试与诊断使用。 */
    public int size() {
        sweep(clock.instant());
        return previews.size();
    }

    /** 回收过期条目。没有后台线程：每次读写顺手清理，条目数本来就有上界。 */
    private void sweep(Instant now) {
        for (Map.Entry<String, Preview> entry : previews.entrySet()) {
            if (!entry.getValue().expiresAt().isAfter(now)) {
                previews.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 为一份新预览腾位置：先按教师上界，再按全局上界，每次淘汰最快过期的那一份。 */
    private void evict(String teacherUid) {
        while (countFor(teacherUid) >= MAX_PREVIEWS_PER_TEACHER) {
            if (!evictOldest(teacherUid)) break;
        }
        while (previews.size() >= MAX_PREVIEWS) {
            if (!evictOldest(null)) break;
        }
    }

    private int countFor(String teacherUid) {
        int count = 0;
        for (Preview preview : previews.values()) {
            if (preview.teacherUid().equals(teacherUid)) count++;
        }
        return count;
    }

    /** 淘汰某位教师（{@code null} 表示不限教师）中过期最早的预览；仓库为空时返回 false。 */
    private boolean evictOldest(String teacherUid) {
        String oldestKey = null;
        Instant oldest = null;
        for (Map.Entry<String, Preview> entry : previews.entrySet()) {
            Preview preview = entry.getValue();
            if (teacherUid != null && !preview.teacherUid().equals(teacherUid)) continue;
            if (oldest == null || preview.expiresAt().isBefore(oldest)) {
                oldest = preview.expiresAt();
                oldestKey = entry.getKey();
            }
        }
        return oldestKey != null && previews.remove(oldestKey) != null;
    }

    /**
     * 一份预览的全部服务端状态：候选由这里的原始行、原草稿值与修正记录每次重新算出，
     * 因此修订不会累积中间状态。列表与映射在构造时防御性复制，对外只读。
     */
    public record Preview(String importToken, String teacherUid, String offeringId, int baseRevision,
            String rosterDigest, GradeSchemeDTO scheme, Instant expiresAt, int previewRevision,
            List<TeacherSpreadsheetRow> rows, List<TeacherRosterRowDTO> roster,
            Map<Long, GradeScoresDTO> baseline, List<GradeImportCorrectionDTO> corrections,
            List<Integer> excludedRows) {

        public Preview {
            rows = rows == null ? List.of() : List.copyOf(rows);
            roster = roster == null ? List.of() : List.copyOf(roster);
            baseline = baseline == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(baseline));
            corrections = corrections == null
                    ? List.of() : List.copyOf(new ArrayList<>(corrections));
            excludedRows = excludedRows == null ? List.of() : List.copyOf(excludedRows);
        }

        /** 按行号索引的修正记录，供合并时逐行取用。 */
        public Map<Integer, GradeImportCorrectionDTO> correctionsByRow() {
            Map<Integer, GradeImportCorrectionDTO> byRow = new LinkedHashMap<>();
            for (GradeImportCorrectionDTO correction : corrections) {
                byRow.put(correction.getRowNumber(), correction);
            }
            return byRow;
        }
    }
}
