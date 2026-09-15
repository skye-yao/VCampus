package network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dto.course.teacher.TeacherFileTicketDTO;
import handler.TeacherCourseHandler;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.TeacherCourseQueryService;
import service.TeacherFileTicketService;
import session.SessionManager;
import session.UserSession;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 文件短连接的线协议、票据校验矩阵与关停生命周期。
 *
 * <p>用真实 Socket 打到端口 0 上绑定的监听器，逐条断言设计第 9 节点名的失败模式：无效/过期/他人
 * token、错误方向、超限、截断、SHA 不符、重复领取、取消与停服。每个失败都必须同时证明服务端没有
 * 留下半截文件——一张没写完的工作簿留在临时目录里比直接报错更糟。
 *
 * <p>票据是单次领取的，所以失败矩阵里的每一行都申请自己的票据；复用同一张票只能证明“已被使用”，
 * 证明不了这一行想证明的规则。
 *
 * <p>最后一段走业务接缝：{@code beginGradeUpload} 签发的票据必须能被文件端口直接兑换。签发与兑换
 * 共用同一个票据服务实例（生产装配也是这样），所以“签发的票据”和“文件端认的票据”不会各说各话。
 */
public final class CourseFileServerTest {
    private static final String TEACHER_A = "teacher-file-a";
    private static final String TEACHER_B = "teacher-file-b";
    private static final String STUDENT = "student-file-a";
    private static final String OFFERING_A = "9007199254740993";
    private static final String UPLOAD = TeacherFileTicketDTO.DIRECTION_UPLOAD;
    private static final String DOWNLOAD = TeacherFileTicketDTO.DIRECTION_DOWNLOAD;
    private static final byte[] CONTENT =
            "学号,姓名,平时成绩\n00005678,张三,95\n".getBytes(StandardCharsets.UTF_8);

    private CourseFileServerTest() {
    }

    public static void main(String[] args) throws Exception {
        SessionManager sessions = SessionManager.getInstance();
        UserSession teacherA = sessions.createSession(TEACHER_A, "教师");
        UserSession teacherB = sessions.createSession(TEACHER_B, "教师");
        UserSession student = sessions.createSession(STUDENT, "学生");
        MutableClock clock = new MutableClock();
        TeacherFileTicketService tickets = new TeacherFileTicketService(0, clock);
        CourseFileServer server = new CourseFileServer(tickets, 0);
        try {
            server.start();
            require(server.isRunning(), "the file listener must report running after start");
            require(server.getPort() > 0, "a port 0 listener must expose the bound port");

            uploadRoundTrip(server, tickets, teacherA);
            downloadRoundTrip(server, tickets, teacherA);
            rejectedClaims(server, tickets, teacherA, teacherB, student, clock);
            handlerIssuesTicketsTheFilePortAccepts(server, tickets, teacherA, student);
            stopClosesListenerLiveConnectionsAndTempFiles(server, tickets, teacherA);
        } finally {
            server.stop();
            tickets.close();
            sessions.removeSession(teacherA.getToken());
            sessions.removeSession(teacherB.getToken());
            sessions.removeSession(student.getToken());
        }
        System.out.println("Course file server test passed.");
    }

    /** 上传闭环：服务端自己命名落盘，票据上的端口就是真正监听的端口。 */
    private static void uploadRoundTrip(CourseFileServer server, TeacherFileTicketService tickets,
            UserSession teacher) throws IOException {
        TeacherFileTicketDTO ticket = tickets.issueUpload(teacher, OFFERING_A, 7L, "成绩导入.XLSX",
                CONTENT.length, sha256(CONTENT));
        require(UPLOAD.equals(ticket.getDirection()), "an upload ticket must be marked as upload");
        require(ticket.getByteLength() == CONTENT.length,
                "the ticket must carry the declared byte length");
        require(ticket.getMaxBytes() == TeacherFileTicketService.MAX_FILE_BYTES,
                "the ticket must advertise the 5 MiB protocol limit");
        require(ticket.getPort() == server.getPort(),
                "the ticket must carry the port the listener actually bound");
        require(ticket.getExpiresAt() != null && !ticket.getExpiresAt().isBlank(),
                "the ticket must carry an expiry instant");

        String metadata = metadata(ticket.getTicket(), teacher.getToken(), UPLOAD, CONTENT.length,
                sha256(CONTENT));
        Response response = exchange(server.getPort(), metadata, CONTENT, false);
        require(response.ok(), "a valid upload must be acknowledged, saw " + response.message());

        List<Path> stored = tempFiles(tickets.getTempDirectory());
        require(stored.size() == 1,
                "a successful upload must leave exactly one server-named file, saw " + stored);
        Path uploaded = stored.get(0);
        require(uploaded.getFileName().toString().endsWith(".xlsx"),
                "the server name must keep only the whitelisted extension, saw " + uploaded);
        require(!uploaded.getFileName().toString().contains("成绩导入"),
                "the client file name must never reach the server file system");
        require(Arrays.equals(Files.readAllBytes(uploaded), CONTENT),
                "the stored bytes must equal the uploaded bytes");
        requireNoPartialFile(tickets.getTempDirectory());

        // 单次领取：同一张票再用一次必须失败。
        expectRejected(server.getPort(), metadata, null, "文件票据无效或已被使用");
    }

    /** 下载闭环：客户端按票据长度读满，服务端文件与票据摘要一致。 */
    private static void downloadRoundTrip(CourseFileServer server, TeacherFileTicketService tickets,
            UserSession teacher) throws IOException {
        Path generated = tickets.newTempFile("template.xlsx");
        require(!Files.exists(generated), "a reserved temp path must not exist before it is written");
        Files.write(generated, CONTENT);
        TeacherFileTicketDTO ticket = tickets.issueDownload(teacher, OFFERING_A, generated);
        require(DOWNLOAD.equals(ticket.getDirection()),
                "a download ticket must be marked as download");
        require(ticket.getByteLength() == CONTENT.length
                        && ticket.getSha256().equals(sha256(CONTENT)),
                "the download ticket must describe the server file exactly");

        String metadata = metadata(ticket.getTicket(), teacher.getToken(), DOWNLOAD, CONTENT.length,
                ticket.getSha256());
        byte[] received = download(server.getPort(), metadata, CONTENT.length);
        require(Arrays.equals(received, CONTENT),
                "the download must return the server file byte for byte");
        expectRejected(server.getPort(), metadata, null, "文件票据无效或已被使用");
    }

    /**
     * 票据校验矩阵：每一行都是一次真实连接，断言错误文案命中预期，并且服务端不留下半截文件。
     */
    private static void rejectedClaims(CourseFileServer server, TeacherFileTicketService tickets,
            UserSession teacherA, UserSession teacherB, UserSession student, MutableClock clock)
            throws Exception {
        int port = server.getPort();
        Path generated = tickets.newTempFile("roster.xlsx");
        Files.write(generated, CONTENT);
        int filesBefore = tempFiles(tickets.getTempDirectory()).size();

        expectRejected(port, metadata("no-such-ticket", teacherA.getToken(), UPLOAD,
                CONTENT.length, sha256(CONTENT)), null, "文件票据无效或已被使用");

        // 会话无效：token 解析不出 Session，或元数据里根本没有 token。
        TeacherFileTicketDTO forUnknownSession = issue(tickets, teacherA);
        expectRejected(port, metadata(forUnknownSession.getTicket(), "expired-token", UPLOAD,
                CONTENT.length, sha256(CONTENT)), null, "登录会话已失效");
        expectRejected(port, metadata(forUnknownSession.getTicket(), null, UPLOAD,
                CONTENT.length, sha256(CONTENT)), null, "文件元数据无效");

        // 他人 token：票据绑定签发时的 Session 与教师，换一个教师的 token 不能兑换。
        expectRejected(port, metadata(issue(tickets, teacherA).getTicket(), teacherB.getToken(),
                UPLOAD, CONTENT.length, sha256(CONTENT)), null, "文件票据不属于当前登录会话");
        expectRejected(port, metadata(issue(tickets, teacherA).getTicket(), student.getToken(),
                UPLOAD, CONTENT.length, sha256(CONTENT)), null, "文件票据不属于当前登录会话");

        // 错误方向：下载票被当成上传用，票据在方向核对失败时不会被烧掉。
        TeacherFileTicketDTO download = tickets.issueDownload(teacherA, OFFERING_A, generated);
        String asUpload = metadata(download.getTicket(), teacherA.getToken(), UPLOAD,
                CONTENT.length, sha256(CONTENT));
        expectRejected(port, asUpload, null, "文件票据用途不匹配");
        byte[] downloaded = download(port, metadata(download.getTicket(), teacherA.getToken(),
                DOWNLOAD, CONTENT.length, download.getSha256()), CONTENT.length);
        require(Arrays.equals(downloaded, CONTENT),
                "a ticket rejected for the wrong direction must stay claimable in its own direction");

        // 超限：声明超过 5 MiB 的载荷在任何字节被读取之前就拒绝。
        expectRejected(port, uploadMetadata(tickets, teacherA,
                TeacherFileTicketService.MAX_FILE_BYTES + 1, sha256(CONTENT)), null,
                "文件大小超出上限");
        // 长度与票据不符：既不多读也不少读。
        expectRejected(port, uploadMetadata(tickets, teacherA, CONTENT.length + 1, sha256(CONTENT)),
                null, "文件长度与票据不一致");
        // 摘要与票据不符。
        expectRejected(port, uploadMetadata(tickets, teacherA, CONTENT.length,
                        sha256("other-content".getBytes(StandardCharsets.UTF_8))), null,
                "文件摘要与票据不一致");

        // 截断：声明 14 字节只发 6 字节就半关写端，必须失败并清理 .part。
        expectRejected(port, uploadMetadata(tickets, teacherA, CONTENT.length, sha256(CONTENT)),
                Arrays.copyOf(CONTENT, 6), "文件上传在收到全部字节前中断");

        // 内容被替换：长度对、摘要不对。
        byte[] tampered = Arrays.copyOf(CONTENT, CONTENT.length);
        tampered[0] = (byte) 'X';
        expectRejected(port, uploadMetadata(tickets, teacherA, CONTENT.length, sha256(CONTENT)),
                tampered, "文件内容校验失败");

        // 过期：推进注入的时钟越过 2 分钟有效期。
        TeacherFileTicketDTO expired = issue(tickets, teacherA);
        clock.advance(Duration.ofSeconds(121));
        expectRejected(port, metadata(expired.getTicket(), teacherA.getToken(), UPLOAD,
                CONTENT.length, sha256(CONTENT)), null, "文件票据已过期，请重新申请");
        clock.advance(Duration.ofSeconds(-121));

        // 元数据长度本身越界：既不能为 0，也不能超过 4096。
        expectRejectedRaw(port, 0, new byte[0]);
        expectRejectedRaw(port, CourseFileConnection.MAX_METADATA_BYTES + 1,
                new byte[CourseFileConnection.MAX_METADATA_BYTES + 1]);

        requireNoPartialFile(tickets.getTempDirectory());
        require(tempFiles(tickets.getTempDirectory()).size() == filesBefore,
                "a rejected transfer must not add any file, saw "
                        + tempFiles(tickets.getTempDirectory()));

        // 签发端同样拒绝超限、空文件与非法摘要，绝不为它们开票；教学班为空也不行。
        expectIssueRejected(() -> tickets.issueUpload(teacherA, OFFERING_A, 0L, "f.xlsx",
                        TeacherFileTicketService.MAX_FILE_BYTES + 1, sha256(CONTENT)),
                "文件大小必须为 1 字节至 5 MiB");
        expectIssueRejected(() -> tickets.issueUpload(teacherA, OFFERING_A, 0L, "g.xlsx", 0,
                        sha256(CONTENT)), "文件大小必须为 1 字节至 5 MiB");
        expectIssueRejected(() -> tickets.issueUpload(teacherA, OFFERING_A, 0L, "h.xlsx",
                        CONTENT.length, "not-a-digest"), "sha256 必须为 64 位十六进制摘要");
        expectIssueRejected(() -> tickets.issueUpload(teacherA, " ", 0L, "i.xlsx", CONTENT.length,
                sha256(CONTENT)), "offeringId 不能为空");
        expectIssueRejected(() -> tickets.issueDownload(null, OFFERING_A, generated),
                "登录会话已失效，请重新登录");
        expectIssueRejected(() -> tickets.issueDownload(teacherA, OFFERING_A,
                        generated.resolveSibling("missing.xlsx")),
                "待下载文件不存在");
    }

    /**
     * 业务接缝：{@code beginGradeUpload} 只回一张票据，而这张票据必须能被文件端口直接兑换。
     *
     * <p>同时钉住签发端的鉴权与形状：身份只来自 Session（伪造 uid/teacherId/sender 一律 BAD_REQUEST）、
     * 非教师 FORBIDDEN、无 token UNAUTHORIZED、超限/坏摘要/非十进制 offeringId 全部 BAD_REQUEST。
     */
    private static void handlerIssuesTicketsTheFilePortAccepts(CourseFileServer server,
            TeacherFileTicketService tickets, UserSession teacher, UserSession student)
            throws IOException {
        // 与生产入口同形：同一个票据服务实例既签发票据，又供文件监听器兑换。
        TeacherCourseHandler handler = new TeacherCourseHandler(new TeacherCourseQueryService(),
                null, null, tickets);
        String digest = sha256(CONTENT);

        Message accepted = handler.handle(uploadRequest(teacher.getToken(), OFFERING_A,
                CONTENT.length, digest));
        require(accepted.getCode() == MessageCode.SUCCESS,
                "a valid upload request must be accepted, saw " + accepted.getMessage());
        Object value = accepted.getData("ticket");
        require(value instanceof TeacherFileTicketDTO,
                "the response must carry the ticket under the `ticket` key, saw " + value);
        TeacherFileTicketDTO ticket = (TeacherFileTicketDTO) value;
        require(UPLOAD.equals(ticket.getDirection()), "the issued ticket must be an upload ticket");
        require(ticket.getPort() == server.getPort(),
                "the issued ticket must point at the running file port");
        require(ticket.getByteLength() == CONTENT.length
                        && digest.equals(ticket.getSha256()),
                "the issued ticket must bind the declared length and digest");

        String metadata = metadata(ticket.getTicket(), teacher.getToken(), UPLOAD,
                CONTENT.length, digest);
        Response response = exchange(server.getPort(), metadata, CONTENT, false);
        require(response.ok(),
                "a business-issued ticket must be redeemable on the file port, saw "
                        + response.message());
        require(tempFiles(tickets.getTempDirectory()).stream()
                        .anyMatch(path -> {
                            try {
                                return Arrays.equals(Files.readAllBytes(path), CONTENT);
                            } catch (IOException unreadable) {
                                throw new AssertionError(unreadable);
                            }
                        }),
                "the redeemed upload must be stored byte for byte");

        require(handler.handle(uploadRequest(null, OFFERING_A, CONTENT.length, digest)).getCode()
                        == MessageCode.UNAUTHORIZED,
                "a missing token must not obtain a file ticket");
        require(handler.handle(uploadRequest(student.getToken(), OFFERING_A, CONTENT.length,
                        digest)).getCode() == MessageCode.FORBIDDEN,
                "a non-teacher must not obtain a file ticket");

        Message forged = uploadRequest(teacher.getToken(), OFFERING_A, CONTENT.length, digest);
        @SuppressWarnings("unchecked")
        Map<String, Object> forgedBody = (Map<String, Object>) forged.getData("request");
        forgedBody.put("uid", TEACHER_B);
        require(handler.handle(forged).getCode() == MessageCode.BAD_REQUEST,
                "a forged uid inside the upload request must be a bad request");

        require(handler.handle(uploadRequest(teacher.getToken(), OFFERING_A,
                        TeacherFileTicketService.MAX_FILE_BYTES + 1, digest)).getCode()
                        == MessageCode.BAD_REQUEST,
                "an upload beyond 5 MiB must be a bad request");
        require(handler.handle(uploadRequest(teacher.getToken(), OFFERING_A, CONTENT.length,
                        "not-a-digest")).getCode() == MessageCode.BAD_REQUEST,
                "a malformed digest must be a bad request");
        require(handler.handle(uploadRequest(teacher.getToken(), null, CONTENT.length, digest))
                        .getCode() == MessageCode.BAD_REQUEST,
                "a missing offeringId must be a bad request");

        Message numericOffering = uploadRequest(teacher.getToken(), 9007199254740993L,
                CONTENT.length, digest);
        require(handler.handle(numericOffering).getCode() == MessageCode.BAD_REQUEST,
                "a numeric offeringId must be rejected, never coerced");
        Message missingBody = new Message(MessageType.REQUEST, "courseTeacher", "beginGradeUpload");
        missingBody.setToken(teacher.getToken());
        require(handler.handle(missingBody).getCode() == MessageCode.BAD_REQUEST,
                "a request without a body must be a bad request");
    }

    private static Message uploadRequest(String token, Object offeringId, long byteLength,
            String sha256) {
        Message request = new Message(MessageType.REQUEST, "courseTeacher", "beginGradeUpload");
        request.setToken(token);
        Map<String, Object> body = new HashMap<>();
        body.put("offeringId", offeringId);
        // 走线与真实报文一致：JSON 里的整数到达服务端时是 double，必须仍被当作精确的 long 解析。
        body.put("expectedRevision", 7.0);
        body.put("fileName", "成绩导入.xlsx");
        body.put("byteLength", (double) byteLength);
        body.put("sha256", sha256);
        request.putData("request", body);
        return request;
    }

    /**
     * 停服：监听器关闭、活跃短连接被断开、临时文件与临时目录被回收，且重复停止不报错。
     */
    private static void stopClosesListenerLiveConnectionsAndTempFiles(CourseFileServer server,
            TeacherFileTicketService tickets, UserSession teacher) throws Exception {
        TeacherFileTicketDTO ticket = issue(tickets, teacher);
        int port = server.getPort();
        Path tempDirectory = tickets.getTempDirectory();

        Socket inFlight = new Socket("127.0.0.1", port);
        inFlight.setSoTimeout(5000);
        try {
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(inFlight.getOutputStream()));
            byte[] metadataJson = metadata(ticket.getTicket(), teacher.getToken(), UPLOAD,
                    CONTENT.length, sha256(CONTENT)).getBytes(StandardCharsets.UTF_8);
            out.writeInt(metadataJson.length);
            out.write(metadataJson);
            out.flush();
            out.write(CONTENT, 0, 4);
            out.flush();
            // 等到服务端确实在处理这条连接（.part 已落地），停服才有可断的对象。
            awaitPartialFile(tempDirectory);
            require(server.isRunning(), "the listener must still be running before stop");

            server.stop();
            server.stop();

            require(!server.isRunning(), "stop must clear the running flag");
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
                "stop must remove the temporary directory holding every transfer file");
        expectConnectFailure(port);
    }

    private static TeacherFileTicketDTO issue(TeacherFileTicketService tickets, UserSession teacher) {
        return tickets.issueUpload(teacher, OFFERING_A, 0L, "case.xlsx", CONTENT.length,
                sha256(CONTENT));
    }

    private static String uploadMetadata(TeacherFileTicketService tickets, UserSession teacher,
            long declaredSize, String declaredSha) {
        return metadata(issue(tickets, teacher).getTicket(), teacher.getToken(), UPLOAD,
                declaredSize, declaredSha);
    }

    /** 一次完整的短连接：元数据长度前缀 + JSON + 可选载荷，然后读回执。 */
    private static Response exchange(int port, String metadata, byte[] payload, boolean halfClose)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            byte[] bytes = metadata.getBytes(StandardCharsets.UTF_8);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(bytes.length);
            out.write(bytes);
            if (payload != null) {
                out.write(payload);
            }
            out.flush();
            if (halfClose) {
                socket.shutdownOutput();
            }
            return readResponse(socket);
        }
    }

    private static byte[] download(int port, String metadata, int expectedLength)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            byte[] bytes = metadata.getBytes(StandardCharsets.UTF_8);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(bytes.length);
            out.write(bytes);
            out.flush();
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            byte[] payload = new byte[expectedLength];
            in.readFully(payload);
            Response response = readResponse(in);
            require(response.ok(),
                    "a valid download must be acknowledged, saw " + response.message());
            return payload;
        }
    }

    private static Response readResponse(Socket socket) throws IOException {
        return readResponse(new DataInputStream(new BufferedInputStream(socket.getInputStream())));
    }

    private static Response readResponse(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] body = new byte[length];
        in.readFully(body);
        JsonObject json = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                .getAsJsonObject();
        return new Response(json.get("status").getAsString(),
                json.get("message") == null ? "" : json.get("message").getAsString());
    }

    private static void expectRejected(int port, String metadata, byte[] payload, String reason)
            throws IOException {
        Response response = exchange(port, metadata, payload, true);
        require(!response.ok(), "the request must be rejected: " + metadata);
        require(response.message().contains(reason),
                "the rejection must report 「" + reason + "」, saw " + response.message());
    }

    /** 元数据长度本身越界：服务端在读取任何内容之前就要用固定文案拒绝。 */
    private static void expectRejectedRaw(int port, int metadataLength, byte[] body)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(metadataLength);
            out.write(body);
            out.flush();
            socket.shutdownOutput();
            Response response = readResponse(socket);
            require(!response.ok() && response.message().contains("invalid metadata length"),
                    "an out-of-range metadata length must be rejected, saw " + response.message());
        }
    }

    private static void expectIssueRejected(Runnable action, String reason) {
        try {
            action.run();
            throw new AssertionError("the issue must be rejected: " + reason);
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains(reason),
                    "the rejection must report 「" + reason + "」, saw " + expected.getMessage());
        }
    }

    private static void expectConnectFailure(int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            throw new AssertionError("the file port must be released after stop, saw " + socket);
        } catch (ConnectException expected) {
            // 端口已释放，正是停服要证明的结果。
        }
    }

    private static String metadata(String ticket, String token, String direction, long size,
            String sha256) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("ticket", ticket);
        fields.put("token", token);
        fields.put("direction", direction);
        fields.put("size", size);
        fields.put("sha256", sha256);
        return new Gson().toJson(fields);
    }

    private static List<Path> tempFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            return new ArrayList<>(paths.toList());
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void requireNoPartialFile(Path directory) {
        require(tempFiles(directory).stream()
                        .noneMatch(path -> path.getFileName().toString().endsWith(".part")),
                "a rejected or finished upload must never leave a .part file behind, saw "
                        + tempFiles(directory));
    }

    private static void awaitPartialFile(Path directory) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean partial = tempFiles(directory).stream()
                    .anyMatch(path -> path.getFileName().toString().endsWith(".part"));
            if (partial) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the server never started writing the in-flight upload");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Response(String status, String message) {
        boolean ok() {
            return "OK".equals(status);
        }
    }

    /** 可推进的时钟：2 分钟有效期不可能靠真实等待来验证。 */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-16T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
