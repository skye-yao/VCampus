package service;

import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeSchemeDTO;
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
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 教师成绩 Excel 的服务端行为矩阵（本套件无数据库：读数、写数都不碰 MySQL）。
 *
 * <p>三条主线，各自钉住真实文件而不是桩：
 * <ol>
 *   <li><b>解析</b>上传的工作簿：中文姓名、前导零学号（{@code DataFormatter} 文本而不是数字转换）、
 *       缺列、空白、0/100/小数、重复学号、公式、错误表头、损坏文件与超过 5000 行。</li>
 *   <li><b>生成</b>成绩模板与名单导出：用 POI 重新打开生成的文件，核对表头文字、文本格式/加粗样式与
 *       行数；模板第二张说明表写教学班、权重与禁用项。</li>
 *   <li><b>下载票据</b>：Handler 的两个新动作只回票据不回文件字节，且**先校验归属再生成文件**——
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
            verifyFormulaCells(service);
            verifyDuplicateStudentUids(service);
            verifyOnlyTheFirstSheetIsRead(service);
            verifyCorruptFilesAreRejected(service);
            verifyRowLimit(service);
            verifyRosterMatching(service);
            verifyGradeTemplate(service);
            verifyRosterExport(service);
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
     * 公式：学号是公式直接拒绝整份文件（原文里没有可归属的学号）；成绩是公式则保留原文并按行报错，
     * 绝不把 POI 算出来的值当成教师填的分数。
     */
    private static void verifyFormulaCells(TeacherSpreadsheetService service) {
        Path formulaUid = workbook("formula-uid.xlsx", TEMPLATE_HEADERS,
                sheet -> row(sheet, 1, new Formula("CONCATENATE(\"000\",\"123\")"), "张三", "88.5", null, null, null));
        expectInvalid("学号不能是公式", () -> service.parse(formulaUid));

        Path formulaScore = workbook("formula-score.xlsx", TEMPLATE_HEADERS,
                sheet -> row(sheet, 1, "000123", "张三", "88.5", new Formula("90+5"), null, null),
                sheet -> row(sheet, 2, "000124", "李四", "70", "80", "90", "100"));
        List<TeacherSpreadsheetRow> rows = service.parse(formulaScore);
        require(rows.size() == 2, "a formula cell must not drop the row: " + rows.size());
        TeacherSpreadsheetRow flagged = rows.get(0);
        require(flagged.cellErrors().size() == 1, "exactly one cell error, saw " + flagged.cellErrors());
        TeacherSpreadsheetRow.CellError error = flagged.cellErrors().get(0);
        require("midtermScore".equals(error.field()),
                "the error must point at the offending field, saw " + error.field());
        require(error.rawValue() != null && !error.rawValue().isEmpty(),
                "the raw cell text must be preserved for the preview, saw " + error.rawValue());
        require("期中成绩不能是公式".equals(error.message()), "saw " + error.message());
        require(flagged.rawMidtermScore() != null && flagged.rawMidtermScore().equals(error.rawValue()),
                "the flagged raw text must also be available as the row value");
        require(!rows.get(1).hasCellErrors(), "a formula in one row must not contaminate the others");
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
