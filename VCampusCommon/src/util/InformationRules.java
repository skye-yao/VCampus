package util;

/** Shared limits for information forms, validation and fixed PDF templates. */
public final class InformationRules {
    public static final int STUDENT_EXPERIENCES = 4;
    public static final int STUDENT_FAMILY = 4;
    public static final int TEACHER_EXPERIENCES = 5;
    public static final int TEACHER_FAMILY = 4;
    public static final int AWARDS_EXPORTED = 4;
    public static final int REVIEW_NOTE_LENGTH = 255;
    private InformationRules() {}
    public static String reviewNote(String value) {
        String note = value == null ? "" : value.trim();
        if (note.codePointCount(0, note.length()) > REVIEW_NOTE_LENGTH)
            throw new IllegalArgumentException("审核意见最多255个字符");
        return note;
    }
    public static void requireRoom(int count, int limit, String label) {
        if (count >= limit) throw new IllegalStateException(label + "最多" + limit + "条，请先删除已有记录");
    }
    public static void requireExportCapacity(int count, int limit, String label) {
        if (count > limit) throw new IllegalArgumentException(label + "超过模板上限" + limit + "条，请整理记录后再导出");
    }
}
