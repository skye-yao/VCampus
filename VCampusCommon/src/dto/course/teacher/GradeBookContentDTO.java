package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 保存草稿或提交成绩时的完整编辑内容（位于写请求的 {@code data.request.content}）。
 *
 * <p>写入都必须带上 {@code expectedRevision} 与 {@code rosterDigest}：前者是客户端看到的草稿版本，
 * 后者是客户端看到的正常名单摘要，服务端在 offering 锁内比对，版本过期或名单变化时拒绝静默覆盖。
 * {@code rows} 只包含 enrollmentId 与分数，不接收客户端总评/绩点。
 * 列表在构造时防御性复制并对外只读，保证写事务执行过程中内容不被调用方改写。
 */
public final class GradeBookContentDTO {
    private final String offeringId;
    private final int expectedRevision;
    private final String rosterDigest;
    private final GradeSchemeDTO scheme;
    private final List<GradeRowInputDTO> rows;

    public GradeBookContentDTO(String offeringId, int expectedRevision, String rosterDigest,
            GradeSchemeDTO scheme, List<GradeRowInputDTO> rows) {
        this.offeringId = offeringId;
        this.expectedRevision = expectedRevision;
        this.rosterDigest = rosterDigest;
        this.scheme = scheme;
        this.rows = immutableCopy(rows);
    }

    public String getOfferingId() {
        return offeringId;
    }

    public int getExpectedRevision() {
        return expectedRevision;
    }

    public String getRosterDigest() {
        return rosterDigest;
    }

    public GradeSchemeDTO getScheme() {
        return scheme;
    }

    /** 构造时复制、反序列化后也返回不可修改视图，避免写入过程中行集合被改写。 */
    public List<GradeRowInputDTO> getRows() {
        return unmodifiable(rows);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <T> List<T> unmodifiable(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(values);
    }
}
