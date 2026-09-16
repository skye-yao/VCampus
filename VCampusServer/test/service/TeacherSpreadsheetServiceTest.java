package service;

import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherFileTicketDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import handler.TeacherCourseHandler;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.SessionManager;
import session.UserSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 教师成绩 Excel 的服务端行为矩阵（本套件无数据库：读数、写数都不碰 MySQL）。
 *
 * <p>三条主线，各自钉住真实文件而不是桩：
 * <ol>
 *   <li><b>解析</b>上传的工作簿：中文姓名、前导零学号（{@code DataFormatter} 文本而不是数字转换）、
 *       缺列、空白、0/100/小数、重复学号、公式（拒绝整份文件）、错误表头、损坏文件、超限压缩内容
 *       与超过 5000 行。</li>
 *   <li><b>生成</b>成绩模板、名单导出与成绩导出：用 POI 重新打开生成的文件，核对表头文字、
 *       文本格式/加粗样式与行数；模板第二张说明表写教学班、权重与禁用项；成绩导出核对列序、
 *       未填写写 0，以及总评/绩点是照抄成绩行的值（页面显示「—」的行就是 0/0）。</li>
 *   <li><b>下载票据</b>：Handler 的三个下载动作只回票据不回文件字节，且**先校验归属再生成文件**——
 *       归属被拒时既没有票据，也没有任何文件落到临时目录（这是 Task 1 评审带过来的要求）。</li>
 * </ol>
 *
 * <p>名单导出的 5000 行上限在两侧都有覆盖：{@link TeacherSpreadsheetService#writeRoster} 超限报错在
 * 这里（真实 5001 行文件），SQL 层的「一次取全部结果而不是当前页」需要真实库，归 Task 5 的端到端。
 */
public final class TeacherSpreadsheetServiceTest {
    private static final String TEACHER = "teacher-excel";
    private static final String OFFERING_ID = "9007199254740993";
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    /** 模板第一张表的固定六列，逐字钉住（设计第 9 节）——故意不引用生产常量。 */
    private static final List<String> TEMPLATE_HEADERS =
            List.of("学号", "姓名", "平时成绩", "期中成绩", "实验成绩", "期末成绩");
    private static final List<String> ROSTER_HEADERS =
            List.of("学号", "姓名", "专业", "状态", "选课时间", "退课时间");
    /** 成绩导出的九列，逐字钉住——同样故意不引用生产常量。 */
    private static final List<String> GRADE_HEADERS =
            List.of("学号", "姓名", "专业", "平时成绩", "期中成绩", "实验成绩", "期末成绩", "总评", "绩点");
    /** 单个填充压缩项的上限：留出余量，让超限用例命中「解压后总量」而不是「单个压缩项」规则。 */
    private static final long PADDING_ENTRY_BYTES = 20L * 1024 * 1024;

    private static Path tempDirectory;

    private TeacherSpreadsheetServiceTest() {
    }

    public static void main(String[] args) throws IOException {
        tempDirectory = Files.createTempDirectory("teacher-spreadsheet-test-");
        SessionManager sessions = SessionManager.getInstance();
        UserSession teacher = sessions.createSession(TEACHER, "教师");
        try {
            TeacherSpreadsheetService service = new TeacherSpreadsheetService();
            verifyChineseNamesAndLeadingZeroUids(service);
            verifyMissingColumnsAndBlanks(service);
            verifyZeroHundredAndDecimals(service);
            verifyHeaderProblemsStopThePreview(service);
            verifyFormulaCellsStopTheParse(service);
            verifyDuplicateStudentUids(service);
            verifyOnlyTheFirstSheetIsRead(service);
            verifyCorruptFilesAreRejected(service);
            verifyOversizedArchivesAreRejected(service);
            verifyRowLimit(service);
            verifyRosterMatching(service);
            verifyGradeTemplate(service);
            verifyRosterExport(service);
            verifyGradeExport(service);
            verifyHandlerDownloadTickets(teacher);
            verifyOwnershipBeforeDownloadTicket(teacher);
        } finally {
            sessions.removeSession(teacher.getToken());
            deleteRecursively(tempDirectory);
        }
        System.out.println("Teacher spreadsheet service test passed.");
    }

    /** 中文姓名与前导零学号：学号写在文本单元格里，读回来的仍是 "000123"，不是 123。 */
    private static void verifyChineseNamesAndLeadingZeroUids(TeacherSpreadsheetService service) {
        Path file = workbook("chinese.xlsx", TEMPLATE_HEADERS,
                sheet -> row(sheet, 1, "000123", "张三", "88.5", "90", "77", "100"),
                sheet -> row(sheet, 2, "000045", "李四", null, null, null, null));
        List<TeacherSpreadsheetRow> rows = service.parse(file);
        require(rows.size() == 2, "both data rows must be parsed, saw " + rows.size());
        TeacherSpreadsheetRow first = rows.get(0);
        require("000123".equals(first.studentUid()),
                "a text student uid must keep its leading zeros, saw " + first.studentUid());
        require("张三".equals(first.studentName()), "the Chinese name must survive the round trip");
        require("88.5".equals(first.rawDailyScore()), "a decimal must stay a decimal text");
        require("100".equals(first.rawFinaltermScore()), "100 must stay 100");
        require(first.rowNumber() == 2 && rows.get(1).rowNumber() == 3,
                "row numbers must be the ones Excel shows (header is row 1)");
        require(!first.hasCellErrors(), "a complete row must be clean: " + first.cellErrors());
        require("李四".equals(rows.get(1).studentName()),
                "a row with blank scores must still be parsed by name");
    }

    /** 缺列与空白：整列缺失给出 null，空白单元格给出空串，两者都不是 0。 */
    private static void verifyMissingColumnsAndBlanks(TeacherSpreadsheetService service) {
        Path file = workbook("missing-columns.xlsx",
                List.of("学号", "成绩备注", "平时成绩", "姓名"),
                sheet -> row(sheet, 1, "000123", "看不到的备注列", null, "张三"),
                sheet -> row(sheet, 2, "000124", "x", "0", null));
        List<TeacherSpreadsheetRow> rows = service.parse(file);
        require(rows.size() == 2, "unknown extra columns must not break parsing");
        TeacherSpreadsheetRow first = rows.get(0);
        require(first.rawMidtermScore() == null && first.rawExperimentScore() == null
                        && first.rawFinaltermScore() == null,
                "a missing score column must be null, not an empty text or a zero");
        require("".equals(first.rawDailyScore()),
                "a blank score cell must stay an empty text, saw " + first.rawDailyScore());
        require("张三".equals(first.studentName()), "the name column may sit anywhere in the header");
        require(!first.hasCellErrors(), "missing optional columns are not a row error");
        require("0".equals(rows.get(1).rawDailyScore()),
                "a real zero must stay the text zero, not be confused with a blank");
        require("".equals(rows.get(1).studentName()), "a blank name is an empty text");
    }

    /** 0/100/小数：原文保留，服务端不在这里做数值转换（非法值由预览阶段判定）。 */
    private static void verifyZeroHundredAndDecimals(TeacherSpreadsheetService service) {
        Path file = workbook("numbers.xlsx", TEMPLATE_HEADERS,
                sheet -> row(sheet, 1, "000123", "张三", 0, 100, 88.5, "100.00"));
        TeacherSpreadsheetRow row = service.parse(file).get(0);
        require("0".equals(row.rawDailyScore()), "a numeric zero cell must read as 0");
        require("100".equals(row.rawMidtermScore()), "a numeric 100 cell must read as 100");
        require("88.5".equals(row.rawExperimentScore()),
                "a numeric decimal must read as 88.5, saw " + row.rawExperimentScore());
        require("100.00".equals(row.rawFinaltermScore()),
                "a text cell must keep its own literal text");
    }

    /** 错误表头属于文件结构错误：缺学号列、已知列重复都停止预览。 */
    private static void verifyHeaderProblemsStopThePreview(TeacherSpreadsheetService service) {
        Path noUid = workbook("no-uid.xlsx", List.of("姓名", "平时成绩", "期中成绩"),
                sheet -> row(sheet, 1, "张三", "88.5", "90"));
        expectInvalid("学号", () -> service.parse(noUid));

        Path duplicateUid = workbook("duplicate-uid.xlsx",
                List.of("学号", "学号", "姓名", "平时成绩"),
                sheet -> row(sheet, 1, "000123", "000123", "张三", "88.5"));
        expectInvalid("重复", () -> service.parse(duplicateUid));

        Path duplicateScore = workbook("duplicate-score.xlsx",
                List.of("学号", "姓名", "平时成绩", "平时成绩", "期末成绩"),
                sheet -> row(sheet, 1, "000123", "张三", "88.5", "90", "100"));
        expectInvalid("重复", () -> service.parse(duplicateScore));

        Path emptyHeader = workbook("empty-header.xlsx", List.of("", ""),
                sheet -> row(sheet, 1, "000123", "张三"));
        expectInvalid("学号", () -> service.parse(emptyHeader));
    }

    /**
     * 公式：学号、姓名与四项成绩都是「按字面读」的列，任何一处出现公式都拒绝**整份文件**——与
     * 「损坏的工作簿」同一个拒绝桶（设计第 9 节）。解析阶段就停下，公式原文不会以任何形式进入预览，
     * 因此预览可以假定拿到的是文件里的字面内容。
     */
    private static void verifyFormulaCellsStopTheParse(TeacherSpreadsheetService service) {
        Path formulaUid = workbook("formula-uid.xlsx", TEMPLATE_HEADERS,
                sheet -> row(sheet, 1, new Formula("CONCATENATE(\"000\",\"123\")"), "张三", "88.5", null, null, null));
        expectInvalid("学号不能是公式", () -> service.parse(formulaUid));

        Path formulaScore = workbook("formula-score.xlsx", TEMPLATE_HEADERS,
                sheet -> row(sheet, 1, "000123", "张三", "88.5", new Formula("SUM(B1:C1)"), null, null),
                sheet -> row(sheet, 2, "000124", "李四", "70", "80", "90", "100"));
        expectInvalid("期中成绩不能是公式", () -> service.parse(formulaScore));

        Path formulaName = workbook("formula-name.xlsx", TEMPLATE_HEADERS,
                sheet -> row(sheet, 1, "000123", new Formula("VLOOKUP(A1,名单!A:B,2)"), "88.5", null, null, null));
        expectInvalid("姓名不能是公式", () -> service.parse(formulaName));
    }

    /** 超限压缩内容：文件本身与解压后的体量都在打开工作簿之前由本仓库显式判定。 */
    private static void verifyOversizedArchivesAreRejected(TeacherSpreadsheetService service) {
        // 文件本身超过 5 MiB：随机字节不可压缩，声明体积（约 6 MiB）仍在解压上限之内，
        // 所以只有文件大小这一条会命中。
        Path tooLarge = archiveWithPadding("too-large.xlsx", 6L * 1024 * 1024, true);
        require(fileSize(tooLarge) > TeacherFileTicketService.MAX_FILE_BYTES,
                "the fixture must really exceed the file limit, saw " + fileSize(tooLarge));
        expectInvalid("文件上限", () -> service.parse(tooLarge));

        // 解压后超过 64 MiB：填充物全是零字节，压缩后很小，文件大小检查不会命中。
        Path tooInflated = archiveWithPadding("too-inflated.xlsx", 70L * 1024 * 1024, false);
        require(fileSize(tooInflated) < TeacherFileTicketService.MAX_FILE_BYTES,
                "the fixture must stay under the file limit, saw " + fileSize(tooInflated));
        expectInvalid("解压后超过", () -> service.parse(tooInflated));

        // 对照：POI 用与服务端相同的只读方式打开同一个包毫无问题——拒绝它的是本层的显式上限，
        // 而不是「文件坏了」。
        try (Workbook ignored = WorkbookFactory.create(tooInflated.toFile(), null, true)) {
            require(ignored.getNumberOfSheets() == 1,
                    "the rejected fixture must still be a readable workbook");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** 重复学号：按行报错（两行都标），不能静默只留一行。 */
    private static void verifyDuplicateStudentUids(TeacherSpreadsheetService service) {
        Path file = workbook("duplicates.xlsx", TEMPLATE_HEADERS,
                sheet -> row(sheet, 1, "000123", "张三", "88.5", "90", "77", "100"),
                sheet -> row(sheet, 2, "000124", "李四", "70", "80", "90", "100"),
                sheet -> row(sheet, 3, "000123", "张三", "60", null, null, null));
        List<TeacherSpreadsheetRow> rows = service.parse(file);
        require(rows.size() == 3, "duplicate rows must all be reported, not merged away");
        require(hasError(rows.get(0), TeacherSpreadsheetRow.FIELD_STUDENT_UID, "重复"),
                "the first occurrence must be flagged too: " + rows.get(0).cellErrors());
        require(hasError(rows.get(2), TeacherSpreadsheetRow.FIELD_STUDENT_UID, "重复"),
                "the second occurrence must be flagged: " + rows.get(2).cellErrors());
        require(!rows.get(1).hasCellErrors(), "a unique student id must stay clean");
    }

    /** 只读第一张工作表：第二张表里的同名数据不能混进来。 */
    private static void verifyOnlyTheFirstSheetIsRead(TeacherSpreadsheetService service) {
        Path file = tempDirectory.resolve("first-sheet-only.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet first = workbook.createSheet("成绩录入");
            header(first, TEMPLATE_HEADERS);
            row(first, 1, "000123", "张三", "88.5", null, null, null);
            Sheet decoy = workbook.createSheet("另一个班");
            header(decoy, TEMPLATE_HEADERS);
            for (int i = 1; i <= 5; i++) {
                row(decoy, i, "0009" + i, "其他学生" + i, "60", "60", "60", "60");
            }
            write(workbook, file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        List<TeacherSpreadsheetRow> rows = service.parse(file);
        require(rows.size() == 1 && "000123".equals(rows.get(0).studentUid()),
                "only the first sheet may be read, saw " + rows.size() + " rows");
    }

    /** 损坏/不是工作簿/加密的文件都必须以可显示的说明被拒绝，而不是抛 POI 的异常。 */
    private static void verifyCorruptFilesAreRejected(TeacherSpreadsheetService service) {
        Path text = tempDirectory.resolve("not-a-workbook.xlsx");
        try {
            Files.writeString(text, "这不是一个 Excel 文件");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        expectInvalid("工作簿", () -> service.parse(text));

        Path truncated = tempDirectory.resolve("truncated.xlsx");
        try {
            byte[] complete = Files.readAllBytes(workbook("complete.xlsx", TEMPLATE_HEADERS,
                    sheet -> row(sheet, 1, "000123", "张三", "88.5", "90", "77", "100")));
            Files.write(truncated, Arrays.copyOf(complete, complete.length / 2));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        expectInvalid("工作簿", () -> service.parse(truncated));

        expectInvalid("不存在", () -> service.parse(tempDirectory.resolve("missing.xlsx")));
    }

    /** 行数上限：5000 行正好可以，5001 行明确报错而不是截断。 */
    private static void verifyRowLimit(TeacherSpreadsheetService service) {
        Path atLimit = workbook("at-limit.xlsx", TEMPLATE_HEADERS,
                sheet -> fill(sheet, TeacherSpreadsheetService.MAX_ROWS));
        require(service.parse(atLimit).size() == TeacherSpreadsheetService.MAX_ROWS,
                "exactly 5000 data rows must be accepted");

        Path overLimit = workbook("over-limit.xlsx", TEMPLATE_HEADERS,
                sheet -> fill(sheet, TeacherSpreadsheetService.MAX_ROWS + 1));
        expectInvalid("5000", () -> service.parse(overLimit));
    }

    /** 名册核对：学号必须在名单里，提供的姓名必须与名单一致；空白姓名不参与比对。 */
    private static void verifyRosterMatching(TeacherSpreadsheetService service) {
        Path file = workbook("roster-check.xlsx", TEMPLATE_HEADERS,
                sheet -> row(sheet, 1, "000123", "张三", "88.5", null, null, null),
                sheet -> row(sheet, 2, "000124", "王五", "70", null, null, null),
                sheet -> row(sheet, 3, "009999", "赵六", "60", null, null, null),
                sheet -> row(sheet, 4, "000125", null, "50", null, null, null));
        List<TeacherRosterRowDTO> roster = List.of(
                roster("1001", "000123", "张三", "ENROLLED"),
                roster("1002", "000124", "李四", "ENROLLED"),
                roster("1003", "000125", "王五", "DROPPED"));

        List<TeacherSpreadsheetRow> rows = service.parse(file, roster);
        require(!rows.get(0).hasCellErrors(),
                "a matching student and name must stay clean: " + rows.get(0).cellErrors());
        require(hasError(rows.get(1), TeacherSpreadsheetRow.FIELD_STUDENT_NAME, "姓名"),
                "a provided name that differs from the roster must be flagged: "
                        + rows.get(1).cellErrors());
        require(hasError(rows.get(2), TeacherSpreadsheetRow.FIELD_STUDENT_UID, "不在本教学班"),
                "an unknown student id must be flagged: " + rows.get(2).cellErrors());
        require(!rows.get(3).hasCellErrors(),
                "an empty name means the file did not provide one: " + rows.get(3).cellErrors());
        require(rows.get(2).cellErrors().get(0).rawValue().equals("009999"),
                "the error must carry the offending raw value");
    }

    /** 成绩模板：固定六列、学号文本单元格、表头加粗、第二张说明表写教学班/权重/禁用项。 */
    private static void verifyGradeTemplate(TeacherSpreadsheetService service) {
        Path target = tempDirectory.resolve("template.xlsx");
        service.writeGradeTemplate(target, gradeBook(2));

        try (Workbook workbook = WorkbookFactory.create(target.toFile())) {
            require(workbook.getNumberOfSheets() == 2,
                    "the template must have an entry sheet and a notes sheet");
            Sheet entry = workbook.getSheetAt(0);
            require("成绩录入".equals(entry.getSheetName()),
                    "the entry sheet must come first, saw " + entry.getSheetName());
            Row header = entry.getRow(0);
            for (int column = 0; column < TEMPLATE_HEADERS.size(); column++) {
                require(TEMPLATE_HEADERS.get(column).equals(text(header.getCell(column))),
                        "column " + column + " must be " + TEMPLATE_HEADERS.get(column)
                                + ", saw " + text(header.getCell(column)));
            }
            require(((XSSFCellStyle) header.getCell(0).getCellStyle()).getFont().getBold(),
                    "the header must be bold");
            require(entry.getLastRowNum() == 2, "two students must produce two data rows, saw "
                    + entry.getLastRowNum());
            Cell uid = entry.getRow(1).getCell(0);
            require("000000".equals(text(uid)),
                    "the template must carry the student uids with their leading zeros, saw "
                            + text(uid));
            require(uid.getCellType() == CellType.STRING,
                    "the student uid must be a text cell, saw " + uid.getCellType());
            require("@".equals(uid.getCellStyle().getDataFormatString()),
                    "the student uid column must use the text format, saw "
                            + uid.getCellStyle().getDataFormatString());
            for (int column = 2; column <= 5; column++) {
                Cell score = entry.getRow(1).getCell(column);
                require(score == null || score.getCellType() == CellType.BLANK,
                        "score cells must be left blank (blank means keep the stored value)");
            }

            Sheet notes = workbook.getSheetAt(1);
            require("说明".equals(notes.getSheetName()), "the notes sheet must be second");
            require(OFFERING_ID.equals(notesRowValue(notes, "教学班")),
                    "the notes must name the offering");
            require("30.00%".equals(notesRowValue(notes, "平时成绩")),
                    "the notes must print the weight of 平时成绩, saw "
                            + notesRowValue(notes, "平时成绩"));
            require("已禁用".equals(notesStatus(notes, "实验成绩")),
                    "a disabled component must be marked as disabled");
            require("实验成绩".equals(notesRowValue(notes, "禁用项")),
                    "the disabled list must name the disabled component");
            require("50.00%".equals(notesRowValue(notes, "期末成绩")),
                    "every weight must be printed as a percentage");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }

        expectInvalid("5000", () -> service.writeGradeTemplate(
                tempDirectory.resolve("template-too-big.xlsx"), gradeBook(5001)));
        require(!Files.exists(tempDirectory.resolve("template-too-big.xlsx")),
                "a rejected template must not leave a file behind");
    }

    /** 名单导出：六列表头、状态用「正常/退课」、空值用破折号、超限报错不截断。 */
    private static void verifyRosterExport(TeacherSpreadsheetService service) {
        Path target = tempDirectory.resolve("roster.xlsx");
        service.writeRoster(target, List.of(
                roster("1001", "000123", "张三", "ENROLLED"),
                new TeacherRosterRowDTO("1002", "000124", "李四", null, "DROPPED",
                        "2026-09-01T00:00:00Z", "2026-09-10T00:00:00Z")));

        try (Workbook workbook = WorkbookFactory.create(target.toFile())) {
            require(workbook.getNumberOfSheets() == 1, "the export must contain a single sheet");
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            for (int column = 0; column < ROSTER_HEADERS.size(); column++) {
                require(ROSTER_HEADERS.get(column).equals(text(header.getCell(column))),
                        "roster column " + column + " must be " + ROSTER_HEADERS.get(column));
            }
            require(sheet.getLastRowNum() == 2, "both roster rows must be exported, saw "
                    + sheet.getLastRowNum());
            Row enrolled = sheet.getRow(1);
            require("000123".equals(text(enrolled.getCell(0))) && "张三".equals(text(enrolled.getCell(1))),
                    "the exported row must carry uid and name");
            require("计算机科学与技术".equals(text(enrolled.getCell(2))), "the major must be exported");
            require("正常".equals(text(enrolled.getCell(3))),
                    "ENROLLED must read as 正常, saw " + text(enrolled.getCell(3)));
            require("—".equals(text(enrolled.getCell(5))),
                    "a student who has not dropped must show a placeholder, not a timestamp");
            require(enrolled.getCell(0).getCellType() == CellType.STRING,
                    "the exported uid must be text so leading zeros survive");
            Row dropped = sheet.getRow(2);
            require("退课".equals(text(dropped.getCell(3))) && "—".equals(text(dropped.getCell(2))),
                    "a dropped student must be labelled 退课 and keep its empty major as a placeholder");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }

        List<TeacherRosterRowDTO> oversized = new ArrayList<>();
        for (int i = 0; i <= TeacherSpreadsheetService.MAX_ROWS; i++) {
            oversized.add(roster(String.valueOf(2000 + i), "0001" + i, "学生" + i, "ENROLLED"));
        }
        expectInvalid("5000", () -> service.writeRoster(
                tempDirectory.resolve("roster-too-big.xlsx"), oversized));
    }

    /**
     * 成绩导出：九列的列序逐字钉住，未填写的成绩写 0；总评/绩点**照抄**成绩行自己的值（与页面同一份
     * 事实，本类不重算），因此页面显示「—」的行在文件里也是 0/0，禁用项缺分不算缺；专业来自名单、
     * 不在名单里的行留占位符，超限报错不截断。
     */
    private static void verifyGradeExport(TeacherSpreadsheetService service) {
        Path target = tempDirectory.resolve("grades.xlsx");
        service.writeGrades(target, scoredGradeBook(), List.of(
                roster("1001", "000123", "张三", "ENROLLED"),
                // 李四没有专业：专业列必须留占位符，而不是空白
                new TeacherRosterRowDTO("1002", "000124", "李四", null, "ENROLLED",
                        "2026-09-01T00:00:00Z", null),
                // 名单里有、成绩表里没有的学生（退课历史）不得出现在成绩导出里
                roster("1099", "000199", "退课生", "DROPPED")));

        try (Workbook workbook = WorkbookFactory.create(target.toFile())) {
            require(workbook.getNumberOfSheets() == 1, "the grade export must contain a single sheet");
            Sheet sheet = workbook.getSheetAt(0);
            require("学生成绩".equals(sheet.getSheetName()),
                    "the grade sheet must be named 学生成绩, saw " + sheet.getSheetName());
            Row header = sheet.getRow(0);
            for (int column = 0; column < GRADE_HEADERS.size(); column++) {
                require(GRADE_HEADERS.get(column).equals(text(header.getCell(column))),
                        "grade column " + column + " must be " + GRADE_HEADERS.get(column)
                                + ", saw " + text(header.getCell(column)));
            }
            require(sheet.getLastRowNum() == 3,
                    "only the grade book's rows may be exported, saw " + sheet.getLastRowNum());

            Cell uid = sheet.getRow(1).getCell(0);
            require("000123".equals(text(uid)) && uid.getCellType() == CellType.STRING,
                    "the student uid must keep its leading zeros as text, saw " + text(uid)
                            + " / " + uid.getCellType());
            Row filled = sheet.getRow(1);
            require("张三".equals(text(filled.getCell(1)))
                            && "计算机科学与技术".equals(text(filled.getCell(2))),
                    "the major must come from the roster");
            for (int column = 3; column <= 8; column++) {
                require(filled.getCell(column).getCellType() == CellType.NUMERIC,
                        "column " + column + " must be a number, saw "
                                + filled.getCell(column).getCellType());
            }
            require(score(filled, 3) == 90d && score(filled, 4) == 80d && score(filled, 5) == 70d
                            && score(filled, 6) == 60d,
                    "the four components must be the saved draft scores, saw " + scoresOf(filled));
            require(score(filled, 7) == 75d && score(filled, 8) == 2.5d,
                    "a complete row must carry the same 总评/绩点 the page shows, saw "
                            + score(filled, 7) + "/" + score(filled, 8));

            Row missing = sheet.getRow(2);
            require(score(missing, 3) == 100d && score(missing, 4) == 100d
                            && score(missing, 5) == 0d && score(missing, 6) == 100d,
                    "an unfilled grade must be written as 0, saw " + scoresOf(missing));
            require(score(missing, 7) == 0d && score(missing, 8) == 0d,
                    "a row the page shows as 「—」 (缺一个启用项) must export 0/0, not a total"
                            + " computed from the zero-filled cells, saw " + scoresOf(missing));
            require("—".equals(text(missing.getCell(2))),
                    "a student without a major must show the placeholder, saw "
                            + text(missing.getCell(2)));

            Row empty = sheet.getRow(3);
            for (int column = 3; column <= 8; column++) {
                require(score(empty, column) == 0d,
                        "a row with no grades must be all zeros, saw " + scoresOf(empty));
            }
            require("—".equals(text(empty.getCell(2))),
                    "a student outside the roster must keep the placeholder major");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }

        List<TeacherGradeRowDTO> oversized = new ArrayList<>();
        for (int i = 0; i <= TeacherSpreadsheetService.MAX_ROWS; i++) {
            oversized.add(new TeacherGradeRowDTO(String.valueOf(3000 + i), "0001" + i, "学生" + i,
                    null, null, null, false, List.of()));
        }
        // 权重还没配齐（草稿方案合计不到 10000）：服务端算不出总评，行里因此是 null。四项成绩照常
        // 导出，总评与绩点写 0——页面显示占位符的行，文件里同样是 0。
        Path unconfigured = tempDirectory.resolve("grades-without-weights.xlsx");
        service.writeGrades(unconfigured, gradeBookWith(scoredScheme(0, 0, 0, 0),
                new TeacherGradeRowDTO("1001", "000123", "张三",
                        new GradeScoresDTO(new BigDecimal("90"), new BigDecimal("80"),
                                new BigDecimal("70"), new BigDecimal("60")),
                        null, null, false, List.of())), List.of(
                roster("1001", "000123", "张三", "ENROLLED")));
        try (Workbook workbook = WorkbookFactory.create(unconfigured.toFile())) {
            Row row = workbook.getSheetAt(0).getRow(1);
            require(score(row, 3) == 90d && score(row, 4) == 80d,
                    "the saved scores must be exported even without a usable scheme, saw "
                            + scoresOf(row));
            require(score(row, 7) == 0d && score(row, 8) == 0d,
                    "without a configured weight the 总评/绩点 must be 0, saw " + scoresOf(row));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }

        // 禁用的组成不是“缺分”：实验关掉之后，其余启用项填齐的行照样有总评/绩点，照原样导出；
        // 实验那一格仍然写 0（四个成绩列都在，未填写就是 0）。
        Path disabledComponent = tempDirectory.resolve("grades-with-disabled-component.xlsx");
        service.writeGrades(disabledComponent, gradeBookWith(disabledExperimentScheme(),
                new TeacherGradeRowDTO("1001", "000123", "张三",
                        new GradeScoresDTO(new BigDecimal("90"), new BigDecimal("80"), null,
                                new BigDecimal("100")),
                        new BigDecimal("93.00"), new BigDecimal("4.5"), true, List.of())),
                List.of(roster("1001", "000123", "张三", "ENROLLED")));
        try (Workbook workbook = WorkbookFactory.create(disabledComponent.toFile())) {
            Row row = workbook.getSheetAt(0).getRow(1);
            require(score(row, 3) == 90d && score(row, 4) == 80d && score(row, 5) == 0d
                            && score(row, 6) == 100d,
                    "a disabled component must still export its (empty) column as 0, saw "
                            + scoresOf(row));
            require(score(row, 7) == 93d && score(row, 8) == 4.5d,
                    "a row that is complete over the ENABLED components must keep its real"
                            + " 总评/绩点, saw " + scoresOf(row));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }

        expectInvalid("5000", () -> service.writeGrades(
                tempDirectory.resolve("grades-too-big.xlsx"),
                new TeacherGradeBookDTO(OFFERING_ID, 4, DIGEST, "DRAFT", scoredScheme(),
                        oversized, null, null, true, null, false),
                List.of()));
        require(!Files.exists(tempDirectory.resolve("grades-too-big.xlsx")),
                "a rejected grade export must not leave a file behind");
    }

    /** Handler 的下载动作：响应只有一张 DOWNLOAD 票据，真正的表格已经生成在服务端临时目录里。 */
    private static void verifyHandlerDownloadTickets(UserSession teacher) {
        TeacherFileTicketService tickets = new TeacherFileTicketService(0);
        try {
            TeacherCourseHandler handler = new TeacherCourseHandler(
                    new RecordingQueryService(List.of()), null, new RecordingGradeService(gradeBook(1)),
                    tickets);

            Message template = handler.handle(request(TeacherCourseActions.REQUEST_GRADE_TEMPLATE,
                    teacher.getToken(), "offeringId", OFFERING_ID));
            require(template.getCode() == MessageCode.SUCCESS, "the template request must succeed: "
                    + template.getMessage());
            require(template.getData() != null && template.getData().size() == 1
                            && template.getData().containsKey("ticket"),
                    "the download must expose exactly the ticket key, saw " + template.getData());
            TeacherFileTicketDTO templateTicket = (TeacherFileTicketDTO) template.getData().get("ticket");
            require(TeacherFileTicketDTO.DIRECTION_DOWNLOAD.equals(templateTicket.getDirection()),
                    "the template must be a download ticket, saw " + templateTicket.getDirection());
            require(templateTicket.getByteLength() > 0
                            && templateTicket.getByteLength() <= TeacherFileTicketService.MAX_FILE_BYTES,
                    "the ticket must describe the generated file size");
            require(tickets.getTempDirectory().toFile().listFiles().length == 1,
                    "exactly one generated file must be waiting for the download");
            require(headerOf(fileAwaitingDownload(tickets.getTempDirectory(), TEMPLATE_HEADERS))
                            .equals(TEMPLATE_HEADERS),
                    "the generated file must be the grade template");

            RecordingQueryService queries = new RecordingQueryService(List.of(
                    roster("1001", "000123", "张三", "ENROLLED")));
            TeacherCourseHandler exportHandler = new TeacherCourseHandler(queries, null,
                    new RecordingGradeService(gradeBook(1)), tickets);
            Message export = exportHandler.handle(request(TeacherCourseActions.REQUEST_ROSTER_EXPORT,
                    teacher.getToken(), "offeringId", OFFERING_ID, "query", "张", "enrollmentStatus", 2));
            require(export.getCode() == MessageCode.SUCCESS, "the export must succeed: "
                    + export.getMessage());
            TeacherFileTicketDTO exportTicket = (TeacherFileTicketDTO) export.getData().get("ticket");
            require(TeacherFileTicketDTO.DIRECTION_DOWNLOAD.equals(exportTicket.getDirection()),
                    "the export must be a download ticket");
            require(TEACHER.equals(queries.lastUid) && OFFERING_ID.equals(queries.lastOfferingId),
                    "the teacher identity must come from the session, not the request body");
            require("张".equals(queries.lastQuery) && Integer.valueOf(2).equals(queries.lastStatus),
                    "the export must carry the same filters as the list, saw "
                            + queries.lastQuery + "/" + queries.lastStatus);
            require(headerOf(fileAwaitingDownload(tickets.getTempDirectory(), ROSTER_HEADERS))
                            .equals(ROSTER_HEADERS),
                    "the generated file must be the roster export");

            // 成绩导出：行与总评口径来自成绩表，专业来自（正常修读的）名单；它是另一个动作，
            // 名单导出那一步的行为一个字节都不受影响。
            RecordingQueryService gradeQueries = new RecordingQueryService(List.of(
                    roster("9000", "000000", "学生0", "ENROLLED")));
            TeacherCourseHandler gradeHandler = new TeacherCourseHandler(gradeQueries, null,
                    new RecordingGradeService(gradeBook(1)), tickets);
            Message grades = gradeHandler.handle(request(TeacherCourseActions.REQUEST_GRADE_EXPORT,
                    teacher.getToken(), "offeringId", OFFERING_ID));
            require(grades.getCode() == MessageCode.SUCCESS,
                    "the grade export must succeed: " + grades.getMessage());
            require(grades.getData() != null && grades.getData().size() == 1
                            && grades.getData().containsKey("ticket"),
                    "the grade export must expose exactly the ticket key, saw " + grades.getData());
            TeacherFileTicketDTO gradeTicket = (TeacherFileTicketDTO) grades.getData().get("ticket");
            require(TeacherFileTicketDTO.DIRECTION_DOWNLOAD.equals(gradeTicket.getDirection()),
                    "the grade export must be a download ticket");
            require(TEACHER.equals(gradeQueries.lastUid)
                            && OFFERING_ID.equals(gradeQueries.lastOfferingId)
                            && Integer.valueOf(2).equals(gradeQueries.lastStatus),
                    "the teacher identity must come from the session, and the majors from the"
                            + " enrolled roster, saw " + gradeQueries.lastUid + "/"
                            + gradeQueries.lastOfferingId + "/" + gradeQueries.lastStatus);
            require(headerOf(fileAwaitingDownload(tickets.getTempDirectory(), GRADE_HEADERS))
                            .equals(GRADE_HEADERS),
                    "the generated file must be the grade export");

            Message gradeExportWithoutRoster = new TeacherCourseHandler(
                    new RecordingQueryService(List.of()), null,
                    new RecordingGradeService(gradeBook(1)), tickets).handle(
                    request(TeacherCourseActions.REQUEST_GRADE_EXPORT, teacher.getToken(),
                            "offeringId", OFFERING_ID));
            require(gradeExportWithoutRoster.getCode() == MessageCode.SUCCESS,
                    "an offering with an empty roster must still export grades: "
                            + gradeExportWithoutRoster.getMessage());

            Message missingId = handler.handle(request(TeacherCourseActions.REQUEST_GRADE_TEMPLATE,
                    teacher.getToken(), "offeringId", 9007199254740993L));
            require(missingId.getCode() == MessageCode.BAD_REQUEST,
                    "a numeric offeringId must be rejected before any file is generated");

            Message badStatus = exportHandler.handle(request(TeacherCourseActions.REQUEST_ROSTER_EXPORT,
                    teacher.getToken(), "offeringId", OFFERING_ID, "enrollmentStatus", 1));
            require(badStatus.getCode() == MessageCode.BAD_REQUEST,
                    "enrollmentStatus only accepts 2 or 3");

            Message noFileService = new TeacherCourseHandler(new RecordingQueryService(List.of()))
                    .handle(request(TeacherCourseActions.REQUEST_ROSTER_EXPORT, teacher.getToken(),
                            "offeringId", OFFERING_ID));
            require(noFileService.getCode() == MessageCode.BAD_REQUEST
                            && "该教师操作尚未开放".equals(noFileService.getMessage()),
                    "a handler without the file service must report the operation as not open yet");
        } finally {
            tickets.close();
        }
    }

    /**
     * 归属必须在生成文件与签发票据**之前**校验：被拒时既没有票据，也没有任何文件落到临时目录。
     * 这是 Task 1 评审带过来的要求，也是导出路径与上传路径不同的一点（导出文件本身就是别人的名单）。
     */
    private static void verifyOwnershipBeforeDownloadTicket(UserSession teacher) {
        TeacherFileTicketService tickets = new TeacherFileTicketService(0);
        try {
            RecordingGradeService grades = new RecordingGradeService(gradeBook(1));
            grades.failure = new TeacherAccessPolicy.AccessDeniedException("没有查看该教学班的权限");
            Message deniedTemplate = new TeacherCourseHandler(new RecordingQueryService(List.of()), null,
                    grades, tickets).handle(request(TeacherCourseActions.REQUEST_GRADE_TEMPLATE,
                    teacher.getToken(), "offeringId", OFFERING_ID));
            require(deniedTemplate.getCode() == MessageCode.FORBIDDEN,
                    "another teacher's offering must be forbidden, saw " + deniedTemplate.getCode());
            require(deniedTemplate.getData() == null
                            || !deniedTemplate.getData().containsKey("ticket"),
                    "a forbidden template request must not issue a ticket");
            require(tickets.getTempDirectory().toFile().listFiles().length == 0,
                    "no file may be generated before the ownership check passes");

            RecordingQueryService queries = new RecordingQueryService(List.of());
            queries.failure = new TeacherAccessPolicy.AccessDeniedException("没有查看该教学班的权限");
            Message deniedExport = new TeacherCourseHandler(queries, null,
                    new RecordingGradeService(gradeBook(1)), tickets).handle(
                    request(TeacherCourseActions.REQUEST_ROSTER_EXPORT, teacher.getToken(),
                            "offeringId", OFFERING_ID));
            require(deniedExport.getCode() == MessageCode.FORBIDDEN,
                    "another teacher's roster must be forbidden, saw " + deniedExport.getCode());
            require(deniedExport.getData() == null
                            || !deniedExport.getData().containsKey("ticket"),
                    "a forbidden export must not issue a ticket");
            require(tickets.getTempDirectory().toFile().listFiles().length == 0,
                    "a forbidden export must not leave a generated file behind");

            // 成绩导出的第一步就是取成绩表（它自己带归属校验）：被拒时同样没有票据、没有文件。
            RecordingGradeService deniedGrades = new RecordingGradeService(gradeBook(1));
            deniedGrades.failure =
                    new TeacherAccessPolicy.AccessDeniedException("没有查看该教学班的权限");
            Message deniedGradesExport = new TeacherCourseHandler(
                    new RecordingQueryService(List.of()), null, deniedGrades, tickets).handle(
                    request(TeacherCourseActions.REQUEST_GRADE_EXPORT, teacher.getToken(),
                            "offeringId", OFFERING_ID));
            require(deniedGradesExport.getCode() == MessageCode.FORBIDDEN,
                    "another teacher's grade book must be forbidden, saw "
                            + deniedGradesExport.getCode());
            require(deniedGradesExport.getData() == null
                            || !deniedGradesExport.getData().containsKey("ticket"),
                    "a forbidden grade export must not issue a ticket");
            require(tickets.getTempDirectory().toFile().listFiles().length == 0,
                    "no file may be generated for a forbidden grade export");
        } finally {
            tickets.close();
        }
    }

    // ------------------------------------------------------------------ 夹具与工具

    private static TeacherGradeBookDTO gradeBook(int studentCount) {
        List<GradeComponentDTO> components = List.of(
                new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 3000),
                new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000),
                new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 5000));
        List<TeacherGradeRowDTO> rows = new ArrayList<>();
        for (int i = 0; i < studentCount; i++) {
            rows.add(new TeacherGradeRowDTO("9000" + i, String.format(Locale.ROOT, "000%03d", i),
                    "学生" + i, null, null, null, false, List.of()));
        }
        return new TeacherGradeBookDTO(OFFERING_ID, 4, DIGEST, "DRAFT", new GradeSchemeDTO(components),
                rows, null, null, true, null, false);
    }

    private static TeacherRosterRowDTO roster(String enrollmentId, String uid, String name,
                                              String status) {
        return new TeacherRosterRowDTO(enrollmentId, uid, name, "计算机科学与技术", status,
                "2026-09-01T00:00:00Z", null);
    }

    /** 成绩导出用的成绩表：一行填齐、一行缺实验、一行全空。 */
    private static TeacherGradeBookDTO scoredGradeBook() {
        List<TeacherGradeRowDTO> rows = List.of(
                new TeacherGradeRowDTO("1001", "000123", "张三",
                        new GradeScoresDTO(new BigDecimal("90"), new BigDecimal("80"),
                                new BigDecimal("70"), new BigDecimal("60")),
                        new BigDecimal("75.00"), new BigDecimal("2.5"), true, List.of()),
                new TeacherGradeRowDTO("1002", "000124", "李四",
                        new GradeScoresDTO(new BigDecimal("100"), new BigDecimal("100"), null,
                                new BigDecimal("100")),
                        null, null, false, List.of()),
                new TeacherGradeRowDTO("1003", "000125", "王五", null, null, null, false,
                        List.of()));
        return new TeacherGradeBookDTO(OFFERING_ID, 4, DIGEST, "DRAFT", scoredScheme(), rows,
                null, null, true, null, false);
    }

    /** 30/20/20/30 四项全部启用：权重配齐，总评与绩点都算得出来。 */
    private static GradeSchemeDTO scoredScheme() {
        return scoredScheme(3000, 2000, 2000, 3000);
    }

    /** 同一个方案换一组权重：传 0 就是「还没配齐」，总评算不出来。 */
    private static GradeSchemeDTO scoredScheme(int daily, int midterm, int experiment,
                                               int finalterm) {
        return new GradeSchemeDTO(List.of(
                new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, daily),
                new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, midterm),
                new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, true, experiment),
                new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, finalterm)));
    }

    /** 实验禁用（权重 0）：它缺分不算“没填齐”，其余三项照旧配齐。 */
    private static GradeSchemeDTO disabledExperimentScheme() {
        return new GradeSchemeDTO(List.of(
                new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 3000),
                new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000),
                new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 5000)));
    }

    /**
     * 一份成绩表：方案与行都由调用方给。行里的总评/绩点照抄服务端算出来的那份值——导出不再自己算，
     * 所以夹具必须与服务端同形（算不出来的组合就是 null）。
     */
    private static TeacherGradeBookDTO gradeBookWith(GradeSchemeDTO scheme,
                                                     TeacherGradeRowDTO... rows) {
        return new TeacherGradeBookDTO(OFFERING_ID, 4, DIGEST, "DRAFT", scheme, List.of(rows),
                null, null, true, null, false);
    }

    /** 数值单元格的值；缺失单元格返回 -1（不会与合法分数混淆）。 */
    private static double score(Row row, int column) {
        Cell cell = row.getCell(column);
        return cell == null ? -1d : cell.getNumericCellValue();
    }

    /** 一行里 6 个数字列的文本，用于断言失败时看清整行。 */
    private static String scoresOf(Row row) {
        StringBuilder joined = new StringBuilder();
        for (int column = 3; column <= 8; column++) {
            joined.append(text(row.getCell(column))).append(' ');
        }
        return joined.toString().strip();
    }

    /** 写一个真实 .xlsx：给定表头与逐行内容（行号由调用方给，便于核对 Excel 行号）。 */
    @SafeVarargs
    private static Path workbook(String fileName, List<String> headers,
                                 Consumer<Sheet>... fillers) {
        Path target = tempDirectory.resolve(fileName);
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("成绩录入");
            header(sheet, headers);
            for (Consumer<Sheet> filler : fillers) {
                filler.accept(sheet);
            }
            write(workbook, target);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return target;
    }

    private static void header(Sheet sheet, List<String> headers) {
        Row row = sheet.createRow(0);
        for (int column = 0; column < headers.size(); column++) {
            row.createCell(column).setCellValue(headers.get(column));
        }
    }

    /** 单元格内容：字符串写成文本单元格，数字写成数值单元格，null 留空，{@link Formula} 写成公式。 */
    private static void row(Sheet sheet, int rowIndex, Object... values) {
        Row row = sheet.createRow(rowIndex);
        for (int column = 0; column < values.length; column++) {
            Object value = values[column];
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(column);
            if (value instanceof Formula formula) {
                cell.setCellFormula(formula.expression());
            } else if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else {
                cell.setCellValue(value.toString());
            }
        }
    }

    private static void fill(Sheet sheet, int dataRows) {
        for (int i = 0; i < dataRows; i++) {
            row(sheet, i + 1, "000000" + i, "学生" + i, "80", "80", "80", "80");
        }
    }

    private static void write(Workbook workbook, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            workbook.write(out);
        }
    }

    /**
     * 造一个仍然可读、但压缩体积或解压体积越界的 xlsx：先写一份正常的模板，把它的条目原样复制到
     * 新包里，再追加若干填充条目。
     *
     * <p>{@code incompressible=true} 用随机字节（1 MiB 一块，块间相隔远超 deflate 的 32 KiB 窗口，
     * 因此压不动）来撑大**文件本身**；{@code false} 用零字节撑大**解压后**的体积而文件几乎不涨。
     * 填充物按块流式写入，测试本身不会占几十 MiB 堆。每个填充条目都不超过上限的一半，所以 70 MiB
     * 的用例命中的是「解压后总量」这条规则，而不是「单个压缩项」那条。
     */
    private static Path archiveWithPadding(String fileName, long payloadBytes, boolean incompressible) {
        Path source = workbook(fileName + ".source.xlsx", TEMPLATE_HEADERS,
                sheet -> row(sheet, 1, "000123", "张三", "88.5", "90", "77", "100"));
        Path target = tempDirectory.resolve(fileName);
        byte[] block = new byte[1024 * 1024];
        if (incompressible) {
            new Random(20260916L).nextBytes(block);
        }
        try (ZipFile archive = new ZipFile(source.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                out.putNextEntry(new ZipEntry(entry.getName()));
                if ("[Content_Types].xml".equals(entry.getName())) {
                    // OPC 要求每个 part 都有内容类型：给填充条目的扩展名补一条 Default 规则，
                    // 这样加过料的包仍然是「POI 打得开的合法工作簿」，被拒只可能是因为我们的上限。
                    String xml = new String(archive.getInputStream(entry).readAllBytes(),
                            StandardCharsets.UTF_8);
                    out.write(xml.replace("</Types>", "<Default Extension=\"bin\""
                            + " ContentType=\"application/octet-stream\"/></Types>")
                            .getBytes(StandardCharsets.UTF_8));
                } else {
                    try (InputStream content = archive.getInputStream(entry)) {
                        content.transferTo(out);
                    }
                }
                out.closeEntry();
            }
            long remaining = payloadBytes;
            int index = 0;
            while (remaining > 0) {
                long size = Math.min(PADDING_ENTRY_BYTES, remaining);
                ZipEntry padding = new ZipEntry("xl/media/padding" + (index++) + ".bin");
                padding.setSize(size);
                out.putNextEntry(padding);
                for (long written = 0; written < size; written += block.length) {
                    out.write(block, 0, (int) Math.min(block.length, size - written));
                }
                out.closeEntry();
                remaining -= size;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return target;
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String text(Cell cell) {
        return cell == null ? "" : new DataFormatter(Locale.ROOT).formatCellValue(cell).strip();
    }

    /** 说明表里某一行（按第一列名字找）的第二列文字。 */
    private static String notesRowValue(Sheet notes, String label) {
        for (int rowIndex = 0; rowIndex <= notes.getLastRowNum(); rowIndex++) {
            Row row = notes.getRow(rowIndex);
            if (row != null && label.equals(text(row.getCell(0)))) {
                return text(row.getCell(1));
            }
        }
        return null;
    }

    private static String notesStatus(Sheet notes, String label) {
        for (int rowIndex = 0; rowIndex <= notes.getLastRowNum(); rowIndex++) {
            Row row = notes.getRow(rowIndex);
            if (row != null && label.equals(text(row.getCell(0)))) {
                return text(row.getCell(2));
            }
        }
        return null;
    }

    private static List<String> headerOf(Path workbook) {
        try (Workbook opened = WorkbookFactory.create(workbook.toFile())) {
            Row header = opened.getSheetAt(0).getRow(0);
            List<String> headers = new ArrayList<>();
            for (int column = 0; column < header.getLastCellNum(); column++) {
                headers.add(text(header.getCell(column)));
            }
            return headers;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** 临时目录里表头匹配的那份文件：文件名是服务端随机生成的，不能靠创建顺序来分辨。 */
    private static Path fileAwaitingDownload(Path directory, List<String> headers) {
        for (Path file : filesIn(directory)) {
            if (headers.equals(headerOf(file))) {
                return file;
            }
        }
        throw new AssertionError("no generated file starts with " + headers + " in " + filesIn(directory));
    }

    private static List<Path> filesIn(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static boolean hasError(TeacherSpreadsheetRow row, String field, String fragment) {
        return row.cellErrors().stream()
                .anyMatch(error -> field.equals(error.field()) && error.message().contains(fragment));
    }

    private static void expectInvalid(String fragment, Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected an IllegalArgumentException containing " + fragment);
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage() != null && expected.getMessage().contains(fragment),
                    "expected a message containing " + fragment + ", saw " + expected.getMessage());
        }
    }

    private static Message request(String action, String token, Object... keysAndValues) {
        require(keysAndValues.length % 2 == 0, "keys and values must come in pairs");
        Message request = new Message(MessageType.REQUEST, "courseTeacher", action);
        request.setToken(token);
        for (int i = 0; i < keysAndValues.length; i += 2) {
            request.putData(keysAndValues[i].toString(), keysAndValues[i + 1]);
        }
        return request;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 测试清理是尽力而为。
                }
            });
        } catch (IOException ignored) {
            // 同上。
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 公式单元格的标记：POI 写公式时不求值，读回来必须仍是 FORMULA。 */
    private record Formula(String expression) {
    }

    /** 记录型假查询服务：Handler 必须走这个（带归属校验的）入口，而不是自己查 DAO。 */
    private static final class RecordingQueryService extends TeacherCourseQueryService {
        private final List<TeacherRosterRowDTO> roster;
        private RuntimeException failure;
        private String lastUid;
        private String lastOfferingId;
        private String lastQuery;
        private Integer lastStatus;

        private RecordingQueryService(List<TeacherRosterRowDTO> roster) {
            this.roster = roster;
        }

        @Override
        public List<TeacherRosterRowDTO> listAllOfferingStudents(String uid, String offeringId,
                                                                 String query, Integer enrollmentStatus) {
            lastUid = uid;
            lastOfferingId = offeringId;
            lastQuery = query;
            lastStatus = enrollmentStatus;
            if (failure != null) {
                throw failure;
            }
            return roster;
        }
    }

    /** 记录型假成绩服务：模板的数据源，同时用来验证归属校验在生成文件之前发生。 */
    private static final class RecordingGradeService extends TeacherGradeBookService {
        private final TeacherGradeBookDTO book;
        private RuntimeException failure;
        private String lastUid;
        private String lastOfferingId;

        private RecordingGradeService(TeacherGradeBookDTO book) {
            this.book = book;
        }

        @Override
        public TeacherGradeBookDTO getGradeBook(String uid, String offeringId) {
            lastUid = uid;
            lastOfferingId = offeringId;
            if (failure != null) {
                throw failure;
            }
            return book;
        }
    }
}
