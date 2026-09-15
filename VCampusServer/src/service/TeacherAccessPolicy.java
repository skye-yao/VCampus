package service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 教师端授权判定：系统角色先要是“教师”，再按教学班关系与当前正式安排/生效调课判定。
 *
 * <p>设计第 4 节要求每次查询和写操作都重新检查关系，只读关系按“当前”计算，不能把历史任课
 * 关系无限扩张成当前权限，所以这里不缓存、不接收已解析的权限位，只接收 {@code uid} 与
 * {@code offeringId}，并由调用方保证 {@code uid} 来自服务端校验过的 Session。
 *
 * <p>拒绝以 {@link AccessDeniedException} 表达，调用方据此返回 FORBIDDEN；不能用返回
 * {@code null} 或空集合代替，否则“无权限”会与“对象不存在/空名单”混淆。
 */
public final class TeacherAccessPolicy {

    /** 教师无权访问该教学班；与“对象不存在”区分开，由上层映射为 FORBIDDEN。 */
    public static final class AccessDeniedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AccessDeniedException(String message) {
            super(message);
        }
    }

    /**
     * 只读教学班/名单：任课教师 role=0、系统角色为教师的助教 role=1，或在该教学班当前
     * PUBLISHED 正式安排 / 生效（ACTIVE）调课中担任任课教师。
     */
    public void requireViewOffering(Connection connection, String uid, long offeringId)
            throws SQLException {
        if (!isTeacher(connection, uid) || offeringId <= 0) {
            throw new AccessDeniedException("没有查看该教学班的权限");
        }
        if (hasOfferingRelation(connection, uid, offeringId)) {
            return;
        }
        throw new AccessDeniedException("没有查看该教学班的权限");
    }

    /** 成绩编辑提交：只允许该教学班 {@code course_offering_teacher.role=0} 的任课教师。 */
    public void requireEditGrades(Connection connection, String uid, long offeringId)
            throws SQLException {
        if (!isTeacher(connection, uid) || offeringId <= 0) {
            throw new AccessDeniedException("没有编辑该教学班成绩的权限");
        }
        if (isOfferingTeacher(connection, uid, offeringId)) return;
        throw new AccessDeniedException("只有任课教师可以编辑成绩");
    }

    /**
     * 成绩编辑能力位：系统角色为教师、且是该教学班 {@code course_offering_teacher.role=0} 的任课教师。
     *
     * <p>只用于界面显隐（例如助教可以读成绩表但不能编辑）；写操作仍然调用
     * {@link #requireEditGrades}，能力位不参与授权判定。
     */
    public boolean canEditGrades(Connection connection, String uid, long offeringId)
            throws SQLException {
        if (!isTeacher(connection, uid) || offeringId <= 0) return false;
        return isOfferingTeacher(connection, uid, offeringId);
    }

    private static boolean isOfferingTeacher(Connection connection, String uid, long offeringId)
            throws SQLException {
        String sql = "SELECT 1 FROM course_offering_teacher"
                + " WHERE offering_id=? AND uid=? AND role=0";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            statement.setString(2, uid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean isTeacher(Connection connection, String uid) throws SQLException {
        if (uid == null || uid.isBlank()) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM tbl_user WHERE UID=? AND role=1")) {
            statement.setString(1, uid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean hasOfferingRelation(Connection connection, String uid, long offeringId)
            throws SQLException {
        // 三段关系都必须落在同一个 offering_id 上：教学班成员、当前 PUBLISHED 正式安排的任课
        // 教师、以及生效调课的任课教师。DRAFT 方案与 DISABLED 安排不参与判定。
        String sql = "SELECT ("
                + " EXISTS(SELECT 1 FROM course_offering_teacher t"
                + "     WHERE t.offering_id=? AND t.uid=? AND t.role IN (0,1))"
                + " OR EXISTS(SELECT 1 FROM course_schedule_arrangement a"
                + "     JOIN schedule_plan p ON p.id=a.plan_id AND p.status='PUBLISHED'"
                + "     WHERE a.offering_id=? AND a.status='ACTIVE' AND a.teacher_uid=?)"
                + " OR EXISTS(SELECT 1 FROM course_schedule_adjustment j"
                + "     JOIN course_occurrence o ON o.id=j.original_occurrence_id"
                + "     JOIN course_schedule_rule r ON r.id=o.rule_id"
                + "     JOIN course_schedule_arrangement a ON a.arrangement_id=r.arrangement_id"
                + "     WHERE a.offering_id=? AND j.status='ACTIVE' AND j.teacher_uid=?)"
                + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            statement.setString(2, uid);
            statement.setLong(3, offeringId);
            statement.setString(4, uid);
            statement.setLong(5, offeringId);
            statement.setString(6, uid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }
}
