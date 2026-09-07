package util.pdf;

import entity.Student;
import entity.StudentAward;
import entity.StudentFamilyMember;
import entity.StudentExperience;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static util.pdf.StudentPdfLayout.*;

/**
 * 学生学籍登记表 PDF 导出工具。
 *
 * <p>
 * 使用固定的 student_profile_template.pdf 作为模板，
 * 将 Student 正式学籍信息和奖励信息写入指定位置。
 * </p>
 *
 * <p>
 * 注意：
 * 1. 本工具运行在客户端。
 * 2. 不访问数据库。
 * 3. 不发送 Socket 请求。
 * 4. 导出的必须是已经正式生效的 Student 数据，
 *    不使用待审核的新值。
 * </p>
 */
public final class StudentPdfExport {

    /**
     * PDF 模板在 resources 中的位置。
     */
    private static final String TEMPLATE_PATH =
            "/resources/pdf/student_profile_template.pdf";

    private StudentPdfExport() {
    }

    /**
     * 导出学生学籍登记表。
     *
     * @param student    正式 Student 数据
     * @param experiences 学生维护的既有学习经历
     * @param awards     学生奖励列表
     * @param familyMembers 家庭主要关系成员
     * @param outputFile 最终保存的 PDF 文件
     */
    public static void export(
            Student student,
            List<StudentExperience> experiences,
            List<StudentAward> awards,
            List<StudentFamilyMember> familyMembers,
            File outputFile) throws IOException {

        if (student == null) {
            throw new IllegalArgumentException(
                    "Student 不能为空");
        }

        if (outputFile == null) {
            throw new IllegalArgumentException(
                    "输出文件不能为空");
        }

        if (awards == null) {
            awards = Collections.emptyList();
        }
        if (experiences == null) {
            experiences = Collections.emptyList();
        }
        if (familyMembers == null) {
            familyMembers = Collections.emptyList();
        }

        /*
         * 1. 加载 PDF 模板
         */
        try (InputStream templateStream =
                     StudentPdfExport.class
                             .getResourceAsStream(
                                     TEMPLATE_PATH)) {

            if (templateStream == null) {
                throw new IOException(
                        "找不到 PDF 模板："
                                + TEMPLATE_PATH);
            }

            byte[] templateBytes =
                    templateStream.readAllBytes();

            /*
             * 2. 打开模板
             */
            try (PDDocument document =
                         Loader.loadPDF(
                                 templateBytes)) {

                if (document.getNumberOfPages() == 0) {
                    throw new IOException(
                            "PDF 模板没有页面");
                }

                PDPage page =
                        document.getPage(0);

                /*
                 * 3. 加载字体：
                 *    中文（含全角标点）使用宋体，
                 *    英文、数字使用 Times New Roman。
                 */
                PDType0Font cjkFont =
                        PdfFontManage.loadChineseFont(document);

                PDType0Font latinFont =
                        PdfFontManage.loadTimesNewRoman(document);

                /*
                 * 4. APPEND 模式：
                 *    不覆盖模板原来的表格、标题和固定文字，
                 *    只把数据叠加上去。
                 */
                try (PDPageContentStream content =
                             new PDPageContentStream(
                                     document,
                                     page,
                                     PDPageContentStream
                                             .AppendMode
                                             .APPEND,
                                     true,
                                     true)) {

                    writeBasicInfo(
                            content,
                            latinFont,
                            cjkFont,
                            student);

                    writeAwards(
                            content,
                            latinFont,
                            cjkFont,
                            awards);
                    writeStudyExperiences(content,latinFont,cjkFont,student,experiences);
                    writeFamilyMembers(content,latinFont,cjkFont,familyMembers);
                }

                /*
                 * 5. 保存最终 PDF
                 */
                document.save(outputFile);
            }
        }
    }


    // =========================================================
    // 一、基本信息
    // =========================================================

    private static void writeBasicInfo(
            PDPageContentStream content,
            PDType0Font latinFont,
            PDType0Font cjkFont,
            Student student) throws IOException {

        /*
         * 第一行：
         * 姓名 / 性别 / 出生年月
         */

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                NAME,
                value(student.getName()));

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                GENDER,
                value(student.getGender()));

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                BIRTH_DATE,
                formatDate(
                        student.getBirthDate()));


        /*
         * 第二行：
         * 籍贯 / 民族 / 政治面貌
         */

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                NATIVE_PLACE,
                value(student.getNativePlace()));

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                NATIONALITY,
                value(student.getNationality()));

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                POLITICAL_STATUS,
                value(student.getPoliticalStatus()));


        /*
         * 第三行：
         * 学历 / 学院 / 专业
         *
         * 当前 Student 中 educationLevel
         * 对应模板中的“学历”。
         */

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                EDUCATION,
                value(student.getEducationLevel()));

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                COLLEGE,
                value(student.getCollege()));

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                MAJOR,
                value(student.getMajor()));


        /*
         * 第四行：
         * 学制 / 入学日期
         */

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                SCHOOLING_LENGTH,
                formatSchoolingLength(
                        student.getSchoolingLength()));

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                ADMISSION_DATE,
                formatDate(
                        student.getAdmissionDate()));


        /*
         * 第五行：
         * 证件号码 / 联系电话
         */

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                ID_NUMBER,
                maskIdNumber(
                        student.getIdNumber()));

        /*
         * 联系电话优先使用手机号。
         * 如果手机号为空，则使用 telephone。
         */
        drawTextFit(
                content,
                latinFont,
                cjkFont,
                TELEPHONE,
                firstNonBlank(
                        student.getMobile(),
                        student.getTelephone()));


        /*
         * 第六行：
         * 家庭地址 / 电子邮箱
         *
         * 注意：
         * 你当前 Student 模型没有名字完全等于
         * familyAddress 的字段。
         *
         * 这里暂时使用 registeredResidence
         * （户口所在地）作为“家庭地址”。
         *
         * 如果你以后决定这里应该显示 campusAddress，
         * 只需要修改这一行即可。
         */

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                FAMILY_ADDRESS,
                value(
                        student.getRegisteredResidence()));

        drawTextFit(
                content,
                latinFont,
                cjkFont,
                EMAIL,
                value(student.getEmail()));
    }


    // =========================================================
    // 二、奖励
    // =========================================================

    /**
     * 模板最多显示 4 条奖励。
     */
    private static void writeAwards(
            PDPageContentStream content,
            PDType0Font latinFont,
            PDType0Font cjkFont,
            List<StudentAward> awards)
            throws IOException {

        int count =
                Math.min(
                        awards.size(),
                        AWARD_ROWS.size());

        for (int i = 0; i < count; i++) {

            StudentAward award =
                    awards.get(i);

            StudentPdfLayout.AwardRow row =
                    AWARD_ROWS.get(i);

            /*
             * 时间
             */
            drawTextFit(
                    content,
                    latinFont,
                    cjkFont,
                    row.date(),
                    formatDate(
                            award.getAwardDate()));

            /*
             * 奖励名称
             */
            drawTextFit(
                    content,
                    latinFont,
                    cjkFont,
                    row.description(),
                    buildAwardDescription(
                            award));
        }
    }


    /**
     * 拼接奖励描述。
     *
     * 例如：
     *
     * 国家奖学金（国家级）
     *
     * 如果 awardLevel 为空，
     * 就只显示 awardName。
     */
    private static String buildAwardDescription(
            StudentAward award) {

        if (award == null) {
            return "-";
        }

        String name =
                value(award.getAwardName());

        String level =
                rawValue(award.getAwardLevel());

        if (level == null
                || level.isBlank()) {
            return name;
        }

        return name
                + "（"
                + level
                + "）";
    }

    /** 先写学生维护的既有经历，再自动补上“入学年月—至今”的当前大学经历。 */
    private static void writeStudyExperiences(
            PDPageContentStream content, PDType0Font latinFont,
            PDType0Font cjkFont, Student student,
            List<StudentExperience> experiences) throws IOException {
        int maintainedLimit = student.getAdmissionDate() == null
                ? EXPERIENCE_ROWS.size() : EXPERIENCE_ROWS.size() - 1;
        int count = Math.min(experiences.size(), maintainedLimit);
        for (int i = 0; i < count; i++) {
            StudentExperience experience = experiences.get(i);
            StudentPdfLayout.ExperienceRow row = EXPERIENCE_ROWS.get(i);
            String end = experience.getEndDate() == null ? "至今" : formatMonth(experience.getEndDate());
            drawTextFit(content, latinFont, cjkFont, row.dateRange(),
                    formatMonth(experience.getStartDate()) + " 至 " + end);
            drawTextFit(content, latinFont, cjkFont, row.placeAndUnit(), value(experience.getSchoolName()));
            drawTextFit(content, latinFont, cjkFont, row.duty(), "学生");
        }
        if (student.getAdmissionDate() != null && count < EXPERIENCE_ROWS.size()) {
            StudentPdfLayout.ExperienceRow row = EXPERIENCE_ROWS.get(count);
            drawTextFit(content, latinFont, cjkFont, row.dateRange(),
                    formatMonth(student.getAdmissionDate()) + " 至今");
            drawTextFit(content, latinFont, cjkFont, row.placeAndUnit(),
                    "东南大学 " + value(student.getCollege()));
            drawTextFit(content, latinFont, cjkFont, row.duty(), "学生");
        }
    }

    /** 将维护的家庭成员写入模板的主要社会关系区域。 */
    private static void writeFamilyMembers(
            PDPageContentStream content, PDType0Font latinFont,
            PDType0Font cjkFont, List<StudentFamilyMember> members) throws IOException {
        int count = Math.min(members.size(), SOCIAL_RELATION_ROWS.size());
        for (int i = 0; i < count; i++) {
            StudentFamilyMember member = members.get(i);
            StudentPdfLayout.SocialRelationRow row = SOCIAL_RELATION_ROWS.get(i);
            drawTextFit(content, latinFont, cjkFont, row.name(), value(member.getName()));
            drawTextFit(content, latinFont, cjkFont, row.relation(), value(member.getRelationship()));
            drawTextFit(content, latinFont, cjkFont, row.workUnit(), value(member.getWorkplace()));
            drawTextFit(content, latinFont, cjkFont, row.telephone(), value(member.getPhone()));
        }
    }


    // =========================================================
    // 三、通用文字绘制
    // =========================================================

    /**
     * 根据 TextSlot 写入文字。
     *
     * 如果内容过长，则自动缩小字号，
     * 防止文字超出当前单元格。
     */
    static void drawTextFit(
            PDPageContentStream content,
            PDType0Font latinFont,
            PDType0Font cjkFont,
            StudentPdfLayout.TextSlot slot,
            String text) throws IOException {

        String safeText =
                normalizeText(text);

        float fontSize =
                slot.fontSize();

        /*
         * 最小字号设置为 7。
         */
        final float minFontSize = 7.0f;

        while (fontSize > minFontSize) {

            float width =
                    getTextWidth(
                            latinFont,
                            cjkFont,
                            safeText,
                            fontSize);

            if (width <= slot.maxWidth()) {
                break;
            }

            fontSize -= 0.5f;
        }

        /*
         * 如果缩到 7pt 后仍然太长，
         * 再进行省略处理。
         */
        safeText =
                fitWithEllipsis(
                        latinFont,
                        cjkFont,
                        safeText,
                        fontSize,
                        slot.maxWidth());

        drawTextAt(
                content,
                latinFont,
                cjkFont,
                safeText,
                fontSize,
                slot.x(),
                slot.y());
    }


    /**
     * 在指定位置绘制文字。
     *
     * 按字符脚本拆分：
     * ASCII（英文、数字、半角标点）使用 Times New Roman，
     * 其余（中文、全角标点）使用宋体。
     */
    private static void drawTextAt(
            PDPageContentStream content,
            PDType0Font latinFont,
            PDType0Font cjkFont,
            String text,
            float fontSize,
            float x,
            float y) throws IOException {

        content.beginText();

        content.newLineAtOffset(
                x,
                y);

        int index = 0;

        while (index < text.length()) {

            int start = index;

            boolean latin =
                    isLatin(text.charAt(index));

            while (index < text.length()
                    && isLatin(text.charAt(index))
                    == latin) {

                index++;
            }

            content.setFont(
                    latin ? latinFont : cjkFont,
                    fontSize);

            content.showText(
                    text.substring(
                            start,
                            index));
        }

        content.endText();
    }


    /**
     * 获取某段文字的实际宽度。
     *
     * 按脚本拆分后分别用对应字体计算，
     * 再累加，得到混合字体的总宽度。
     */
    private static float getTextWidth(
            PDType0Font latinFont,
            PDType0Font cjkFont,
            String text,
            float fontSize)
            throws IOException {

        float total = 0f;

        int index = 0;

        while (index < text.length()) {

            int start = index;

            boolean latin =
                    isLatin(text.charAt(index));

            while (index < text.length()
                    && isLatin(text.charAt(index))
                    == latin) {

                index++;
            }

            PDType0Font font =
                    latin ? latinFont : cjkFont;

            total += font.getStringWidth(
                    text.substring(
                            start,
                            index))
                    / 1000f
                    * fontSize;
        }

        return total;
    }


    /**
     * 内容仍然太长时自动变为：
     *
     * 计算机科学与工程学...
     */
    private static String fitWithEllipsis(
            PDType0Font latinFont,
            PDType0Font cjkFont,
            String text,
            float fontSize,
            float maxWidth)
            throws IOException {

        if (getTextWidth(
                latinFont,
                cjkFont,
                text,
                fontSize) <= maxWidth) {

            return text;
        }

        String suffix = "...";

        String result = text;

        while (!result.isEmpty()) {

            String candidate =
                    result + suffix;

            if (getTextWidth(
                    latinFont,
                    cjkFont,
                    candidate,
                    fontSize) <= maxWidth) {

                return candidate;
            }

            result =
                    result.substring(
                            0,
                            result.length() - 1);
        }

        return suffix;
    }


    /**
     * 判断字符是否属于“西文”范围：
     * ASCII 使用 Times New Roman，
     * 其余（中文、全角标点）使用宋体。
     */
    private static boolean isLatin(char c) {

        return c < 0x80;
    }


    // =========================================================
    // 四、格式化工具
    // =========================================================

    /**
     * 普通值转换。
     *
     * null 和空字符串显示为 "-"。
     */
    private static String value(
            Object value) {

        String text =
                rawValue(value);

        if (text == null
                || text.isBlank()) {

            return "-";
        }

        return text;
    }


    /**
     * 不主动替换成 "-"。
     */
    private static String rawValue(
            Object value) {

        if (value == null) {
            return null;
        }

        return value.toString().trim();
    }


    /**
     * 日期统一输出 yyyy-MM-dd。
     *
     * 同时兼容：
     * java.util.Date
     * java.sql.Date
     * LocalDate
     */
    private static String formatDate(
            Object date) {

        if (date == null) {
            return "-";
        }

        if (date instanceof LocalDate localDate) {

            return localDate.toString();
        }

        if (date instanceof java.sql.Date sqlDate) {

            return sqlDate
                    .toLocalDate()
                    .toString();
        }

        if (date instanceof java.util.Date utilDate) {

            SimpleDateFormat format =
                    new SimpleDateFormat(
                            "yyyy-MM-dd");

            return format.format(
                    utilDate);
        }

        return date.toString();
    }

    private static String formatMonth(java.sql.Date date) {
        return date.toLocalDate().toString().substring(0, 7);
    }


    /**
     * 学制格式。
     *
     * 如果 Student.schoolingLength 是 4，
     * PDF 显示为“4年”。
     *
     * 如果本身已经是“4年”，则保持不变。
     */
    private static String formatSchoolingLength(
            Object schoolingLength) {

        if (schoolingLength == null) {
            return "-";
        }

        String text =
                schoolingLength
                        .toString()
                        .trim();

        if (text.isBlank()) {
            return "-";
        }

        // 学制为 int，未填写时默认值是 0，不应显示成“0年”。
        if ("0".equals(text)) {
            return "-";
        }

        if (text.endsWith("年")) {
            return text;
        }

        return text + "年";
    }


    /**
     * 身份证号码脱敏。
     *
     * 例如：
     * 320100200801011234
     *
     * →
     *
     * 3201**********1234
     */
    private static String maskIdNumber(
            String idNumber) {

        if (idNumber == null
                || idNumber.isBlank()) {

            return "-";
        }

        String id =
                idNumber.trim();

        if (id.length() < 8) {
            return id;
        }

        return id.substring(0, 4)
                + "**********"
                + id.substring(
                id.length() - 4);
    }


    /**
     * 两个联系电话中选择第一个非空值。
     */
    private static String firstNonBlank(
            String first,
            String second) {

        if (first != null
                && !first.isBlank()) {

            return first;
        }

        if (second != null
                && !second.isBlank()) {

            return second;
        }

        return "-";
    }


    /**
     * PDFBox showText 不适合直接处理换行符，
     * 因此统一替换为空格。
     */
    private static String normalizeText(
            String text) {

        if (text == null
                || text.isBlank()) {

            return "-";
        }

        return text
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\t", " ")
                .trim();
    }
}
