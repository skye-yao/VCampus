package service;

import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 教师成绩 Excel 的服务端「表格大脑」：解析上传的工作簿、生成空白成绩模板、导出完整名单。
 *
 * <p>本类只做文件与文本，不碰数据库、不签发票据、不写草稿：教学班归属与名单由调用方在**生成文件之前**
 * 通过既有归属校验入口取好（导出文件是另一份名单的快照，缺失这一步就会把别人的学生交给教师）。
 * 因此这里没有任何 uid/offeringId 参数，也就无法绕过上层的权限判定。
 *
 * <p>格式约定（设计第 9 节）：
 * <ul>
 *   <li>只读第一张工作表；表头必须是第一行，且至少包含「学号」列。缺少必需列、已知列重复属于
 *       **文件结构错误**，直接抛出并停止预览；未知的多余列被忽略（教师可能自加备注列）。</li>
 *   <li>学号列与成绩列都用 {@link DataFormatter} 取文本，绝不把学号当数字转换（前导零必须保留）；
 *       学号是公式则整份文件拒绝，成绩/姓名是公式则该行按错误行保留原文，交给预览修正或排除。</li>
 *   <li>成绩列可以整列缺失；空白单元格保留原文本，绝不转成 0。</li>
 *   <li>一个工作簿最多 {@link #MAX_ROWS} 个数据行：解析超限即拒绝，导出超限明确报错，绝不截断。</li>
 * </ul>
 */
public final class TeacherSpreadsheetService {

    /** 单个工作簿的数据行上限：解析、成绩模板与名单导出共用同一口径（设计第 9 节的 5000 行）。 */
    public static final int MAX_ROWS = 5000;

    /** 成绩模板第一张表的固定列，顺序即列序；校验时同一份名单也是「已知表头」的定义。 */
    static final List<String> TEMPLATE_HEADERS =
            List.of("学号", "姓名", "平时成绩", "期中成绩", "实验成绩", "期末成绩");

    private static final String HEADER_STUDENT_UID = "学号";
    private static final String HEADER_STUDENT_NAME = "姓名";
    private static final String SHEET_ENTRY = "成绩录入";
    private static final String SHEET_NOTES = "说明";
    private static final String SHEET_ROSTER = "学生名单";
    private static final List<String> ROSTER_HEADERS =
            List.of("学号", "姓名", "专业", "状态", "选课时间", "退课时间");
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
     * @throws IllegalArgumentException 文件不存在、不是有效的 .xlsx、加密、损坏、表头缺少学号列、
     *         已知表头重复、学号列出现公式、或者数据行超过 {@link #MAX_ROWS}
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
        List<TeacherSpreadsheetRow.CellError> errors = new ArrayList<>();
        String uid = readStudentUid(cellAt(row, columns.uid()), formatter);
        String name = columns.name() < 0 ? "" : readCheckedText(cellAt(row, columns.name()),
                formatter, TeacherSpreadsheetRow.FIELD_STUDENT_NAME, HEADER_STUDENT_NAME, errors);
        String daily = readScore(row, columns.daily(), formatter,
                TeacherSpreadsheetRow.FIELD_DAILY_SCORE, headerOf(GradeComponentCodeDTO.DAILY), errors);
        String midterm = readScore(row, columns.midterm(), formatter,
                TeacherSpreadsheetRow.FIELD_MIDTERM_SCORE, headerOf(GradeComponentCodeDTO.MIDTERM), errors);
        String experiment = readScore(row, columns.experiment(), formatter,
                TeacherSpreadsheetRow.FIELD_EXPERIMENT_SCORE,
                headerOf(GradeComponentCodeDTO.EXPERIMENT), errors);
        String finalterm = readScore(row, columns.finalterm(), formatter,
                TeacherSpreadsheetRow.FIELD_FINALTERM_SCORE,
                headerOf(GradeComponentCodeDTO.FINALTERM), errors);
        if (uid.isEmpty() && name.isEmpty() && isBlank(daily) && isBlank(midterm)
                && isBlank(experiment) && isBlank(finalterm)) {
            return null;
        }
        if (uid.isEmpty()) {
            errors.add(new TeacherSpreadsheetRow.CellError(TeacherSpreadsheetRow.FIELD_STUDENT_UID,
                    "", "学号不能为空"));
        }
        return new TeacherSpreadsheetRow(rowNumber, uid, name, daily, midterm, experiment,
                finalterm, errors);
    }

    /**
     * 学号单元格：只接受文本/数字等普通单元格，原文由 {@link DataFormatter} 给出（前导零保留）。
     * 公式一律拒绝整份文件：学号是行的身份，靠公式算出来的学号说明文件结构本身就是错的。
     */
    private static String readStudentUid(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            throw new IllegalArgumentException("学号不能是公式");
        }
        return formatter.formatCellValue(cell).strip();
    }

    /**
     * 成绩单元格：整列缺失返回 {@code null}；单元格空白返回空串；公式保留原文并记错误行，
     * 让预览显示问题单元格而不是在解析时把它吞掉。
     */
    private static String readScore(Row row, int column, DataFormatter formatter, String field,
                                    String label, List<TeacherSpreadsheetRow.CellError> errors) {
        if (column < 0) {
            return null;
        }
        return readCheckedText(cellAt(row, column), formatter, field, label, errors);
    }

    private static String readCheckedText(Cell cell, DataFormatter formatter, String field,
                                          String label, List<TeacherSpreadsheetRow.CellError> errors) {
        if (cell == null) {
            return "";
        }
        String text = formatter.formatCellValue(cell).strip();
        if (cell.getCellType() == CellType.FORMULA) {
            errors.add(new TeacherSpreadsheetRow.CellError(field, text, label + "不能是公式"));
        }
        return text;
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
