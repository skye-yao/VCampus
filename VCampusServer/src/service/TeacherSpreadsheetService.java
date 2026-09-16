package service;

import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 教师成绩 Excel 的服务端「表格大脑」：解析上传的工作簿、生成空白成绩模板、导出完整名单，
 * 以及导出成绩（名单 + 已保存草稿的四项成绩、总评与绩点）。
 *
 * <p>本类只做文件与文本，不碰数据库、不签发票据、不写草稿：教学班归属与名单由调用方在**生成文件之前**
 * 通过既有归属校验入口取好（导出文件是另一份名单的快照，缺失这一步就会把别人的学生交给教师）。
 * 因此这里没有任何 uid/offeringId 参数，也就无法绕过上层的权限判定。
 *
 * <p>格式约定（设计第 9 节）：
 * <ul>
 *   <li>只读第一张工作表；表头必须是第一行，且至少包含「学号」列。缺少必需列、已知列重复属于
 *       **文件结构错误**，直接抛出并停止预览；未知的多余列被忽略（教师可能自加备注列）。</li>
 *   <li>学号、姓名与四项成绩都用 {@link DataFormatter} 取文本，绝不把学号当数字转换（前导零必须保留）；
 *       这些列里的**公式一律拒绝整份文件**（与「损坏的工作簿」同一个拒绝桶，设计第 9 节）：
 *       本服务不求值，公式一旦被当作成绩读出来，就是一个服务端猜的值冒充教师填的分数。</li>
 *   <li>成绩列可以整列缺失；空白单元格保留原文本，绝不转成 0。</li>
 *   <li>打开工作簿之前先拒绝超限的压缩内容：文件本身不得超过
 *       {@link TeacherFileTicketService#MAX_FILE_BYTES}，解压后总字节不得超过 {@link #MAX_INFLATED_BYTES}。</li>
 *   <li>一个工作簿最多 {@link #MAX_ROWS} 个数据行：解析超限即拒绝，导出超限明确报错，绝不截断。</li>
 * </ul>
 */
public final class TeacherSpreadsheetService {

    /** 单个工作簿的数据行上限：解析、成绩模板与名单导出共用同一口径（设计第 9 节的 5000 行）。 */
    public static final int MAX_ROWS = 5000;

    /**
     * 单个工作簿解压后的字节上限（64 MiB）。
     *
     * <p>设计第 9 节要求拒绝「超限压缩内容」。这个上界由本仓库显式判定，不依赖 POI 的
     * {@code ZipSecureFile} 默认值——默认最小压缩比 0.01 意味着一个 5 MiB 的包可以膨胀到几百 MiB
     * 才被拦下。合法工作簿（≤5000 行、六列短文本）解压后远小于这个数。
     */
    public static final long MAX_INFLATED_BYTES = 64L * 1024 * 1024;

    /** 成绩模板第一张表的固定列，顺序即列序；校验时同一份名单也是「已知表头」的定义。 */
    static final List<String> TEMPLATE_HEADERS =
            List.of("学号", "姓名", "平时成绩", "期中成绩", "实验成绩", "期末成绩");

    private static final String HEADER_STUDENT_UID = "学号";
    private static final String HEADER_STUDENT_NAME = "姓名";
    private static final String SHEET_ENTRY = "成绩录入";
    private static final String SHEET_NOTES = "说明";
    private static final String SHEET_ROSTER = "学生名单";
    private static final String SHEET_GRADES = "学生成绩";
    private static final List<String> ROSTER_HEADERS =
            List.of("学号", "姓名", "专业", "状态", "选课时间", "退课时间");
    /** 成绩导出的固定列，顺序即列序：名单三列 + 四项成绩 + 总评 + 绩点。 */
    static final List<String> GRADE_HEADERS =
            List.of("学号", "姓名", "专业", "平时成绩", "期中成绩", "实验成绩", "期末成绩", "总评", "绩点");
    /** 与教学班详情页一致：空值显示破折号，状态显示「正常/退课」。 */
    private static final String BLANK_TEXT = "—";
    private static final String ENROLLED_TEXT = "正常";
    private static final String DROPPED_TEXT = "退课";
    /** 列名到成绩组成的映射，模板的说明表与解析时的列定位共用一份定义。 */
    private static final Map<GradeComponentCodeDTO, String> COMPONENT_HEADERS = componentHeaders();

    /**
     * 解析上传的工作簿：返回每个数据行的原文与行级结构错误，不写库、不判定业务合法性。
     *
     * <p>不做名册校验（本方法拿不到名单）：学号是否在本班、姓名是否一致由
     * {@link #parse(Path, Collection)} 或 {@link #matchRoster} 叠加。
     *
     * @throws IllegalArgumentException 文件不存在、不是有效的 .xlsx、加密、损坏、超过文件或解压体积上限、
     *         表头缺少学号列、已知表头重复、任一处出现公式、或者数据行超过 {@link #MAX_ROWS}
     */
    public List<TeacherSpreadsheetRow> parse(Path workbook) {
        try (Workbook book = openWorkbook(workbook)) {
            return readSheet(book);
        } catch (IOException failure) {
            // 只可能是关闭工作簿失败：读到的内容已经有效，但句柄没收干净一样是环境问题。
            throw new IllegalStateException("工作簿读取后无法关闭", failure);
        }
    }

    /**
     * 解析后立刻与教学班名单核对：学号不在名单、或提供的姓名与名单不一致都会记为行错误。
     *
     * @param roster 该教学班的完整名单（含退课行），由调用方在归属校验之后取得
     */
    public List<TeacherSpreadsheetRow> parse(Path workbook, Collection<TeacherRosterRowDTO> roster) {
        return matchRoster(parse(workbook), roster);
    }

    /**
     * 名册核对：文件只提供学号（必需）与姓名（可选），事实来源是教学班名单。
     *
     * <p>学号不在名单里 → 该行无法合并，必须由教师修正或明确排除；提供了姓名但与名单不符 →
     * 同样是错误，不能用文件里的姓名覆盖名单。学号为空的行已在解析阶段报过错，这里不重复。
     * 返回新列表，入参行对象保持不变。
     */
    public List<TeacherSpreadsheetRow> matchRoster(List<TeacherSpreadsheetRow> rows,
                                                  Collection<TeacherRosterRowDTO> roster) {
        Map<String, String> namesByUid = new HashMap<>();
        if (roster != null) {
            for (TeacherRosterRowDTO row : roster) {
                if (row != null && row.getStudentUid() != null) {
                    namesByUid.putIfAbsent(row.getStudentUid(), row.getStudentName());
                }
            }
        }
        List<TeacherSpreadsheetRow> matched = new ArrayList<>();
        for (TeacherSpreadsheetRow row : rows) {
            List<TeacherSpreadsheetRow.CellError> errors = new ArrayList<>(row.cellErrors());
            String uid = row.studentUid();
            if (uid.isEmpty()) {
                matched.add(row);
                continue;
            }
            String expectedName = namesByUid.get(uid);
            if (!namesByUid.containsKey(uid)) {
                errors.add(new TeacherSpreadsheetRow.CellError(TeacherSpreadsheetRow.FIELD_STUDENT_UID,
                        uid, "学号不在本教学班的名单中"));
            } else if (!row.studentName().isEmpty() && expectedName != null
                    && !expectedName.equals(row.studentName())) {
                errors.add(new TeacherSpreadsheetRow.CellError(TeacherSpreadsheetRow.FIELD_STUDENT_NAME,
                        row.studentName(), "姓名与本教学班名单不一致（名单：" + expectedName + "）"));
            }
            matched.add(errors.size() == row.cellErrors().size() ? row : row.withCellErrors(errors));
        }
        return List.copyOf(matched);
    }

    /**
     * 生成空白成绩录入模板：第一张表是固定六列（学号、姓名、四项成绩），学号写成文本单元格；
     * 第二张说明表写教学班、各组成权重与禁用项。
     *
     * <p>成绩列一律留空：空白表示「保留成绩表里已有的值」，绝不用 0 冒充未填。模板里的学生来自成绩表
     * （调用方已校验归属），因此教师下载后可以直接按学号填分再上传。
     */
    public void writeGradeTemplate(Path target, TeacherGradeBookDTO gradeBook) {
        requireTarget(target);
        if (gradeBook == null || gradeBook.getScheme() == null) {
            throw new IllegalArgumentException("缺少成绩方案，无法生成成绩模板");
        }
        List<TeacherGradeRowDTO> students = gradeBook.getRows();
        if (students.size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "名单超过 " + MAX_ROWS + " 行，无法生成可再次导入的成绩模板，请先拆分教学班");
        }
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle textStyle = textStyle(workbook);
            Sheet sheet = workbook.createSheet(SHEET_ENTRY);
            Row header = sheet.createRow(0);
            for (int column = 0; column < TEMPLATE_HEADERS.size(); column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(TEMPLATE_HEADERS.get(column));
                cell.setCellStyle(headerStyle);
            }
            int rowIndex = 1;
            for (TeacherGradeRowDTO student : students) {
                Row row = sheet.createRow(rowIndex++);
                Cell uid = row.createCell(0);
                uid.setCellValue(nullToEmpty(student.getStudentUid()));
                uid.setCellStyle(textStyle);
                row.createCell(1).setCellValue(nullToEmpty(student.getStudentName()));
                // 2..5 四个成绩单元格保持空白：留空＝保留已有值，不是 0。
            }
            for (int column = 0; column < TEMPLATE_HEADERS.size(); column++) {
                sheet.setColumnWidth(column, column <= 1 ? 18 * 256 : 12 * 256);
            }
            writeNotesSheet(workbook, gradeBook, headerStyle);
            writeWorkbook(workbook, target);
        } catch (IOException failure) {
            throw new IllegalStateException("无法生成成绩模板", failure);
        }
    }

    /**
     * 导出完整名单：调用方已经按**与列表相同的过滤条件**取好全部行（不是当前页），这里只负责写表。
     *
     * <p>超过 {@link #MAX_ROWS} 行直接报错而不是截断：悄悄少几行会让教师以为名单只有这些，比失败更糟。
     */
    public void writeRoster(Path target, List<TeacherRosterRowDTO> roster) {
        requireTarget(target);
        List<TeacherRosterRowDTO> rows = roster == null ? List.of() : roster;
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "名单超过 " + MAX_ROWS + " 行，无法写出工作簿，请缩小筛选范围后重试");
        }
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle textStyle = textStyle(workbook);
            Sheet sheet = workbook.createSheet(SHEET_ROSTER);
            Row header = sheet.createRow(0);
            for (int column = 0; column < ROSTER_HEADERS.size(); column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(ROSTER_HEADERS.get(column));
                cell.setCellStyle(headerStyle);
            }
            int rowIndex = 1;
            for (TeacherRosterRowDTO student : rows) {
                Row row = sheet.createRow(rowIndex++);
                Cell uid = row.createCell(0);
                uid.setCellValue(nullToEmpty(student.getStudentUid()));
                uid.setCellStyle(textStyle);
                row.createCell(1).setCellValue(blankToDash(student.getStudentName()));
                row.createCell(2).setCellValue(blankToDash(student.getMajor()));
                row.createCell(3).setCellValue(enrollmentStatusText(student.getEnrollmentStatus()));
                row.createCell(4).setCellValue(blankToDash(student.getSelectedAt()));
                row.createCell(5).setCellValue(blankToDash(student.getDroppedAt()));
            }
            int[] widths = {16, 20, 24, 8, 22, 22};
            for (int column = 0; column < widths.length; column++) {
                sheet.setColumnWidth(column, widths[column] * 256);
            }
            writeWorkbook(workbook, target);
        } catch (IOException failure) {
            throw new IllegalStateException("无法导出名单", failure);
        }
    }

    /**
     * 导出成绩：成绩表当前这一版草稿的每一行，加上名单里的专业。
     *
     * <p>列序固定为 {@link #GRADE_HEADERS}，学号是文本单元格（前导零必须保留），六个数字列是
     * 数值单元格（拿到文件就能直接求和）。<b>未填写的成绩一律写 0</b>：这份文件是拿来直接算的，
     * 留空白会让人分不清“还没录”和“丢了”。
     *
     * <p><b>总评与绩点不在这里重算</b>，直接用成绩行自己带的两个值
     * （{@link TeacherGradeRowDTO#getTotalScore()}／{@link TeacherGradeRowDTO#getGradePoint()}）——
     * 它们与页面「总评」「绩点」两列是同一份事实，因此文件与界面不可能对同一行给出不同的结论。
     * 只有“整个启用项都填齐”的行才带着这两个值（禁用项缺分不算缺，{@code GradeCalculator} 会跳过它），
     * 其余情况服务端给的就是 null，这里写 0：分数没填全就不凭空造一个总评出来，页面显示「—」的行
     * 在文件里也是 0。权重未配齐同理（服务端本来就算不出总评）。
     *
     * <p>行集合与总评口径都来自 {@code gradeBook}（与页面同一张表）；{@code roster} 只贡献专业，
     * 不在名单里的行专业留占位符（与名单导出同一个约定）。
     *
     * @param gradeBook 已按归属校验取到的成绩表
     * @param roster    同一教学班的名单（只用来取专业，允许为空）
     */
    public void writeGrades(Path target, TeacherGradeBookDTO gradeBook,
                            List<TeacherRosterRowDTO> roster) {
        requireTarget(target);
        if (gradeBook == null || gradeBook.getScheme() == null) {
            throw new IllegalArgumentException("缺少成绩方案，无法导出成绩");
        }
        List<TeacherGradeRowDTO> rows = gradeBook.getRows();
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "成绩表超过 " + MAX_ROWS + " 行，无法导出成绩，请先拆分教学班");
        }
        Map<String, String> majors = majorsByEnrollmentId(roster);
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle textStyle = textStyle(workbook);
            Sheet sheet = workbook.createSheet(SHEET_GRADES);
            Row header = sheet.createRow(0);
            for (int column = 0; column < GRADE_HEADERS.size(); column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(GRADE_HEADERS.get(column));
                cell.setCellStyle(headerStyle);
            }
            int rowIndex = 1;
            for (TeacherGradeRowDTO student : rows) {
                Row row = sheet.createRow(rowIndex++);
                Cell uid = row.createCell(0);
                uid.setCellValue(nullToEmpty(student.getStudentUid()));
                uid.setCellStyle(textStyle);
                row.createCell(1).setCellValue(blankToDash(student.getStudentName()));
                row.createCell(2).setCellValue(blankToDash(
                        majors.get(nullToEmpty(student.getEnrollmentId()))));
                GradeScoresDTO scores = zeroFilled(student.getScores());
                row.createCell(3).setCellValue(scoreValue(scores.getDailyScore()));
                row.createCell(4).setCellValue(scoreValue(scores.getMidtermScore()));
                row.createCell(5).setCellValue(scoreValue(scores.getExperimentScore()));
                row.createCell(6).setCellValue(scoreValue(scores.getFinaltermScore()));
                row.createCell(7).setCellValue(scoreValue(student.getTotalScore()));
                row.createCell(8).setCellValue(scoreValue(student.getGradePoint()));
            }
            int[] widths = {16, 20, 24, 12, 12, 12, 12, 10, 8};
            for (int column = 0; column < widths.length; column++) {
                sheet.setColumnWidth(column, widths[column] * 256);
            }
            writeWorkbook(workbook, target);
        } catch (IOException failure) {
            throw new IllegalStateException("无法导出成绩", failure);
        }
    }

    /** 名单里的专业按 enrollmentId 索引；同一个学生出现多行时第一行说了算（与名单导出的取值一致）。 */
    private static Map<String, String> majorsByEnrollmentId(List<TeacherRosterRowDTO> roster) {
        Map<String, String> majors = new HashMap<>();
        if (roster == null) return majors;
        for (TeacherRosterRowDTO row : roster) {
            if (row == null || row.getEnrollmentId() == null) continue;
            majors.putIfAbsent(row.getEnrollmentId(), row.getMajor());
        }
        return majors;
    }

    /** 未填写的成绩一律写 0：导出的四个成绩列里不出现空白（禁用项没有分数也写 0）。 */
    private static GradeScoresDTO zeroFilled(GradeScoresDTO scores) {
        if (scores == null) {
            return new GradeScoresDTO(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO);
        }
        return new GradeScoresDTO(orZero(scores.getDailyScore()), orZero(scores.getMidtermScore()),
                orZero(scores.getExperimentScore()), orZero(scores.getFinaltermScore()));
    }

    private static BigDecimal orZero(BigDecimal score) {
        return score == null ? BigDecimal.ZERO : score;
    }

    /**
     * 数字单元格的取值：null（未填写，或服务端算不出总评/绩点）与 0 等价。
     *
     * <p>总评/绩点的 null 不是“缺数据”而是“还不到算的时候”：分数没填全、或权重没配齐时页面显示
     * 占位符，文件里就写 0，两边说的是同一件事。
     */
    private static double scoreValue(BigDecimal score) {
        return score == null ? 0d : score.doubleValue();
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 打开工作簿并把 POI 的异常翻译成可回给客户端的说明。
     *
     * <p>刻意与读取内容分开：读表头/读行的结构错误是本类的 {@link IllegalArgumentException}，
     * 不能被这里的兜底捕获改写成「文件损坏」。
     */
    private static Workbook openWorkbook(Path workbook) {
        if (workbook == null || !Files.isRegularFile(workbook)) {
            throw new IllegalArgumentException("待解析的 Excel 文件不存在");
        }
        requireWithinCompressionLimits(workbook);
        try {
            // readOnly：解析只需要读，流式读取共享字符串表更省内存。
            return WorkbookFactory.create(workbook.toFile(), null, true);
        } catch (EncryptedDocumentException failure) {
            throw new IllegalArgumentException("不支持加密的工作簿，请取消密码保护后重试", failure);
        } catch (UnsupportedFileFormatException failure) {
            throw new IllegalArgumentException("文件不是有效的 .xlsx 工作簿", failure);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("工作簿已损坏，无法读取", failure);
        }
    }

    /**
     * 打开工作簿**之前**拒绝超限的压缩内容：先量文件本身，再逐个累加压缩包中央目录里声明的解压后
     * 大小。任何一项越界都在解压器启动之前失败，因此一个 5 MiB 的包不能让这次解析吃掉任意内存。
     *
     * <p>为什么不能只靠 POI：{@code ZipSecureFile} 的默认最小压缩比是 0.01，也就是允许膨胀到
     * 原文件的 100 倍（5 MiB → 约 500 MiB）才拦下；而且它只对真正被读到的条目生效，包里的
     * 无关巨型条目可以一路留到解析结束。这里判定的正是「这个包整体有多大」，与内容是否被读到无关。
     * 中央目录可以谎报大小，那是 POI 在流式读取时的防线（{@code ZipSecureFile} 默认值仍然生效），
     * 与本层的声明体积上限互补。
     */
    private static void requireWithinCompressionLimits(Path workbook) {
        long fileBytes;
        try {
            fileBytes = Files.size(workbook);
        } catch (IOException failure) {
            throw new IllegalArgumentException("无法读取工作簿的大小", failure);
        }
        if (fileBytes > TeacherFileTicketService.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("工作簿超过 "
                    + TeacherFileTicketService.MAX_FILE_BYTES / (1024 * 1024) + " MiB 文件上限");
        }
        long inflated = 0;
        try (ZipFile archive = new ZipFile(workbook.toFile())) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                long entryBytes = entries.nextElement().getSize();
                // -1 表示中央目录没有给出大小：无法担保的包一律拒绝，绝不「边读边看」。
                if (entryBytes < 0 || entryBytes > MAX_INFLATED_BYTES) {
                    throw new IllegalArgumentException("工作簿包含超过 "
                            + MAX_INFLATED_BYTES / (1024 * 1024) + " MiB 的压缩项");
                }
                inflated += entryBytes;
                if (inflated > MAX_INFLATED_BYTES) {
                    throw new IllegalArgumentException("工作簿解压后超过 "
                            + MAX_INFLATED_BYTES / (1024 * 1024) + " MiB 上限");
                }
            }
        } catch (ZipException failure) {
            // 不是 ZIP 包：加密或旧版工作簿是 OLE2 复合文档，另外还有彻底损坏的文件。三者都不该
            // 在解压器里尝试，说明里都要给出可执行的下一步，而不是一句「服务端内部错误」。
            throw new IllegalArgumentException("文件不是有效的 .xlsx 工作簿（可能已加密、是旧版格式或已损坏），"
                    + "请用 Excel 另存为 .xlsx 后重试", failure);
        } catch (IOException failure) {
            throw new IllegalArgumentException("工作簿已损坏，无法读取", failure);
        }
    }

    private static List<TeacherSpreadsheetRow> readSheet(Workbook book) {
        if (book.getNumberOfSheets() == 0) {
            throw new IllegalArgumentException("工作簿没有工作表");
        }
        Sheet sheet = book.getSheetAt(0);
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        Columns columns = readHeader(sheet.getRow(0), formatter);
        List<TeacherSpreadsheetRow> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            TeacherSpreadsheetRow parsed = readRow(row, rowIndex + 1, columns, formatter);
            if (parsed == null) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                throw new IllegalArgumentException(
                        "数据行超过 " + MAX_ROWS + " 行上限，请拆分文件后重新导入");
            }
            rows.add(parsed);
        }
        return flagDuplicateStudentUids(rows);
    }

    /**
     * 表头：第一行、已知列不得重复、必须含学号列。返回各已知列的下标，缺失的列（姓名或成绩）为 -1。
     */
    private static Columns readHeader(Row header, DataFormatter formatter) {
        if (header == null) {
            throw new IllegalArgumentException("工作表缺少表头行");
        }
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int column = 0; column < header.getLastCellNum(); column++) {
            Cell cell = header.getCell(column);
            if (cell == null) {
                continue;
            }
            String text = formatter.formatCellValue(cell).strip();
            if (!TEMPLATE_HEADERS.contains(text)) {
                continue; // 未知列：教师可能自加备注列，忽略即可，不影响按名取列。
            }
            if (found.putIfAbsent(text, column) != null) {
                throw new IllegalArgumentException("表头重复：「" + text + "」出现了多次");
            }
        }
        Integer uidColumn = found.get(HEADER_STUDENT_UID);
        if (uidColumn == null) {
            throw new IllegalArgumentException("缺少必需的「" + HEADER_STUDENT_UID + "」列");
        }
        return new Columns(uidColumn, columnOrMissing(found, HEADER_STUDENT_NAME),
                columnOrMissing(found, COMPONENT_HEADERS.get(GradeComponentCodeDTO.DAILY)),
                columnOrMissing(found, COMPONENT_HEADERS.get(GradeComponentCodeDTO.MIDTERM)),
                columnOrMissing(found, COMPONENT_HEADERS.get(GradeComponentCodeDTO.EXPERIMENT)),
                columnOrMissing(found, COMPONENT_HEADERS.get(GradeComponentCodeDTO.FINALTERM)));
    }

    /** 读一行；整行已知列都为空时返回 null（Excel 尾部常见空行，不是错误行）。 */
    private static TeacherSpreadsheetRow readRow(Row row, int rowNumber, Columns columns,
                                                DataFormatter formatter) {
        String uid = readLiteralText(cellAt(row, columns.uid()), formatter, HEADER_STUDENT_UID);
        String name = readLiteralText(cellAt(row, columns.name()), formatter, HEADER_STUDENT_NAME);
        String daily = readScore(row, columns.daily(), formatter,
                headerOf(GradeComponentCodeDTO.DAILY));
        String midterm = readScore(row, columns.midterm(), formatter,
                headerOf(GradeComponentCodeDTO.MIDTERM));
        String experiment = readScore(row, columns.experiment(), formatter,
                headerOf(GradeComponentCodeDTO.EXPERIMENT));
        String finalterm = readScore(row, columns.finalterm(), formatter,
                headerOf(GradeComponentCodeDTO.FINALTERM));
        if (uid.isEmpty() && name.isEmpty() && isBlank(daily) && isBlank(midterm)
                && isBlank(experiment) && isBlank(finalterm)) {
            return null;
        }
        List<TeacherSpreadsheetRow.CellError> errors = new ArrayList<>();
        if (uid.isEmpty()) {
            errors.add(new TeacherSpreadsheetRow.CellError(TeacherSpreadsheetRow.FIELD_STUDENT_UID,
                    "", "学号不能为空"));
        }
        return new TeacherSpreadsheetRow(rowNumber, uid, name, daily, midterm, experiment,
                finalterm, errors);
    }

    /**
     * 读取一列我们当作字面文本使用的单元格（学号、姓名、四项成绩）：整列缺失返回 {@code null}，
     * 单元格缺失/空白返回空串，公式则拒绝**整份文件**。
     *
     * <p>公式与「损坏的工作簿」是同一个拒绝桶（设计第 9 节）：本服务不求值，`=SUM(B2:C2)` 一旦
     * 作为成绩读进来，就是一个服务端猜的值冒充教师填的分数。这里在解析阶段就停下，而不是把公式
     * 原文当成一项待修正的数据往后传——预览阶段必须能假定拿到的是文件里的字面内容。
     */
    private static String readLiteralText(Cell cell, DataFormatter formatter, String label) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            throw new IllegalArgumentException(label + "不能是公式");
        }
        return formatter.formatCellValue(cell).strip();
    }

    /** 成绩列：整列缺失（列下标为 -1）时该行没有值，与「列存在但单元格空白」区分开。 */
    private static String readScore(Row row, int column, DataFormatter formatter, String label) {
        return column < 0 ? null : readLiteralText(cellAt(row, column), formatter, label);
    }

    /** 同一学号在文件里出现多次：无法合并到同一行，逐行报错交给预览修正或排除。 */
    private static List<TeacherSpreadsheetRow> flagDuplicateStudentUids(List<TeacherSpreadsheetRow> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (TeacherSpreadsheetRow row : rows) {
            if (!row.studentUid().isEmpty()) {
                counts.merge(row.studentUid(), 1, Integer::sum);
            }
        }
        boolean duplicated = counts.values().stream().anyMatch(count -> count > 1);
        if (!duplicated) {
            return List.copyOf(rows);
        }
        List<TeacherSpreadsheetRow> flagged = new ArrayList<>(rows.size());
        for (TeacherSpreadsheetRow row : rows) {
            Integer count = counts.get(row.studentUid());
            if (count == null || count < 2) {
                flagged.add(row);
                continue;
            }
            List<TeacherSpreadsheetRow.CellError> errors =
                    new ArrayList<>(row.cellErrors());
            errors.add(new TeacherSpreadsheetRow.CellError(TeacherSpreadsheetRow.FIELD_STUDENT_UID,
                    row.studentUid(), "学号在文件中重复出现 " + count + " 次"));
            flagged.add(row.withCellErrors(errors));
        }
        return List.copyOf(flagged);
    }

    // ------------------------------------------------------------------ 写出

    /** 第二张说明表：教学班、每个组成的权重与启用状态、禁用项清单。 */
    private static void writeNotesSheet(Workbook workbook, TeacherGradeBookDTO gradeBook,
                                        CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet(SHEET_NOTES);
        Row offering = sheet.createRow(0);
        offering.createCell(0).setCellValue("教学班");
        offering.createCell(1).setCellValue(nullToEmpty(gradeBook.getOfferingId()));

        Row title = sheet.createRow(2);
        Cell componentHeader = title.createCell(0);
        componentHeader.setCellValue("成绩组成");
        componentHeader.setCellStyle(headerStyle);
        Cell weightHeader = title.createCell(1);
        weightHeader.setCellValue("权重");
        weightHeader.setCellStyle(headerStyle);
        Cell stateHeader = title.createCell(2);
        stateHeader.setCellValue("状态");
        stateHeader.setCellStyle(headerStyle);

        List<String> disabled = new ArrayList<>();
        int rowIndex = 3;
        for (GradeComponentDTO component : gradeBook.getScheme().getComponents()) {
            String name = componentName(component.getCode());
            if (!component.isEnabled()) {
                disabled.add(name);
            }
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(name);
            row.createCell(1).setCellValue(weightText(component.getWeightBasisPoints()));
            row.createCell(2).setCellValue(component.isEnabled() ? "已启用" : "已禁用");
        }
        Row disabledRow = sheet.createRow(rowIndex++);
        disabledRow.createCell(0).setCellValue("禁用项");
        disabledRow.createCell(1).setCellValue(disabled.isEmpty() ? "无" : String.join("、", disabled));

        Row note = sheet.createRow(rowIndex + 1);
        note.createCell(0).setCellValue("填写说明");
        note.createCell(1).setCellValue(
                "只有「学号」是必需的；姓名与四项成绩都可以留空，留空表示保留成绩表里已有的值，不会被写成 0。");
        sheet.setColumnWidth(0, 16 * 256);
        sheet.setColumnWidth(1, 42 * 256);
        sheet.setColumnWidth(2, 10 * 256);
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    /** 学号列专用文本格式：即便教师用 Excel 重新保存，前导零也不会被改成数字。 */
    private static CellStyle textStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("@"));
        return style;
    }

    private static void writeWorkbook(Workbook workbook, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            workbook.write(out);
        }
    }

    // ------------------------------------------------------------------ 常量与小工具

    private static Map<GradeComponentCodeDTO, String> componentHeaders() {
        Map<GradeComponentCodeDTO, String> headers = new LinkedHashMap<>();
        headers.put(GradeComponentCodeDTO.DAILY, "平时成绩");
        headers.put(GradeComponentCodeDTO.MIDTERM, "期中成绩");
        headers.put(GradeComponentCodeDTO.EXPERIMENT, "实验成绩");
        headers.put(GradeComponentCodeDTO.FINALTERM, "期末成绩");
        return Map.copyOf(headers);
    }

    /** 组成列名；未知代码（Gson 反序列化可能给出）不静默回退成某一固定组成。 */
    private static String headerOf(GradeComponentCodeDTO code) {
        return COMPONENT_HEADERS.getOrDefault(code, "成绩");
    }

    private static String componentName(GradeComponentCodeDTO code) {
        return COMPONENT_HEADERS.getOrDefault(code, "未知组成");
    }

    /** 权重按整数万分比保存，显示成百分比；不参与总评计算也不改写原值。 */
    private static String weightText(int weightBasisPoints) {
        return String.format(Locale.ROOT, "%.2f%%", weightBasisPoints / 100.0);
    }

    /** 与教学班详情页一致：ENROLLED 显示「正常」，DROPPED 显示「退课」，未知值原样输出。 */
    private static String enrollmentStatusText(String status) {
        if ("ENROLLED".equals(status)) {
            return ENROLLED_TEXT;
        }
        if ("DROPPED".equals(status)) {
            return DROPPED_TEXT;
        }
        return status == null || status.isBlank() ? BLANK_TEXT : status;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? BLANK_TEXT : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void requireTarget(Path target) {
        if (target == null) {
            throw new IllegalArgumentException("输出路径不能为空");
        }
    }

    private static Cell cellAt(Row row, int column) {
        return column < 0 ? null : row.getCell(column);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isEmpty();
    }

    private static int columnOrMissing(Map<String, Integer> found, String header) {
        return found.getOrDefault(header, -1);
    }

    /** 解析出的已知列下标；-1 表示该列在文件里不存在（成绩列与姓名列允许缺失）。 */
    private record Columns(int uid, int name, int daily, int midterm, int experiment, int finalterm) {
    }
}
