package util;

import entity.*;
import vo.StudentBatchRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Shared form constraints; the server always validates again. */
public final class StudentBatchValidation {
    public static final int MAX_STUDENTS = 200;
    private StudentBatchValidation() {}
    public static StudentBatchRequest normalize(StudentBatchRequest request, boolean award) {
        if (request == null) throw new IllegalArgumentException("缺少批量添加信息");
        String id = text(request.operationId(), 36, true, "操作编号");
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("操作编号无效"); }
        if (request.studentIds() == null) throw new IllegalArgumentException("请选择学生");
        SortedSet<String> ids = new TreeSet<>();
        for (String studentId : request.studentIds()) ids.add(text(studentId, 20, true, "学号"));
        if (ids.isEmpty() || ids.size() > MAX_STUDENTS) throw new IllegalArgumentException("每批请选择1～200名学生");
        if (award) {
            StudentAward source = request.award();
            if (source == null || request.aid() != null) throw new IllegalArgumentException("奖励模板无效");
            if (source.getAwardId() != null || source.getStudentId() != null) throw new IllegalArgumentException("批量模板不能指定记录编号或所属学生");
            StudentAward a = new StudentAward();
            a.setAwardName(text(source.getAwardName(), 100, true, "奖励名称"));
            if (source.getAwardType() == null) throw new IllegalArgumentException("请选择奖励类型");
            a.setAwardType(source.getAwardType());
            if (source.getAwardDate() == null) throw new IllegalArgumentException("请选择奖励日期");
            a.setAwardDate(source.getAwardDate());
            a.setAwardLevel(text(source.getAwardLevel(), 50, false, "奖励级别"));
            a.setOrganization(text(source.getOrganization(), 100, false, "颁发单位"));
            a.setDescription(text(source.getDescription(), 255, false, "奖励说明"));
            return new StudentBatchRequest(id, List.copyOf(ids), a, null);
        }
        StudentAid source = request.aid();
        if (source == null || request.award() != null) throw new IllegalArgumentException("资助模板无效");
        if (source.getAidId() != null || source.getStudentId() != null) throw new IllegalArgumentException("批量模板不能指定记录编号或所属学生");
        StudentAid a = new StudentAid();
        a.setAidName(text(source.getAidName(), 100, true, "资助名称"));
        a.setAidType(text(source.getAidType(), 50, true, "资助类型"));
        a.setAmount(amount(source.getAmount()));
        if (source.getAidDate() == null) throw new IllegalArgumentException("请选择资助日期");
        a.setAidDate(source.getAidDate());
        if (source.getStatus() == null) throw new IllegalArgumentException("请选择资助状态");
        a.setStatus(source.getStatus());
        a.setProvider(text(source.getProvider(), 100, false, "资助提供方"));
        a.setDescription(text(source.getDescription(), 255, false, "资助说明"));
        return new StudentBatchRequest(id, List.copyOf(ids), null, a);
    }
    public static BigDecimal amount(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("请填写每人金额");
        if (value.signum() < 0 || value.compareTo(new BigDecimal("99999999.99")) > 0)
            throw new IllegalArgumentException("每人金额须在0～99999999.99元之间");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("每人金额最多两位小数"); }
    }
    private static String text(String value, int length, boolean required, String label) {
        String text = value == null ? "" : value.trim();
        if (required && text.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        if (text.codePointCount(0, text.length()) > length) throw new IllegalArgumentException(label + "最多" + length + "个字符");
        return text;
    }
}
