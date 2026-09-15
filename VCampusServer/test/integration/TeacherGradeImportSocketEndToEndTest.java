package integration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dto.course.CourseActions;
import dto.course.GradeRecordDTO;
import dto.course.GradeSummaryDTO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.teacher.ConfirmGradeImportRequestDTO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeImportCorrectionDTO;
import dto.course.teacher.GradeImportPreviewDTO;
import dto.course.teacher.GradeImportRowIssueDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.ReviseGradeImportRequestDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherFileTicketDTO;
import dto.course.teacher.TeacherFileUploadRequestDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import handler.AdminCourseHandler;
import handler.CourseHandler;
import handler.TeacherCourseHandler;
import network.CourseFileServer;
import network.MessageDispatcher;
import network.OnlineConnectionRegistry;
import network.Server;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.TeacherAdjustmentApplicationService;
import service.TeacherCourseQueryService;
import service.TeacherFileTicketService;
import service.TeacherGradeBookService;
import service.TeacherGradeImportService;
import service.TeacherGradeImportStore;
import util.DBUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Real-wire proof of the whole teacher spreadsheet chain in <em>one</em> fixture: the teacher downloads
 * the grade template, fills it (one enabled column missing, a blank cell, an out-of-range 105 and an
 * unknown student), uploads it over the file port, corrects and excludes rows in the preview, confirms
 * the import into the draft, completes and submits it, the administrator approves that batch, and the
 * student reads the published grade.
 *
 * <p>Why one fixture over two real ports: the preview/confirm and the approve/read halves each have
 * their own snapshot tests, and both of them would keep passing after the two sides drifted apart —
 * the import test hands the ticket over at the ticket-service seam, and the administrator test parses
 * a hand-written batch. Here the draft the administrator's batch froze is the one this run's confirm
 * wrote from a workbook that travelled over the file port, and the student's published numbers are
 * recomputed from rows that came out of that same file.
 *
 * <p>What the parts prove:
 * <ul>
 *   <li>Download → edit → upload → preview — the template the teacher gets is the layout the parser
 *       accepts, the preview writes nothing (draft, items, published grades, submissions and the audit
 *       trail all byte-identical), a missing <em>enabled</em> column keeps the draft value, a blank
 *       cell keeps the draft value, and neither ever becomes 0.</li>
 *   <li>Correct → exclude → confirm — the corrected cell (105 → 91) is what lands in the draft, the
 *       excluded unknown-student row is never merged, only the draft moves (revision 1 → 2 while the
 *       published grade table still has no row), and the audit rows of this write carry the
 *       {@code confirmGradeImport} action so an import is distinguishable from a hand edit.</li>
 *   <li>Ticket reuse — a second transfer and a second preview with the same upload ticket are refused,
 *       a truncated upload leaves no half file and no claimable ticket, and the teacher can simply
 *       upload again.</li>
 *   <li>Lost response — a confirm retried with the same operationId after the token was consumed
 *       replays the stored result instead of failing, writing no second draft, audit or operation row.</li>
 *   <li>Cancel — dropping the preview token really kills it (a revise afterwards reports the expired
 *       preview) and leaves the draft exactly where it was.</li>
 *   <li>Export — a roster longer than one page exports every row (105 &gt; the 100-row page cap), the
 *       export and the list agree on filter and order, and another account cannot redeem the teacher's
 *       ticket on the file port.</li>
 *   <li>Stop — the listener, the in-flight short connection, the temp directory holding every transfer
 *       file and the {@code course-file-*} threads are all gone.</li>
 * </ul>
 *
 * <p>Fixtures live in the 984xxx id range with the {@code tgie984-} UID prefix — none of
 * {@code tgb947-} (947000-947999), {@code tgi948-} (948000-948999), {@code tge983-} (983000-983999)
 * or {@code gra974-} (974100-974799) — and only {@code virtual_campus_course_test} is allowed. Every
 * fixture row is deleted again even when an assertion fails.
 */
public final class TeacherGradeImportSocketEndToEndTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String PREFIX = "tgie984-";
    private static final String TEACHER = PREFIX + "teacher-a";
    private static final String OTHER_TEACHER = PREFIX + "teacher-b";
    private static final String ADMIN = PREFIX + "admin";
    private static final String PASSWORD = "course-test-only";
    private static final Gson GSON = new Gson();
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.ROOT);

    private static final int YEAR = 2027;
    private static final int SEMESTER = 1;

    private static final long COURSE_MAIN = 984101L;
    private static final long COURSE_EXPORT = 984102L;
    private static final long OFFERING_MAIN = 984201L;
    private static final long OFFERING_EXPORT = 984202L;
    private static final String OFFERING_MAIN_ID = Long.toString(OFFERING_MAIN);
    /** The import chain's five students: enrollments 984301..984305. */
    private static final long ENROLL_MAIN_FIRST = 984301L;
    private static final int MAIN_STUDENTS = 5;
    /** The export offering's roster: 105 enrollments, the last one dropped. */
    private static final long ENROLL_EXPORT_FIRST = 984401L;
    private static final int EXPORT_STUDENTS = 105;
    private static final int DROPPED_INDEX = EXPORT_STUDENTS;

    /** The scheme the teacher enters by hand first: 40/20/–/40, EXPERIMENT disabled. */
    private static final GradeSchemeDTO SCHEME = new GradeSchemeDTO(List.of(
            new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 4000),
            new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000),
            new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, false, 0),
            new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 4000)));

    private static final String TEMPLATE_HEADERS = "学号|姓名|平时成绩|期中成绩|实验成绩|期末成绩";

    private static Path workDirectory;

    private TeacherGradeImportSocketEndToEndTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        LoginSchemaTestBridge.ensureBankAccountTable();
        cleanup();
        workDirectory = Files.createTempDirectory("teacher-grade-import-e2e-");
        Server server = null;
        Thread serverThread = null;
        TeacherFileTicketService tickets = null;
        try {
            insertFixtures();
            tickets = new TeacherFileTicketService(0);
            CourseFileServer fileServer = new CourseFileServer(tickets, 0);
            TeacherGradeBookService grades = new TeacherGradeBookService();
            TeacherGradeImportService imports = new TeacherGradeImportService(tickets, grades,
                    new TeacherGradeImportStore());
            MessageDispatcher dispatcher = new MessageDispatcher(new CourseHandler(),
                    new AdminCourseHandler(),
                    new TeacherCourseHandler(new TeacherCourseQueryService(),
                            new TeacherAdjustmentApplicationService(), grades, tickets, imports));
            server = new Server(0, new OnlineConnectionRegistry(), dispatcher, null, null,
                    fileServer);
            serverThread = new Thread(server::start, "teacher-grade-import-e2e-server");
            serverThread.setDaemon(true);
            serverThread.start();
            awaitRunning(server);
            int businessPort = server.getPort();
            int filePort = fileServer.getPort();
            Path tempDirectory = tickets.getTempDirectory();
            require(filePort > 0 && filePort != businessPort,
                    "the file listener must bind its own port (business " + businessPort + ", file "
                            + filePort + ")");

            Socket inFlight = runScenarios(businessPort, filePort, tempDirectory);

            // ---- stop: nothing may outlive the server, not even the connection that was mid-flight.
            server.stop();
            serverThread.join(5_000);
            require(!serverThread.isAlive(), "the business server must stop");
            requireStopped(inFlight, tempDirectory, businessPort, filePort);
        } finally {
            if (tickets != null) tickets.close();
            if (server != null) server.stop();
            if (serverThread != null) serverThread.join(5_000);
            deleteRecursively(workDirectory);
            cleanup();
        }
        require(count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN"
                        + " 984200 AND 984299") == 0
                        && count("SELECT COUNT(*) FROM grade WHERE enrollment_id BETWEEN 984300"
                        + " AND 984599") == 0,
                "cleanup must leave no fixture row behind");
        System.out.println("Teacher grade import socket end-to-end test passed.");
    }

    /**
     * Every scenario before the stop, in the order the story is told; returns the in-flight file
     * connection whose payload is still being sent when the caller stops the server.
     */
    private static Socket runScenarios(int businessPort, int filePort, Path tempDirectory)
            throws Exception {
        try (JsonLineClient teacher = new JsonLineClient(businessPort);
             JsonLineClient other = new JsonLineClient(businessPort);
             JsonLineClient admin = new JsonLineClient(businessPort);
             JsonLineClient student = new JsonLineClient(businessPort);
             JsonLineClient stranger = new JsonLineClient(businessPort)) {
            String teacherToken = teacher.login(TEACHER, "教师");
            String otherToken = other.login(OTHER_TEACHER, "教师");
            String adminToken = admin.login(ADMIN, "管理员");
            String studentToken = student.login(studentUid(1), "学生");
            String strangerToken = stranger.login(studentUid(2), "学生");

            templateDownloadAndForeignTickets(teacher, teacherToken, other, otherToken, stranger,
                    strangerToken, filePort, tempDirectory);
            exportRosterBeyondOnePage(teacher, teacherToken, tempDirectory);
            importChain(teacher, teacherToken, tempDirectory);
            secondPreviewCancelled(teacher, teacherToken, tempDirectory);
            interruptedUpload(teacher, teacherToken, tempDirectory);
            submitApproveAndStudentReads(teacher, teacherToken, admin, adminToken, student,
                    studentToken, tempDirectory);
            return inFlightUploadBeforeStop(teacher, teacherToken, filePort, tempDirectory);
        }
    }

    // --------------------------------------------------------- template and foreign tickets

    /**
     * The teacher's own template must come back as a real workbook with the parser's fixed layout, and
     * neither another teacher nor a student may take that file: the business entry refuses them, the
     * file port refuses the ticket, and both refusals leave the owner's ticket claimable.
     */
    private static void templateDownloadAndForeignTickets(JsonLineClient teacher, String teacherToken,
            JsonLineClient other, String otherToken, JsonLineClient stranger, String strangerToken,
            int filePort, Path tempDirectory) throws Exception {
        requireCode(other.request("courseTeacher", TeacherCourseActions.REQUEST_GRADE_TEMPLATE,
                        otherToken, Map.of("offeringId", OFFERING_MAIN_ID)), MessageCode.FORBIDDEN,
                "another teacher must not request a coworker's grade template");
        requireCode(stranger.request("courseTeacher", TeacherCourseActions.REQUEST_ROSTER_EXPORT,
                        strangerToken, Map.of("offeringId", OFFERING_MAIN_ID)), MessageCode.FORBIDDEN,
                "a student must not request an export of a class roster");

        TeacherFileTicketDTO template = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.REQUEST_GRADE_TEMPLATE, teacherToken,
                Map.of("offeringId", OFFERING_MAIN_ID)));
        require(TeacherFileTicketDTO.DIRECTION_DOWNLOAD.equals(template.getDirection())
                        && template.getByteLength() > 0
                        && template.getSha256() != null && !template.getSha256().isBlank(),
                "a template download ticket must describe the generated file");
        require(template.getPort() == filePort,
                "the ticket must carry the port the file listener actually bound, saw "
                        + template.getPort() + " instead of " + filePort);

        // 他人的 token 兑换不了这张票，而且拒绝之后票据仍然属于签发它的那位教师。
        byte[] none = null;
        expectFileRejected(template.getPort(), fileMetadata(template, otherToken), none, false,
                "文件票据不属于当前登录会话");
        expectFileRejected(template.getPort(), fileMetadata(template, strangerToken), none, false,
                "文件票据不属于当前登录会话");
        expectFileRejected(template.getPort(), fileMetadata(template, null), none, false,
                "文件元数据无效");

        Path downloaded = workDirectory.resolve("template.xlsx");
        Files.write(downloaded, fileDownload(template.getPort(),
                fileMetadata(template, teacherToken), template.getByteLength()));
        require(tempDirectoryIsEmpty(tempDirectory),
                "a claimed download must not leave the server file behind");
        require(template.getSha256().equalsIgnoreCase(TeacherFileTicketService.sha256(downloaded)),
                "the downloaded template must match the ticket's digest");
        requireTemplateLayout(downloaded);
        // 一张票只能兑换一次：下载过的票再用一次拿到的就是「已被使用」。
        expectFileRejected(template.getPort(), fileMetadata(template, teacherToken), none, false,
                "文件票据无效或已被使用");
    }

    /** The template is the file the teacher fills in: fixed first sheet, roster, and a notes sheet. */
    private static void requireTemplateLayout(Path template) throws IOException {
        List<List<String>> rows = sheetText(template, 0);
        require(!rows.isEmpty() && String.join("|", rows.get(0)).equals(TEMPLATE_HEADERS),
                "the template must carry the parser's fixed header row, saw "
                        + (rows.isEmpty() ? "no rows" : rows.get(0)));
        require(rows.size() == MAIN_STUDENTS + 1,
                "the template must list the whole roster, saw " + (rows.size() - 1) + " rows");
        for (int index = 0; index < MAIN_STUDENTS; index++) {
            List<String> row = rows.get(index + 1);
            require(studentUid(index + 1).equals(row.get(0)),
                    "template row " + (index + 1) + " must keep the student uid as text, saw "
                            + row.get(0));
            require(!row.get(1).isBlank(), "the template must carry the student name");
            for (int column = 2; column < 6; column++) {
                require(row.get(column).isBlank(),
                        "a blank score cell means 「keep the existing value」, so the template must"
                                + " ship it empty, saw " + row.get(column));
            }
        }
    }

    /** 说明表：教学班、四项组成与权重、禁用项。方案由服务端当前草稿决定，所以只在草稿存在时断言权重。 */
    private static String notesText(Path template) throws IOException {
        return String.join("\n", sheetText(template, 1).stream()
                .map(row -> String.join("|", row)).toList());
    }

    // ------------------------------------------------------------- export beyond one page

    /**
     * The export is not the current page: 105 enrolled students come back in full even though a page is
     * capped at 100, the optional filters behave exactly like the roster list, and the two orderings
     * agree row for row.
     */
    private static void exportRosterBeyondOnePage(JsonLineClient teacher, String teacherToken,
            Path tempDirectory) throws Exception {
        String offeringId = Long.toString(OFFERING_EXPORT);
        TeacherFileTicketDTO full = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.REQUEST_ROSTER_EXPORT, teacherToken,
                Map.of("offeringId", offeringId)));
        Path exported = workDirectory.resolve("roster.xlsx");
        Files.write(exported, fileDownload(full.getPort(), fileMetadata(full, teacherToken),
                full.getByteLength()));
        List<List<String>> rows = sheetText(exported, 0);
        require(rows.size() == EXPORT_STUDENTS + 1,
                "the export must carry every roster row, not one page, saw " + (rows.size() - 1));
        List<String> exportedUids = column(rows, 0);
        require(exportedUids.equals(exportUids()),
                "the export must keep the roster order (uid, enrollment id), saw "
                        + exportedUids.subList(0, 3) + "..."
                        + exportedUids.get(exportedUids.size() - 1));
        require(exportedUids.contains(exportUid(DROPPED_INDEX)),
                "the unfiltered export must list the dropped student too");
        require("退课".equals(rows.get(rows.size() - 1).get(3)) && "正常".equals(rows.get(1).get(3)),
                "the exported status column must spell out the enrollment state, saw "
                        + rows.get(rows.size() - 1).get(3) + "/" + rows.get(1).get(3));
        require(tempDirectoryIsEmpty(tempDirectory),
                "a claimed export must not leave the generated workbook behind");

        // 一页最多 100 行：导出比一页多，正好证明导出取的是全部结果而不是当前页。
        JsonObject page = rosterPage(teacher, teacherToken, offeringId, null, null);
        require(page.get("totalCount").getAsInt() == EXPORT_STUDENTS
                        && page.get("items").getAsJsonArray().size() == 100,
                "the roster list must page at 100 of " + EXPORT_STUDENTS + " students (observed "
                        + page.get("items").getAsJsonArray().size() + "/"
                        + page.get("totalCount").getAsInt() + ")");

        // 过滤条件与列表同源：同一个 query 下导出的学号序列必须与列表第一页逐行一致。
        JsonObject filteredPage = rosterPage(teacher, teacherToken, offeringId, "e10", null);
        TeacherFileTicketDTO filtered = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.REQUEST_ROSTER_EXPORT, teacherToken,
                Map.of("offeringId", offeringId, "query", "e10")));
        Path filteredFile = workDirectory.resolve("roster-filtered.xlsx");
        Files.write(filteredFile, fileDownload(filtered.getPort(),
                fileMetadata(filtered, teacherToken), filtered.getByteLength()));
        List<String> filteredUids = column(sheetText(filteredFile, 0), 0);
        require(filteredUids.equals(exportUids().subList(99, EXPORT_STUDENTS)),
                "the exported filter must match the list's, saw " + filteredUids);
        require(filteredUids.equals(uidsOf(filteredPage)),
                "the export and the list must agree on filter and order, saw " + filteredUids + " vs "
                        + uidsOf(filteredPage));

        TeacherFileTicketDTO dropped = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.REQUEST_ROSTER_EXPORT, teacherToken,
                Map.of("offeringId", offeringId, "enrollmentStatus", 3)));
        Path droppedFile = workDirectory.resolve("roster-dropped.xlsx");
        Files.write(droppedFile, fileDownload(dropped.getPort(),
                fileMetadata(dropped, teacherToken), dropped.getByteLength()));
        require(column(sheetText(droppedFile, 0), 0).equals(List.of(exportUid(DROPPED_INDEX))),
                "the status filter must reach the export as well, saw "
                        + column(sheetText(droppedFile, 0), 0));
    }

    // --------------------------------------------- download → edit → preview → confirm

    /**
     * The chain the task exists for: hand-enter a draft, download the template, fill it, preview without
     * writing anything, correct and exclude, then confirm into the draft.
     *
     * <p>The template is edited in place (its column layout is the parser's) and uploaded over the file
     * port, so the workbook the server parses is the one this run downloaded.
     */
    private static void importChain(JsonLineClient teacher, String teacherToken, Path tempDirectory)
            throws Exception {
        TeacherGradeBookDTO empty = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK, Map.of("offeringId", OFFERING_MAIN_ID)));
        require(empty.getRevision() == 0 && empty.isCanEdit()
                        && empty.getRows().size() == MAIN_STUDENTS,
                "the fixture offering starts without a working copy (observed revision "
                        + empty.getRevision() + ")");
        String digest = empty.getRosterDigest();

        // 1. 教师先手工录入一份草稿：导入要保留的就是这份值，缺列/空白不能把它擦成 0。
        Message saved = save(teacher, teacherToken, TeacherCourseActions.SAVE_GRADE_DRAFT,
                Map.of("request", new WriteGradeBookRequestDTO(op(1),
                        content(OFFERING_MAIN_ID, 0, digest, baseRows()))));
        requireCode(saved, MessageCode.SUCCESS, "save the hand-entered base draft");
        require(book(saved).getRevision() == 1, "the base draft must be revision 1");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=1 AND draft_open=1 AND last_submission_id IS NULL") == 1,
                "the base draft must be the open working copy");

        String draftBefore = draftSnapshot();
        String publishedBefore = publishedSnapshot();
        require(!draftBefore.isBlank() && publishedBefore.isBlank(),
                "the fixture must start with a draft and no published grade (draft=" + draftBefore
                        + " published=" + publishedBefore + ")");

        // 2. 下载模板并按其列序填好（缺列＝整列没有值），再上传。
        TeacherFileTicketDTO templateTicket = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.REQUEST_GRADE_TEMPLATE, teacherToken,
                Map.of("offeringId", OFFERING_MAIN_ID)));
        Path template = workDirectory.resolve("import-template.xlsx");
        Files.write(template, fileDownload(templateTicket.getPort(),
                fileMetadata(templateTicket, teacherToken), templateTicket.getByteLength()));
        // 模板的说明表取的就是这位教师当前这份草稿的方案：权重与禁用项必须与导入语用的方案一致，
        // 否则教师按模板填出来的成绩与候选的重算口径会各说各话。
        String notes = notesText(template);
        require(notes.contains("教学班") && notes.contains(OFFERING_MAIN_ID)
                        && notes.contains("平时成绩|40.00%|已启用")
                        && notes.contains("期中成绩|20.00%|已启用")
                        && notes.contains("实验成绩|0.00%|已禁用")
                        && notes.contains("期末成绩|40.00%|已启用")
                        && notes.contains("禁用项|实验成绩"),
                "the notes sheet must carry this draft's weights and disabled component, saw "
                        + notes);
        Path workbook = filledImportWorkbook(template);

        TeacherFileTicketDTO upload = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.BEGIN_GRADE_UPLOAD, teacherToken,
                Map.of("request", new TeacherFileUploadRequestDTO(OFFERING_MAIN_ID, 1, "成绩导入.xlsx",
                        Files.size(workbook), TeacherFileTicketService.sha256(workbook)))));
        require(TeacherFileTicketDTO.DIRECTION_UPLOAD.equals(upload.getDirection())
                        && upload.getByteLength() == Files.size(workbook),
                "the upload ticket must bind the declared length");
        fileUpload(upload.getPort(), fileMetadata(upload, teacherToken), workbook);
        // 落地的上传就该躺在临时目录里等着唯一一次预览领取：一个文件、没有 .part。
        require(tempDirectoryFiles(tempDirectory).size() == 1 && noPartialFile(tempDirectory),
                "a landed upload must leave exactly the server-named workbook, saw "
                        + tempDirectoryFiles(tempDirectory));

        // 3. 预览：不写库，候选保留缺列与空白对应的原草稿值，非法原文留在 issues 里。
        // 编辑副本就是教师刚刚手工录入的那一版（revision 1）：导入要合并进的就是它。
        GradeImportPreviewDTO preview = preview(teacher, teacherToken, upload.getTicket(),
                content(OFFERING_MAIN_ID, 1, digest, baseRows()));
        require(preview.getPreviewRevision() == 1 && preview.getTotalRows() == 6
                        && preview.getValidRows() == 4 && preview.getErrorRows() == 2,
                "the preview must count 6/4/2 rows (observed " + preview.getTotalRows() + "/"
                        + preview.getValidRows() + "/" + preview.getErrorRows() + ")");
        Map<Integer, List<GradeImportRowIssueDTO>> issues = issuesByRow(preview);
        GradeImportRowIssueDTO outOfRange = issueWithField(issues, 3,
                GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE);
        require("105".equals(outOfRange.getRawValue())
                        && outOfRange.getMessage().contains("期中成绩") && !outOfRange.isExcluded(),
                "the out-of-range cell must keep the teacher's original text (observed "
                        + outOfRange.getRawValue() + " / " + outOfRange.getMessage() + ")");
        GradeImportRowIssueDTO unknown = issueWithField(issues, 7,
                GradeImportRowIssueDTO.FIELD_STUDENT_UID);
        require("0000".equals(unknown.getRawValue()) && unknown.getMessage().contains("名单"),
                "an unknown student uid must be reported instead of skipped silently (observed "
                        + unknown.getRawValue() + " / " + unknown.getMessage() + ")");
        require(issues.getOrDefault(2, List.of()).isEmpty()
                        && issues.getOrDefault(5, List.of()).isEmpty()
                        && issues.getOrDefault(6, List.of()).isEmpty(),
                "a missing column and blank cells are not errors, saw " + issues);
        Map<Long, GradeScoresDTO> candidate = candidateScores(preview);
        require(same(scores(candidate, ENROLL_MAIN_FIRST), scores("88.5", "61", null, "63")),
                "the imported cell wins while a blank cell keeps the draft value (observed "
                        + text(scores(candidate, ENROLL_MAIN_FIRST)) + ")");
        require(same(scores(candidate, ENROLL_MAIN_FIRST + 2), scores("100", "100", null, "83")),
                "a whole column missing from the file keeps the draft value for every row (observed "
                        + text(scores(candidate, ENROLL_MAIN_FIRST + 2)) + ")");
        require(same(scores(candidate, ENROLL_MAIN_FIRST + 3), scores("90", "91", null, "93")),
                "blank cells keep the draft values and never become 0 (observed "
                        + text(scores(candidate, ENROLL_MAIN_FIRST + 3)) + ")");
        require(same(scores(candidate, ENROLL_MAIN_FIRST + 4), scores("70", null, null, null)),
                "the newly filled student keeps the untouched components empty (observed "
                        + text(scores(candidate, ENROLL_MAIN_FIRST + 4)) + ")");
        require(same(scores(candidate, ENROLL_MAIN_FIRST + 1), baseScores().get(ENROLL_MAIN_FIRST + 1)),
                "a row with an unresolved error is not merged into the candidate at all");
        require(OFFERING_MAIN_ID.equals(preview.getCandidate().getOfferingId())
                        && preview.getCandidate().getExpectedRevision() == 1
                        && digest.equals(preview.getCandidate().getRosterDigest()),
                "the candidate must carry the server's base revision and roster digest");

        require(draftBefore.equals(draftSnapshot()) && publishedBefore.equals(publishedSnapshot()),
                "a preview must not touch the draft or the published grades");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFFERING_MAIN) == 0,
                "a preview must not create a submission batch");
        require(tempDirectoryIsEmpty(tempDirectory),
                "a successful preview deletes the parsed workbook right away");
        require(count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='"
                        + TEACHER + "' AND operation_id='" + op(2) + "'") == 0,
                "a preview writes no operation-log row");

        // 令牌复用：同一次上传只能换一次预览，第二次拿到的就是「票据已被使用」。
        expectFailure(teacher, teacherToken, TeacherCourseActions.PREVIEW_GRADE_IMPORT,
                MessageCode.BAD_REQUEST,
                Map.of("uploadTicket", upload.getTicket(), "baseDraft",
                        content(OFFERING_MAIN_ID, 1, digest, baseRows())),
                "a second preview must not reuse the same upload ticket");

        // 4. 修正非法单元格 + 明确排除未知学生行：确认之前两件事都要做完。
        Message revised = teacher.request("courseTeacher", TeacherCourseActions.REVISE_GRADE_IMPORT,
                teacherToken, Map.of("request", new ReviseGradeImportRequestDTO(
                        preview.getImportToken(), 1,
                        List.of(new GradeImportCorrectionDTO(3,
                                Map.of(GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE, "91"))),
                        List.of(7))));
        requireCode(revised, MessageCode.SUCCESS, "revise the import preview");
        GradeImportPreviewDTO resolved = previewOf(revised);
        require(resolved.getPreviewRevision() == 2 && resolved.getValidRows() == 5
                        && resolved.getErrorRows() == 1,
                "revise must return an incremented revision and re-count the rows (observed "
                        + resolved.getPreviewRevision() + "/" + resolved.getValidRows() + "/"
                        + resolved.getErrorRows() + ")");
        require(issuesByRow(resolved).getOrDefault(3, List.of()).isEmpty()
                        && same(candidateScores(resolved).get(ENROLL_MAIN_FIRST + 1).getMidtermScore(),
                        decimal("91")),
                "the corrected cell stops being an issue and carries the corrected value");
        require(issuesByRow(resolved).get(7).stream().allMatch(GradeImportRowIssueDTO::isExcluded),
                "the excluded row keeps its issue marked excluded, saw " + issuesByRow(resolved).get(7));
        require(resolved.getIssues().stream().noneMatch(issue -> !issue.isExcluded()),
                "after the correction and the exclusion no unresolved issue may remain");

        // 5. 确认：只写草稿，不写正式成绩，也不产生提交批次。
        Message confirmed = save(teacher, teacherToken, TeacherCourseActions.CONFIRM_GRADE_IMPORT,
                Map.of("request", new ConfirmGradeImportRequestDTO(op(2), resolved.getImportToken(),
                        2, 1)));
        requireCode(confirmed, MessageCode.SUCCESS, "confirm the import over TCP");
        JsonObject result = result(confirmed);
        require(!result.get("replayed").getAsBoolean()
                        && result.get("message").getAsString().contains("成绩草稿"),
                "the confirm writes a draft and reports it as a fresh result (observed "
                        + result.get("message").getAsString() + ")");
        require(result.getAsJsonObject("value").get("revision").getAsInt() == 2,
                "the confirm writes draft revision 2");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=2 AND draft_open=1 AND draft_kind='INITIAL'") == 1,
                "the confirm must leave an open revision-2 working copy");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFFERING_MAIN) == 0
                        && publishedBefore.equals(publishedSnapshot()),
                "confirming an import publishes nothing");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + ENROLL_MAIN_FIRST
                        + " AND daily_score=88.50 AND midterm_score=61.00 AND experiment_score IS NULL"
                        + " AND finalterm_score=63.00") == 1,
                "the draft must keep the missing column's value and never turn it into 0");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + (ENROLL_MAIN_FIRST + 1)
                        + " AND daily_score=95.00 AND midterm_score=91.00"
                        + " AND finalterm_score=73.00") == 1,
                "the corrected cell is what gets saved and the absent column keeps its value");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + (ENROLL_MAIN_FIRST + 3)
                        + " AND daily_score=90.00 AND midterm_score=91.00 AND finalterm_score=93.00")
                        == 1,
                "blank cells keep the base draft values in the saved draft");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + (ENROLL_MAIN_FIRST + 4)
                        + " AND daily_score=70.00 AND midterm_score IS NULL"
                        + " AND experiment_score IS NULL AND finalterm_score IS NULL") == 1,
                "a component the file never filled must stay NULL, never 0");

        // 审计：这次写库的 action 必须是 confirmGradeImport（手工改一格是另一条路径）。
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND action='" + TeacherCourseActions.CONFIRM_GRADE_IMPORT
                        + "' AND book_revision=2") == 4
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND action='" + TeacherCourseActions.CONFIRM_GRADE_IMPORT
                        + "' AND enrollment_id=" + ENROLL_MAIN_FIRST
                        + " AND before_json IS NOT NULL AND after_json IS NOT NULL") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND action='" + TeacherCourseActions.CONFIRM_GRADE_IMPORT
                        + "' AND enrollment_id=" + (ENROLL_MAIN_FIRST + 3)) == 0,
                "the import is audited per changed student under its own action name");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND action='saveGradeDraft'") == 5,
                "the hand-entered draft keeps its own action name in the same audit table");
        require(count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='"
                        + TEACHER + "' AND operation_id='" + op(2) + "' AND action='"
                        + TeacherCourseActions.CONFIRM_GRADE_IMPORT + "' AND target_id='"
                        + OFFERING_MAIN + "' AND target_type='GRADE_BOOK' AND result_code='OK'") == 1,
                "the confirm logs exactly one operation row under its own action name");

        // 6. 响应丢失重试：令牌已被消费，同一个 operationId 只能重放已保存的结果。
        int auditRows = count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                + OFFERING_MAIN);
        Message replay = save(teacher, teacherToken, TeacherCourseActions.CONFIRM_GRADE_IMPORT,
                Map.of("request", new ConfirmGradeImportRequestDTO(op(2), resolved.getImportToken(),
                        2, 1)));
        requireCode(replay, MessageCode.SUCCESS, "retry the confirm after a lost response");
        JsonObject replayed = result(replay);
        require(replayed.get("replayed").getAsBoolean()
                        && replayed.get("message").getAsString()
                        .equals(result.get("message").getAsString())
                        && replayed.getAsJsonObject("value").get("revision").getAsInt() == 2,
                "the retry replays the stored result instead of failing on the consumed token");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN) == auditRows
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='"
                        + TEACHER + "' AND operation_id='" + op(2) + "'") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFFERING_MAIN + " AND revision=3") == 0,
                "a replay writes neither a new draft revision nor a second audit row");
    }

    // ------------------------------------------------------------------- cancel

    /**
     * 取消导入：第二个文件只有「学号」与「平时成绩」两列（缺列本身不是错误），预览成功之后丢弃
     * 令牌，令牌立刻失效，草稿停在确认后的那一版。
     */
    private static void secondPreviewCancelled(JsonLineClient teacher, String teacherToken,
            Path tempDirectory) throws Exception {
        TeacherGradeBookDTO book = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK, Map.of("offeringId", OFFERING_MAIN_ID)));
        String before = draftSnapshot();
        Path workbook = partialColumnWorkbook();
        TeacherFileTicketDTO upload = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.BEGIN_GRADE_UPLOAD, teacherToken,
                Map.of("request", new TeacherFileUploadRequestDTO(OFFERING_MAIN_ID, book.getRevision(),
                        "第二次导入.xlsx", Files.size(workbook),
                        TeacherFileTicketService.sha256(workbook)))));
        fileUpload(upload.getPort(), fileMetadata(upload, teacherToken), workbook);
        GradeImportPreviewDTO preview = preview(teacher, teacherToken, upload.getTicket(),
                content(OFFERING_MAIN_ID, book.getRevision(), book.getRosterDigest(), currentRows()));
        require(preview.getErrorRows() == 0 && preview.getValidRows() == 2,
                "a file with only two known columns is a legal preview, not an error (observed "
                        + preview.getValidRows() + "/" + preview.getErrorRows() + ")");
        require(before.equals(draftSnapshot()) && tempDirectoryIsEmpty(tempDirectory),
                "a preview of the second file writes nothing and deletes the upload");

        requireCode(teacher.request("courseTeacher", TeacherCourseActions.CANCEL_GRADE_IMPORT,
                        teacherToken,
                        Map.of("request", Map.of("importToken", preview.getImportToken()))),
                MessageCode.SUCCESS, "cancel the import");
        expectFailure(teacher, teacherToken, TeacherCourseActions.REVISE_GRADE_IMPORT,
                MessageCode.NOT_FOUND,
                Map.of("importToken", preview.getImportToken(), "expectedPreviewRevision",
                        preview.getPreviewRevision(), "corrections", List.of(), "excludedRows",
                        List.of(3)),
                "a cancelled preview must be gone");
        require(before.equals(draftSnapshot()),
                "cancelling an import must leave the draft exactly as it was");
    }

    // ------------------------------------------------------- interrupted upload

    /**
     * 网络中断：元数据之后只发一半字节就半关写端，服务端必须报「收到全部字节前中断」、不留下
     * 半截文件，也不给这张票交接「已落地」——预览因此拿不到文件，教师重新上传即可。
     */
    private static void interruptedUpload(JsonLineClient teacher, String teacherToken,
            Path tempDirectory) throws Exception {
        TeacherGradeBookDTO book = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK, Map.of("offeringId", OFFERING_MAIN_ID)));
        Path workbook = partialColumnWorkbook();
        byte[] payload = Files.readAllBytes(workbook);
        TeacherFileTicketDTO upload = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.BEGIN_GRADE_UPLOAD, teacherToken,
                Map.of("request", new TeacherFileUploadRequestDTO(OFFERING_MAIN_ID, book.getRevision(),
                        "半途中断.xlsx", payload.length, TeacherFileTicketService.sha256(workbook)))));
        expectFileRejected(upload.getPort(), fileMetadata(upload, teacherToken), payload, true,
                "文件上传在收到全部字节前中断");
        require(tempDirectoryIsEmpty(tempDirectory),
                "a truncated upload must leave neither a .part file nor a landed workbook, saw "
                        + tempDirectoryFiles(tempDirectory));
        require(noPartialFile(tempDirectory), "a truncated upload must clean up its .part file");

        // 传输阶段已经消费过这张票：再次上传和被预览都要被拒绝，教师只能重新申请。
        byte[] none = null;
        expectFileRejected(upload.getPort(), fileMetadata(upload, teacherToken), none, false,
                "文件票据无效或已被使用");
        expectFailure(teacher, teacherToken, TeacherCourseActions.PREVIEW_GRADE_IMPORT,
                MessageCode.BAD_REQUEST,
                Map.of("uploadTicket", upload.getTicket(), "baseDraft",
                        content(OFFERING_MAIN_ID, book.getRevision(), book.getRosterDigest(),
                                currentRows())),
                "a failed upload leaves no claimable ticket for the preview");
    }

    // -------------------------------------------------- complete, submit, approve, read

    /** 导入之后教师补齐再提交：提交通道仍然负责完整性，导入没有绕过它。 */
    private static void submitApproveAndStudentReads(JsonLineClient teacher, String teacherToken,
            JsonLineClient admin, String adminToken, JsonLineClient student, String studentToken,
            Path tempDirectory) throws Exception {
        TeacherGradeBookDTO draft = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK, Map.of("offeringId", OFFERING_MAIN_ID)));
        require("DRAFT".equals(draft.getState()) && draft.isCanEdit() && draft.getRevision() == 2,
                "the imported draft must stay editable (observed " + draft.getState() + "/"
                        + draft.getRevision() + ")");
        require(draft.getRows().size() == MAIN_STUDENTS,
                "the imported draft must still carry the whole roster");
        String publishedBefore = publishedSnapshot();

        Message submitted = save(teacher, teacherToken, TeacherCourseActions.SUBMIT_GRADE_BOOK,
                Map.of("request", new WriteGradeBookRequestDTO(op(3),
                        content(OFFERING_MAIN_ID, 2, draft.getRosterDigest(), completedRows()))));
        requireCode(submitted, MessageCode.SUCCESS, "submit the completed draft over TCP");
        TeacherGradeBookDTO pending = book(submitted);
        long batch = Long.parseLong(pending.getLastSubmissionId());
        require("PENDING".equals(pending.getState()) && pending.getRevision() == 3,
                "the submission must freeze the book as a PENDING batch at revision 3 (observed "
                        + pending.getState() + "/" + pending.getRevision() + ")");
        require(publishedBefore.equals(publishedSnapshot()),
                "a pending batch publishes nothing");

        // 管理员审批 → 学生才看得到成绩。
        requireCode(review(admin, adminToken, op(4), batch, 1), MessageCode.SUCCESS,
                "approve the batch over TCP");
        GradeSubmissionDetailDTO detail = submissionDetail(admin, adminToken, batch);
        require(detail.getSummary().getStatus() == ApprovalStatusDTO.APPROVED
                        && detail.getSummary().getStudentCount() == MAIN_STUDENTS,
                "the administrator's detail must be the batch this run submitted");
        requirePublishedGrades();
        GradeSummaryDTO summary = grades(student, studentToken);
        GradeRecordDTO record = recordFor(summary, "TGIE984A");
        require(record != null, "the student must see the approved course");
        require(close(record.getScore(), 72.8) && close(record.getGradePoint(), 2.0)
                        && close(record.getDailyScore(), 88.5) && close(record.getMidtermScore(), 61.0)
                        && close(record.getFinalScore(), 63.0) && record.getExperimentScore() == null,
                "the student reads the imported components with the disabled one NULL (observed "
                        + record.getDailyScore() + "/" + record.getMidtermScore() + "/"
                        + record.getFinalScore() + "/" + record.getExperimentScore() + ")");
        require(close(summary.getTermGpa(), 2.0),
                "one published course makes the term GPA the course's own point (observed "
                        + summary.getTermGpa() + ")");

        // 另一名学生读到的正是被修正过的 91 与补齐的 80/90（缺列与空白从不变成 0）。
        try (JsonLineClient fifth = new JsonLineClient(student.getPort())) {
            String fifthToken = fifth.login(studentUid(5), "学生");
            GradeRecordDTO fifthRecord = recordFor(grades(fifth, fifthToken), "TGIE984A");
            require(fifthRecord != null && close(fifthRecord.getScore(), 80.0)
                            && close(fifthRecord.getGradePoint(), 3.0)
                            && close(fifthRecord.getDailyScore(), 70.0)
                            && close(fifthRecord.getMidtermScore(), 80.0)
                            && close(fifthRecord.getFinalScore(), 90.0),
                    "the student whose row was completed after the import reads the completed values"
                            + " (observed " + (fifthRecord == null ? "none" : fifthRecord.getScore())
                            + ")");
        }
        require(tempDirectoryIsEmpty(tempDirectory),
                "no transfer file may be left behind after the whole chain");
    }

    // -------------------------------------------------------------- stop lifecycle

    /**
     * 停服前留下一条正在传输的短连接：停服必须关掉监听器、断开这条连接、收干净连接线程与临时目录。
     */
    private static Socket inFlightUploadBeforeStop(JsonLineClient teacher, String teacherToken,
            int filePort, Path tempDirectory) throws Exception {
        TeacherGradeBookDTO book = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK, Map.of("offeringId", OFFERING_MAIN_ID)));
        Path workbook = partialColumnWorkbook();
        byte[] payload = Files.readAllBytes(workbook);
        TeacherFileTicketDTO upload = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.BEGIN_GRADE_UPLOAD, teacherToken,
                Map.of("request", new TeacherFileUploadRequestDTO(OFFERING_MAIN_ID, book.getRevision(),
                        "停服时在途.xlsx", payload.length, TeacherFileTicketService.sha256(workbook)))));
        require(upload.getPort() == filePort, "the in-flight upload must use the file port");

        Socket inFlight = new Socket("127.0.0.1", filePort);
        inFlight.setSoTimeout(10_000);
        try {
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(inFlight.getOutputStream()));
            byte[] metadata = fileMetadata(upload, teacherToken).getBytes(StandardCharsets.UTF_8);
            out.writeInt(metadata.length);
            out.write(metadata);
            out.flush();
            out.write(payload, 0, 4);
            out.flush();
            awaitPartialFile(tempDirectory);
            require(Files.isDirectory(tempDirectory) && !tempDirectoryFiles(tempDirectory).isEmpty(),
                    "the in-flight upload must have a file for stop to reclaim");
            return inFlight;
        } catch (RuntimeException | IOException failure) {
            inFlight.close();
            throw failure;
        }
    }

    /** 停服后的可观测事实：连接被断开、临时目录被回收、两个端口都被释放、传输线程已退出。 */
    private static void requireStopped(Socket inFlight, Path tempDirectory, int businessPort,
            int filePort) throws Exception {
        try {
            boolean closed = false;
            try {
                closed = inFlight.getInputStream().read() < 0;
            } catch (IOException expected) {
                closed = true;
            }
            require(closed, "an in-flight short connection must be closed by stop");
        } finally {
            inFlight.close();
        }
        require(!Files.exists(tempDirectory),
                "stop must remove the ticket service's temp directory with every transfer file in it");
        requireNoLiveThreads();
        expectConnectFailure(filePort, "the file port must be released after stop");
        expectConnectFailure(businessPort, "the business port must be released after stop");
    }

    /** {@code course-file-connection} / {@code course-file-accept} 线程必须在停服后退出。 */
    private static void requireNoLiveThreads() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Set<String> alive = liveCourseFileThreads();
        while (!alive.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            alive = liveCourseFileThreads();
        }
        require(alive.isEmpty(),
                "no file-transfer thread may outlive the server, still alive: " + alive);
    }

    private static Set<String> liveCourseFileThreads() {
        Set<String> alive = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("course-file-")) {
                alive.add(thread.getName());
            }
        }
        return alive;
    }

    private static void expectConnectFailure(int port, String message) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            throw new AssertionError(message + ", saw " + socket);
        } catch (ConnectException expected) {
            // 端口已释放，正是停服要证明的结果。
        } catch (IOException failure) {
            throw new AssertionError(message, failure);
        }
    }

    // ------------------------------------------------------------------ workbooks

    /** 下载来的模板：删掉整列「期末成绩」，填入缺项、空白与 105，再追加一行未知学号的学生。 */
    private static Path filledImportWorkbook(Path template) throws IOException {
        Path target = workDirectory.resolve("filled-import.xlsx");
        try (Workbook book = WorkbookFactory.create(template.toFile())) {
            Sheet sheet = book.getSheetAt(0);
            Row header = sheet.getRow(0);
            // 缺列：整列没有表头，解析器据此认为这一列不存在（而不是一列 0 分）。
            header.removeCell(header.getCell(5));
            Map<String, Integer> rowByUid = new LinkedHashMap<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                String uid = text(row.getCell(0));
                if (!uid.isBlank()) rowByUid.put(uid, index);
            }
            require(rowByUid.size() == MAIN_STUDENTS,
                    "the template must list the whole roster, saw " + rowByUid.keySet());
            // S1：导入平时分，留空期中分（保留草稿值），期末整列缺失（同样保留）。
            put(sheet, rowByUid.get(studentUid(1)), 2, "88.5");
            put(sheet, rowByUid.get(studentUid(1)), 3, "");
            // S2：期中成绩 105 越界，整行都不会进入候选。
            put(sheet, rowByUid.get(studentUid(2)), 2, "95");
            put(sheet, rowByUid.get(studentUid(2)), 3, "105");
            // S3：合法的一整行。
            put(sheet, rowByUid.get(studentUid(3)), 2, "100");
            put(sheet, rowByUid.get(studentUid(3)), 3, "100");
            // S4：一行全空：合法，且必须原样保留草稿值。
            put(sheet, rowByUid.get(studentUid(4)), 2, "");
            put(sheet, rowByUid.get(studentUid(4)), 3, "");
            // S5：只填平时分，其余留给教师在导入之后补齐。
            put(sheet, rowByUid.get(studentUid(5)), 2, "70");
            put(sheet, rowByUid.get(studentUid(5)), 3, "");

            Row unknown = sheet.createRow(sheet.getLastRowNum() + 1);
            unknown.createCell(0).setCellValue("0000");
            unknown.createCell(1).setCellValue("无名氏");
            unknown.createCell(2).setCellValue("60");
            unknown.createCell(3).setCellValue("60");
            unknown.createCell(4).setCellValue("");
            write(book, target);
        }
        return target;
    }

    /** 第二个文件：只有「学号」与「平时成绩」两列，缺列不是错误。 */
    private static Path partialColumnWorkbook() throws IOException {
        Path target = workDirectory.resolve("partial-columns.xlsx");
        try (Workbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("成绩录入");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("学号");
            header.createCell(1).setCellValue("平时成绩");
            sheet.createRow(1);
            sheet.createRow(2);
            put(sheet, 1, 0, studentUid(1));
            put(sheet, 1, 1, "80");
            put(sheet, 2, 0, studentUid(3));
            put(sheet, 2, 1, "90");
            write(book, target);
        }
        return target;
    }

    private static void write(Workbook book, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            book.write(out);
        }
    }

    private static void put(Sheet sheet, Integer rowIndex, int column, String value) {
        require(rowIndex != null, "the sheet must contain the edited row");
        Row row = sheet.getRow(rowIndex);
        require(row != null, "the sheet must contain row " + rowIndex);
        row.createCell(column).setCellValue(value);
    }

    /** 第一张工作表的文本内容；空单元格是空串，行按最宽的一行补齐。 */
    private static List<List<String>> sheetText(Path workbook, int sheetIndex) throws IOException {
        try (Workbook book = WorkbookFactory.create(workbook.toFile())) {
            Sheet sheet = book.getSheetAt(sheetIndex);
            int width = 0;
            for (int index = 0; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row != null) width = Math.max(width, row.getLastCellNum());
            }
            List<List<String>> rows = new ArrayList<>();
            for (int index = 0; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                List<String> cells = new ArrayList<>();
                for (int column = 0; column < width; column++) {
                    cells.add(row == null ? "" : text(row.getCell(column)));
                }
                rows.add(List.copyOf(cells));
            }
            return rows;
        }
    }

    private static List<String> column(List<List<String>> rows, int index) {
        List<String> values = new ArrayList<>();
        for (int row = 1; row < rows.size(); row++) {
            values.add(rows.get(row).size() > index ? rows.get(row).get(index) : "");
        }
        return values;
    }

    private static String text(Cell cell) {
        return cell == null ? "" : FORMATTER.formatCellValue(cell).strip();
    }

    // ---------------------------------------------------- file port wire helpers

    private static String fileMetadata(TeacherFileTicketDTO ticket, String token) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ticket", ticket.getTicket());
        fields.put("token", token);
        fields.put("direction", ticket.getDirection());
        fields.put("size", ticket.getByteLength());
        fields.put("sha256", ticket.getSha256());
        return GSON.toJson(fields);
    }

    /** 一次完整的上传短连接；成功时读回执并断言 OK。 */
    private static void fileUpload(int port, String metadata, Path workbook) throws IOException {
        Response response = fileExchange(port, metadata, Files.readAllBytes(workbook), false);
        require(response.ok(), "a valid upload must be acknowledged, saw " + response.message());
    }

    /** 一次完整的下载短连接：按票据长度读满，再读回执。 */
    private static byte[] fileDownload(int port, String metadata, long expectedLength)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            byte[] bytes = metadata.getBytes(StandardCharsets.UTF_8);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(bytes.length);
            out.write(bytes);
            out.flush();
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            byte[] payload = new byte[(int) expectedLength];
            in.readFully(payload);
            Response response = readResponse(in);
            require(response.ok(), "a valid download must be acknowledged, saw " + response.message());
            return payload;
        }
    }

    /**
     * 一条短连接：长度前缀元数据 + 可选 payload，然后读回执。
     *
     * <p>{@code halfClose} 为真时只发一半字节并半关写端——这正是「网络中断」要制造的形状；
     * 为假时整份发送，用于校验通过或票据层面的拒绝。
     */
    private static Response fileExchange(int port, String metadata, byte[] payload, boolean halfClose)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            byte[] bytes = metadata.getBytes(StandardCharsets.UTF_8);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(bytes.length);
            out.write(bytes);
            if (payload != null) {
                out.write(payload, 0, halfClose ? payload.length / 2 : payload.length);
            }
            out.flush();
            if (halfClose) {
                socket.shutdownOutput();
            }
            return readResponse(socket);
        }
    }

    private static void expectFileRejected(int port, String metadata, byte[] payload,
            boolean halfClose, String reason) throws IOException {
        Response response = fileExchange(port, metadata, payload, halfClose);
        require(!response.ok(), "the transfer must be rejected: " + reason);
        require(response.message().contains(reason),
                "the rejection must report 「" + reason + "」, saw " + response.message());
    }

    private static Response readResponse(Socket socket) throws IOException {
        return readResponse(new DataInputStream(new BufferedInputStream(socket.getInputStream())));
    }

    private static Response readResponse(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] body = new byte[length];
        in.readFully(body);
        JsonObject json = GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        return new Response(json.get("status").getAsString(),
                json.get("message") == null ? "" : json.get("message").getAsString());
    }

    private record Response(String status, String message) {
        boolean ok() {
            return "OK".equals(status);
        }
    }

    // --------------------------------------------------------------- bus helpers

    private static TeacherFileTicketDTO ticket(Message response) {
        requireCode(response, MessageCode.SUCCESS, "file ticket request");
        Object value = response.getData("ticket");
        require(value != null, "the response must carry a file ticket under `ticket`");
        return GSON.fromJson(GSON.toJsonTree(value), TeacherFileTicketDTO.class);
    }

    private static JsonObject result(Message response) {
        requireCode(response, MessageCode.SUCCESS, "write response");
        Object value = response.getData("result");
        require(value != null, "the response must carry the operation result");
        return GSON.toJsonTree(value).getAsJsonObject();
    }

    /** 预览请求体与写请求同形：{@code data.request} 里放票据与编辑副本。 */
    private static GradeImportPreviewDTO preview(JsonLineClient teacher, String token,
            String uploadTicket, GradeBookContentDTO baseDraft) throws IOException {
        Message response = teacher.request("courseTeacher", TeacherCourseActions.PREVIEW_GRADE_IMPORT,
                token, Map.of("request", Map.of("uploadTicket", uploadTicket,
                        "baseDraft", baseDraft)));
        requireCode(response, MessageCode.SUCCESS, "preview the uploaded workbook");
        return previewOf(response);
    }

    private static GradeImportPreviewDTO previewOf(Message response) {
        Object value = response.getData("preview");
        require(value != null, "the response must carry the preview under `preview`");
        return GSON.fromJson(GSON.toJsonTree(value), GradeImportPreviewDTO.class);
    }

    /** 业务拒绝：断言状态码（预览/修订/取消的请求体都在 {@code request} 里，与写请求同形）。 */
    private static void expectFailure(JsonLineClient client, String token, String action,
            MessageCode expected, Map<String, Object> body, String what) throws IOException {
        Message response = client.request("courseTeacher", action, token, Map.of("request", body));
        require(response.getCode() == expected,
                what + " must be " + expected + ", saw " + response.getCode() + " ("
                        + response.getMessage() + ")");
    }

    private static Message save(JsonLineClient teacher, String token, String action,
            Map<String, Object> data) throws IOException {
        return teacher.request("courseTeacher", action, token, data);
    }

    private static JsonObject rosterPage(JsonLineClient teacher, String token, String offeringId,
            String query, Integer status) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("offeringId", offeringId);
        data.put("page", 1);
        data.put("size", 100);
        if (query != null) data.put("query", query);
        if (status != null) data.put("enrollmentStatus", status);
        Message response = teacher.request("courseTeacher",
                TeacherCourseActions.LIST_OFFERING_STUDENTS, token, data);
        requireCode(response, MessageCode.SUCCESS, "list the roster page");
        return GSON.toJsonTree(response.getData("students")).getAsJsonObject();
    }

    private static List<String> uidsOf(JsonObject page) {
        List<String> uids = new ArrayList<>();
        for (var item : page.get("items").getAsJsonArray()) {
            uids.add(item.getAsJsonObject().get("studentUid").getAsString());
        }
        return uids;
    }

    private static GradeSubmissionDetailDTO submissionDetail(JsonLineClient admin, String token,
            long submissionId) throws IOException {
        Message response = admin.request("courseAdmin", AdminCourseActions.GET_GRADE_SUBMISSION,
                token, Map.of("submissionId", Long.toString(submissionId)));
        requireCode(response, MessageCode.SUCCESS, "read the submission detail");
        return GSON.fromJson(GSON.toJsonTree(response.getData("gradeSubmission")),
                GradeSubmissionDetailDTO.class);
    }

    private static Message review(JsonLineClient admin, String token, String operationId,
            long submissionId, int expectedVersion) throws IOException {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("operationId", operationId);
        decision.put("requestId", Long.toString(submissionId));
        decision.put("expectedVersion", expectedVersion);
        decision.put("approved", true);
        return admin.request("courseAdmin", AdminCourseActions.REVIEW_GRADE_SUBMISSION, token,
                Map.of("request", decision));
    }

    private static GradeSummaryDTO grades(JsonLineClient student, String token) throws IOException {
        Message response = student.request("course", CourseActions.LOAD_GRADES, token,
                Map.of("academicYear", YEAR, "semester", SEMESTER));
        requireCode(response, MessageCode.SUCCESS, "read the student grades");
        return GSON.fromJson(GSON.toJsonTree(response.getData("grades")), GradeSummaryDTO.class);
    }

    private static GradeRecordDTO recordFor(GradeSummaryDTO summary, String courseCode) {
        if (summary == null || summary.getRecords() == null) return null;
        for (GradeRecordDTO record : summary.getRecords()) {
            if (courseCode.equals(record.getCourseCode())) return record;
        }
        return null;
    }

    private static TeacherGradeBookDTO book(Message response) {
        requireCode(response, MessageCode.SUCCESS, "grade book request");
        Object source = response.getData("gradeBook");
        if (source == null) {
            source = GSON.toJsonTree(response.getData("result")).getAsJsonObject().get("value");
        }
        require(source != null, "the response must carry a grade book");
        return GSON.fromJson(GSON.toJsonTree(source), TeacherGradeBookDTO.class);
    }

    // -------------------------------------------------------------- grade content

    private static GradeBookContentDTO content(String offeringId, int expectedRevision, String digest,
            List<GradeRowInputDTO> rows) {
        return new GradeBookContentDTO(offeringId, expectedRevision, digest, SCHEME, rows);
    }

    /** 手工录入的第一版草稿：四位学生有值，第五位什么都没填（导入之后才补齐）。 */
    private static Map<Long, GradeScoresDTO> baseScores() {
        Map<Long, GradeScoresDTO> scores = new LinkedHashMap<>();
        scores.put(ENROLL_MAIN_FIRST, scores("60", "61", null, "63"));
        scores.put(ENROLL_MAIN_FIRST + 1, scores("70", "71", null, "73"));
        scores.put(ENROLL_MAIN_FIRST + 2, scores("80", "81", null, "83"));
        scores.put(ENROLL_MAIN_FIRST + 3, scores("90", "91", null, "93"));
        scores.put(ENROLL_MAIN_FIRST + 4, scores(null, null, null, null));
        return scores;
    }

    /** 导入之后的候选：平时/期中来自文件（含修正），期末整列缺失保留原值，S5 仍缺两项。 */
    private static Map<Long, GradeScoresDTO> afterImportScores() {
        Map<Long, GradeScoresDTO> scores = new LinkedHashMap<>();
        scores.put(ENROLL_MAIN_FIRST, scores("88.5", "61", null, "63"));
        scores.put(ENROLL_MAIN_FIRST + 1, scores("95", "91", null, "73"));
        scores.put(ENROLL_MAIN_FIRST + 2, scores("100", "100", null, "83"));
        scores.put(ENROLL_MAIN_FIRST + 3, scores("90", "91", null, "93"));
        scores.put(ENROLL_MAIN_FIRST + 4, scores("70", null, null, null));
        return scores;
    }

    /** 提交前补齐：S5 的期中/期末补上，其余沿用导入后的草稿值。 */
    private static Map<Long, GradeScoresDTO> completed() {
        Map<Long, GradeScoresDTO> scores = new LinkedHashMap<>(afterImportScores());
        scores.put(ENROLL_MAIN_FIRST + 4, scores("70", "80", null, "90"));
        return scores;
    }

    private static List<GradeRowInputDTO> baseRows() {
        return rowsOf(baseScores());
    }

    /** 编辑副本里的当前内容 = 确认导入之后草稿停在的那一版（revision 2）。 */
    private static List<GradeRowInputDTO> currentRows() {
        return rowsOf(afterImportScores());
    }

    private static List<GradeRowInputDTO> completedRows() {
        return rowsOf(completed());
    }

    private static List<GradeRowInputDTO> rowsOf(Map<Long, GradeScoresDTO> scores) {
        List<GradeRowInputDTO> rows = new ArrayList<>();
        for (Map.Entry<Long, GradeScoresDTO> entry : scores.entrySet()) {
            rows.add(new GradeRowInputDTO(Long.toString(entry.getKey()), entry.getValue()));
        }
        return rows;
    }

    private static GradeScoresDTO scores(String daily, String midterm, String experiment,
            String finalterm) {
        return new GradeScoresDTO(decimal(daily), decimal(midterm), decimal(experiment),
                decimal(finalterm));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Map<Long, GradeScoresDTO> candidateScores(GradeImportPreviewDTO preview) {
        Map<Long, GradeScoresDTO> scores = new LinkedHashMap<>();
        for (GradeRowInputDTO row : preview.getCandidate().getRows()) {
            scores.put(Long.parseLong(row.getEnrollmentId()), row.getScores());
        }
        return scores;
    }

    private static GradeScoresDTO scores(Map<Long, GradeScoresDTO> scores, long enrollmentId) {
        GradeScoresDTO value = scores.get(enrollmentId);
        require(value != null, "the candidate must contain student " + enrollmentId);
        return value;
    }

    /** 逐项比较（{@link GradeScoresDTO} 没有 equals）：88.5 与 88.50 是同一个分数。 */
    private static boolean same(GradeScoresDTO left, GradeScoresDTO right) {
        return left != null && right != null
                && same(left.getDailyScore(), right.getDailyScore())
                && same(left.getMidtermScore(), right.getMidtermScore())
                && same(left.getExperimentScore(), right.getExperimentScore())
                && same(left.getFinaltermScore(), right.getFinaltermScore());
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right;
        return left.compareTo(right) == 0;
    }

    private static String text(GradeScoresDTO scores) {
        return scores.getDailyScore() + "/" + scores.getMidtermScore() + "/"
                + scores.getExperimentScore() + "/" + scores.getFinaltermScore();
    }

    private static Map<Integer, List<GradeImportRowIssueDTO>> issuesByRow(GradeImportPreviewDTO preview) {
        Map<Integer, List<GradeImportRowIssueDTO>> byRow = new LinkedHashMap<>();
        for (GradeImportRowIssueDTO issue : preview.getIssues()) {
            byRow.computeIfAbsent(issue.getRowNumber(), key -> new ArrayList<>()).add(issue);
        }
        return byRow;
    }

    private static GradeImportRowIssueDTO issueWithField(
            Map<Integer, List<GradeImportRowIssueDTO>> byRow, int rowNumber, String field) {
        for (GradeImportRowIssueDTO issue : byRow.getOrDefault(rowNumber, List.of())) {
            if (field.equals(issue.getField())) {
                return issue;
            }
        }
        throw new AssertionError("row " + rowNumber + " must carry a " + field + " issue, saw "
                + byRow.getOrDefault(rowNumber, List.of()));
    }

    private static boolean close(Double actual, double expected) {
        return actual != null && Math.abs(actual - expected) < 0.005;
    }

    // ------------------------------------------------------------------ fixtures

    private static void insertFixtures() throws Exception {
        StringBuilder users = new StringBuilder("INSERT INTO tbl_user(UID,name,password,salt,role,"
                + "college,major) VALUES('" + ADMIN + "','Tgie984 Admin',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',0,'Administration','Registrar'),('"
                + TEACHER + "','Tgie984 Teacher A',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',1,'Engineering','Professor'),('"
                + OTHER_TEACHER + "','Tgie984 Teacher B',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',1,'Engineering','Professor')");
        for (int index = 1; index <= MAIN_STUDENTS; index++) {
            users.append(",('").append(studentUid(index)).append("','Tgie984 Student ")
                    .append(String.format("%02d", index)).append("',"
                            + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                            + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',2,'Engineering','CS')");
        }
        for (int index = 1; index <= EXPORT_STUDENTS; index++) {
            users.append(",('").append(exportUid(index)).append("','Tgie984 Student ")
                    .append(String.format("%03d", index)).append("',"
                            + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                            + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',2,'Engineering','CS')");
        }
        execute(users.toString());
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(" + COURSE_MAIN + ",'TGIE984A','Teacher Grade Import"
                + " E2E',3.00,48,1,'ACTIVE'),(" + COURSE_EXPORT + ",'TGIE984B','Teacher Grade Import"
                + " Export E2E',2.00,32,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFFERING_MAIN + ",'TGIE984-A'," + COURSE_MAIN
                + "," + YEAR + "," + SEMESTER + ",40,2),(" + OFFERING_EXPORT + ",'TGIE984-B',"
                + COURSE_EXPORT + "," + YEAR + "," + SEMESTER + ",120,2)");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES(" + OFFERING_MAIN
                + ",'" + TEACHER + "',0),(" + OFFERING_EXPORT + ",'" + TEACHER + "',0)");
        StringBuilder enrollments = new StringBuilder("INSERT INTO enrollment(enrollment_id,"
                + "offering_id,course_id,academic_year,semester,uid,status,select_time,drop_time)"
                + " VALUES");
        for (int index = 1; index <= MAIN_STUDENTS; index++) {
            if (index > 1) enrollments.append(',');
            enrollments.append('(').append(ENROLL_MAIN_FIRST + index - 1).append(',')
                    .append(OFFERING_MAIN).append(',').append(COURSE_MAIN).append(',').append(YEAR)
                    .append(',').append(SEMESTER).append(",'").append(studentUid(index))
                    .append("',2,'2027-02-01 00:00:00',NULL)");
        }
        for (int index = 1; index <= EXPORT_STUDENTS; index++) {
            // chk_enrollment_drop_time：退课行必须带退课时间，正常行必须没有。
            boolean dropped = index == DROPPED_INDEX;
            enrollments.append(",(").append(ENROLL_EXPORT_FIRST + index - 1).append(',')
                    .append(OFFERING_EXPORT).append(',').append(COURSE_EXPORT).append(',').append(YEAR)
                    .append(',').append(SEMESTER).append(",'").append(exportUid(index)).append("',")
                    .append(dropped ? 3 : 2).append(",'2027-02-01 00:00:00',")
                    .append(dropped ? "'2027-03-01 00:00:00'" : "NULL").append(')');
        }
        execute(enrollments.toString());
    }

    /** 删除只按本测试自己的 UID 前缀与 id 区间进行。 */
    private static void cleanup() throws Exception {
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid='" + ADMIN + "'");
        execute("DELETE FROM teacher_course_operation_log WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_grade_change_log WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_grade_draft_item WHERE offering_id BETWEEN 984200 AND 984299");
        execute("DELETE FROM teacher_grade_book WHERE offering_id BETWEEN 984200 AND 984299");
        execute("DELETE FROM grade WHERE enrollment_id BETWEEN 984300 AND 984599");
        execute("DELETE FROM grade_submission_item WHERE submission_id IN (SELECT submission_id"
                + " FROM grade_submission WHERE offering_id BETWEEN 984200 AND 984299)");
        execute("DELETE FROM grade_submission WHERE offering_id BETWEEN 984200 AND 984299");
        execute("DELETE FROM enrollment WHERE offering_id BETWEEN 984200 AND 984299");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 984200 AND 984299");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 984200 AND 984299");
        execute("DELETE FROM course WHERE course_id BETWEEN 984100 AND 984199");
        execute("DELETE FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'");
    }

    // ------------------------------------------------------------------- snapshots

    /** 该教学班草稿明细的规范文本：确认之前与之后逐字比较。 */
    private static String draftSnapshot() throws Exception {
        return text("SELECT COALESCE(GROUP_CONCAT(CONCAT(enrollment_id,'|',COALESCE(daily_score,'-'),"
                + "'|',COALESCE(midterm_score,'-'),'|',COALESCE(experiment_score,'-'),'|',"
                + "COALESCE(finalterm_score,'-')) ORDER BY enrollment_id SEPARATOR ',')"
                + ",'') FROM teacher_grade_draft_item WHERE offering_id=" + OFFERING_MAIN);
    }

    /** 本测试选课记录范围内已发布的成绩：审批之前必须逐字不变。 */
    private static String publishedSnapshot() throws Exception {
        return text("SELECT COALESCE(GROUP_CONCAT(CONCAT(enrollment_id,'|',COALESCE(daily_score,'-'),"
                + "'|',COALESCE(midterm_score,'-'),'|',COALESCE(experiment_score,'-'),'|',"
                + "COALESCE(finalterm_score,'-'),'|',COALESCE(score,'-'),'|',"
                + "COALESCE(grade_point,'-'),'|',is_published) ORDER BY enrollment_id SEPARATOR ',')"
                + ",'') FROM grade WHERE enrollment_id BETWEEN 984300 AND 984599");
    }

    /** 审批之后：五位学生的四项组成、总评、绩点与发布标志都必须与导入的内容一致。 */
    private static void requirePublishedGrades() throws Exception {
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id BETWEEN " + ENROLL_MAIN_FIRST
                        + " AND " + (ENROLL_MAIN_FIRST + MAIN_STUDENTS - 1) + " AND is_published=1")
                        == MAIN_STUDENTS,
                "the approval must publish every imported student");
        String[] dalies = {"88.50", "95.00", "100.00", "90.00", "70.00"};
        String[] midterms = {"61.00", "91.00", "100.00", "91.00", "80.00"};
        String[] finalterms = {"63.00", "73.00", "83.00", "93.00", "90.00"};
        // 40/20/–/40：总评 = 0.4·平时 + 0.2·期中 + 0.4·期末。
        String[] totals = {"72.80", "85.40", "93.20", "91.40", "80.00"};
        String[] points = {"2.0", "3.5", "4.5", "4.0", "3.0"};
        for (int index = 0; index < MAIN_STUDENTS; index++) {
            require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id="
                            + (ENROLL_MAIN_FIRST + index) + " AND daily_score=" + dalies[index]
                            + " AND midterm_score=" + midterms[index] + " AND experiment_score IS NULL"
                            + " AND finalterm_score=" + finalterms[index] + " AND score=" + totals[index]
                            + " AND grade_point=" + points[index] + " AND is_published=1"
                            + " AND publish_time IS NOT NULL") == 1,
                    "student " + (index + 1) + " must be published with the imported components,"
                            + " total " + totals[index] + " and point " + points[index]);
        }
    }

    // ------------------------------------------------------------------- helpers

    private static String studentUid(int index) {
        return PREFIX + "s" + index;
    }

    private static String exportUid(int index) {
        return PREFIX + "e" + String.format("%03d", index);
    }

    private static List<String> exportUids() {
        List<String> uids = new ArrayList<>();
        for (int index = 1; index <= EXPORT_STUDENTS; index++) {
            uids.add(exportUid(index));
        }
        return uids;
    }

    private static String op(int value) {
        return String.format("98400000-0000-0000-0000-%012d", value);
    }

    private static void awaitRunning(Server server) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!server.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        require(server.isRunning(), "the real server must start");
    }

    private static void awaitPartialFile(Path directory) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (tempDirectoryFiles(directory).stream()
                    .anyMatch(path -> path.getFileName().toString().endsWith(".part"))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the server never started writing the in-flight upload");
    }

    private static List<Path> tempDirectoryFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            return new ArrayList<>(paths.toList());
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean tempDirectoryIsEmpty(Path directory) {
        return tempDirectoryFiles(directory).isEmpty();
    }

    private static boolean noPartialFile(Path directory) {
        return tempDirectoryFiles(directory).stream()
                .noneMatch(path -> path.getFileName().toString().endsWith(".part"));
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Windows 上仍被句柄占用的文件交给系统临时目录回收。
                }
            }
        } catch (IOException ignored) {
            // 清理是尽力而为。
        }
    }

    private static void requireTestDatabase() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = DBUtil.class.getClassLoader()
                .getResourceAsStream("resources/db.properties")) {
            require(stream != null, "db.properties is unavailable on the runtime classpath");
            properties.load(stream);
        }
        String url = properties.getProperty("db.url");
        require(url != null && !url.isBlank(), "db.url is not configured");
        String raw = url.startsWith("jdbc:") ? url.substring(5) : url;
        String path = URI.create(raw).getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database),
                "Refusing teacher grade import e2e: the JDBC URL must target the guarded schema");
        require(TEST_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing teacher grade import e2e outside the guarded schema");
    }

    private static void requireCode(Message response, MessageCode expected, String what) {
        require(response.getCode() == expected,
                what + " must be " + expected + ", saw " + response.getCode() + " ("
                        + response.getMessage() + ")");
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            String value = rows.getString(1);
            return value == null ? "" : value;
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 最小真实 Socket 客户端：一行一条 JSON，带真实登录 token。 */
    private static final class JsonLineClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private final int port;

        private JsonLineClient(int port) throws IOException {
            this.port = port;
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(20_000);
            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private int getPort() {
            return port;
        }

        private String login(String uid, String role) throws IOException {
            Message captcha = request("user", "get_captcha", null, Map.of());
            requireCode(captcha, MessageCode.SUCCESS, "get captcha for " + uid);
            // 先落到 Object：getData 是泛型方法，直接内联进 String.valueOf 会让编译器挑中
            // valueOf(char[]) 重载并推断出 char[]，运行期就是一个 ClassCastException。
            Object captchaIdValue = captcha.getData("captchaId");
            String captchaId = String.valueOf(captchaIdValue);
            Message response = request("user", "login", null,
                    Map.of("cardNo", uid, "password", PASSWORD, "role", role, "captchaId", captchaId,
                            "captchaCode", CaptchaTestBridge.codeFor(captchaId)));
            requireCode(response, MessageCode.SUCCESS, "login for " + uid);
            Object token = response.getData("token");
            require(token instanceof String value && !value.isBlank(), "login must supply a token");
            return (String) token;
        }

        private Message request(String module, String action, String token,
                Map<String, Object> data) throws IOException {
            Message request = new Message(MessageType.REQUEST, module, action);
            request.setToken(token);
            if (data != null) {
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    request.putData(entry.getKey(), entry.getValue());
                }
            }
            writer.write(GSON.toJson(request));
            writer.newLine();
            writer.flush();
            String line = reader.readLine();
            require(line != null, "server must answer " + module + "." + action);
            return GSON.fromJson(line, Message.class);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
