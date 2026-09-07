package util.pdf;

import entity.Teacher;
import entity.TeacherFamilyMember;
import entity.TeacherWorkExperience;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Date;
import java.util.List;

/** 将教师正式资料写入教师登记表，签字和学院意见留白供打印填写。 */
public final class TeacherPdfExport {
    private static final String TEMPLATE = "/resources/pdf/teacher_profile_template.pdf";
    private static final float[] EXPERIENCE_Y = {304.68f, 330.72f, 356.64f, 382.68f, 408.72f};
    private static final float[] FAMILY_Y = {460.68f, 486.72f, 512.64f, 538.68f};

    private TeacherPdfExport() {}

    public static void export(Teacher teacher, List<TeacherWorkExperience> experiences,
                              List<TeacherFamilyMember> familyMembers, File output) throws IOException {
        if (teacher == null || output == null) throw new IllegalArgumentException("教师和输出文件不能为空");
        List<TeacherWorkExperience> work = experiences == null ? List.of() : experiences;
        List<TeacherFamilyMember> family = familyMembers == null ? List.of() : familyMembers;
        try (InputStream input = TeacherPdfExport.class.getResourceAsStream(TEMPLATE)) {
            if (input == null) throw new IOException("找不到 PDF 模板：" + TEMPLATE);
            byte[] bytes = input.readAllBytes();
            try (PDDocument document = Loader.loadPDF(bytes)) {
                if (document.getNumberOfPages() != 1) throw new IOException("教师 PDF 模板应为单页登记表");
                // 超出模板行数时续页，避免遗漏教师已维护的经历或社会关系。
                int pages = Math.max(1, Math.max((work.size() + 4) / 5, (family.size() + 3) / 4));
                for (int i = 1; i < pages; i++) {
                    try (PDDocument template = Loader.loadPDF(bytes)) {
                        new org.apache.pdfbox.multipdf.PDFMergerUtility().appendDocument(document, template);
                    }
                }
                PDType0Font latin = PdfFontManage.loadTimesNewRoman(document);
                PDType0Font chinese = PdfFontManage.loadChineseFont(document);
                for (int p = 0; p < pages; p++) {
                    try (PDPageContentStream content = new PDPageContentStream(document, document.getPage(p),
                            PDPageContentStream.AppendMode.APPEND, true, true)) {
                        Writer writer = new Writer(content, latin, chinese);
                        writer.basic(teacher);
                        for (int row = 0; row < EXPERIENCE_Y.length && p * 5 + row < work.size(); row++) {
                            TeacherWorkExperience item = work.get(p * 5 + row);
                            float y = EXPERIENCE_Y[row];
                            writer.text(118.80f, y, 212.40f, month(item.getStartDate()) + " 至 " +
                                    (item.getEndDate() == null ? "至今" : month(item.getEndDate())));
                            writer.text(218.07f, y, 460.32f, item.getOrganization());
                            writer.text(466.13f, y, 538.08f, item.getPosition());
                        }
                        for (int row = 0; row < FAMILY_Y.length && p * 4 + row < family.size(); row++) {
                            TeacherFamilyMember item = family.get(p * 4 + row);
                            float y = FAMILY_Y[row];
                            writer.text(118.80f, y, 184.08f, item.getName());
                            writer.text(189.77f, y, 247.92f, item.getRelationship());
                            writer.text(253.55f, y, 460.32f, item.getWorkplace());
                            writer.text(466.13f, y, 538.08f, item.getPhone());
                        }
                        if (pages > 1) writer.text(460, 789, 538, "第 " + (p + 1) + " / " + pages + " 页");
                    }
                }
                document.save(output);
            }
        }
    }

    private static String month(Date date) { return date == null ? "-" : date.toString().substring(0, 7); }

    private record Writer(PDPageContentStream content, PDType0Font latin, PDType0Font chinese) {
        void text(float x, float top, float right, Object value) throws IOException {
            StudentPdfExport.drawTextFit(content, latin, chinese,
                    new StudentPdfLayout.TextSlot(x, 841.92004f - top, right - x - 5, 10),
                    value == null ? "-" : value.toString());
        }

        void basic(Teacher teacher) throws IOException {
            text(118.80f, 122.64f, 184.08f, teacher.getName());
            text(253.60f, 122.64f, 331.80f, teacher.getGender());
            text(395.28f, 122.64f, 460.32f, teacher.getBirthDate());
            text(118.80f, 148.68f, 184.08f, teacher.getNativePlace());
            text(253.60f, 148.68f, 331.80f, teacher.getNationality());
            text(395.28f, 148.68f, 460.32f, teacher.getPoliticalStatus());
            // 当前 Teacher 未维护学历，沿用缺失值占位。
            text(118.80f, 174.72f, 184.08f, teacher.getEducation());
            text(253.60f, 174.72f, 331.80f, teacher.getCollege());
            text(395.28f, 174.72f, 460.32f, teacher.getDepartment());
            text(118.80f, 200.64f, 184.08f, teacher.getTitle());
            // 当前 Teacher 未维护入职日期，不以工作经历日期代替。
            text(253.58f, 200.64f, 460.32f, teacher.getEmploymentStartDate());
            String id = teacher.getIdNumber();
            text(118.80f, 226.68f, 331.80f, id == null || id.length() < 8 ? id :
                    id.substring(0, 4) + "**********" + id.substring(id.length() - 4));
            text(395.28f, 226.68f, 538.08f, teacher.getMobile() == null || teacher.getMobile().isBlank() ?
                    teacher.getTelephone() : teacher.getMobile());
            text(118.80f, 252.72f, 331.80f, teacher.getRegisteredResidence());
            text(395.28f, 252.72f, 538.08f, teacher.getEmail());
        }
    }
}
