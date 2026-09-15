package service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dto.course.teacher.TeacherFileTicketDTO;
import network.SocketClient;
import session.ClientSession;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端文件短连接传输的线协议、完整性核对与取消。
 *
 * <p>本测试不依赖服务端：它自己起一个端口 0 的 {@link ServerSocket} 当对端，按服务端
 * {@code CourseFileConnection} 的同一套线协议应答。元数据字段名在两处各写一遍，正是这一层最容易
 * 漂移，所以这里逐个字段钉住（并断言“只有这五个字段”）。
 *
 * <p>覆盖：上传/下载闭环、长度与摘要三类失败（本地不符、下载摘要不符、下载截断）、服务端 ERROR
 * 回执的透传、取消时关闭短连接，以及失败后目标文件与临时文件都不留痕。
 */
public final class SocketTeacherFileTransportTest {
    private static final String TOKEN = "teacher-file-token";
    private static final String HOST = "127.0.0.1";
    private static final byte[] CONTENT =
            "学号,姓名,平时成绩\n00005678,张三,95\n".getBytes(StandardCharsets.UTF_8);

    private SocketTeacherFileTransportTest() {
    }

    public static void main(String[] args) throws Exception {
        SocketClient.getInstance().init(HOST, 0);
        ClientSession.getInstance().login("teacher-file", "教师", TOKEN, null);
        Path workspace = Files.createTempDirectory("vcampus-file-transport-test-");
        try {
            uploadRoundTrip(workspace);
            uploadSurfacesTheServerReason(workspace);
            uploadRefusesAMismatchedLocalFile(workspace);
            downloadRoundTrip(workspace);
            downloadRejectsAMismatchedDigest(workspace);
            downloadRejectsATruncatedFile(workspace);
            cancelClosesTheShortConnection(workspace);
        } finally {
            ClientSession.getInstance().logout();
            deleteRecursively(workspace);
        }
        System.out.println("Socket teacher file transport test passed.");
    }

    /** 上传闭环：元数据是线协议契约本身，载荷是原始字节。 */
    private static void uploadRoundTrip(Path workspace) throws Exception {
        Path file = workspace.resolve("成绩导入.xlsx");
        Files.write(file, CONTENT);
        String digest = sha256(CONTENT);
        AtomicReference<JsonObject> seenMetadata = new AtomicReference<>();
        AtomicReference<byte[]> seenPayload = new AtomicReference<>();

        Peer peer = new Peer(socket -> {
            DataInputStream in = input(socket);
            JsonObject metadata = readMetadata(in);
            seenMetadata.set(metadata);
            byte[] payload = new byte[metadata.get("size").getAsInt()];
            in.readFully(payload);
            seenPayload.set(payload);
            writeResponse(output(socket), "OK", "");
        });
        try {
            transport().upload(ticket(peer.port(), TeacherFileTicketDTO.DIRECTION_UPLOAD,
                    CONTENT.length, digest), file).get(15, TimeUnit.SECONDS);
            peer.await();
        } finally {
            peer.close();
        }

        JsonObject metadata = seenMetadata.get();
        require(metadata != null, "the upload must send metadata first");
        require(metadata.keySet().equals(Set.of("ticket", "token", "direction", "size", "sha256")),
                "the metadata must carry exactly the agreed fields, saw " + metadata.keySet());
        require("ticket-UPLOAD".equals(metadata.get("ticket").getAsString()),
                "the ticket id must travel verbatim");
        require(TOKEN.equals(metadata.get("token").getAsString()),
                "the short connection must carry the session token");
        require("UPLOAD".equals(metadata.get("direction").getAsString()),
                "an upload must declare the upload direction");
        require(metadata.get("size").getAsLong() == CONTENT.length,
                "the declared size must be the file length");
        require(digest.equals(metadata.get("sha256").getAsString()),
                "the declared digest must be the file digest");
        require(Arrays.equals(seenPayload.get(), CONTENT),
                "the payload must be the raw file bytes, never base64");
    }

    /** 服务端回执里的原因必须原样透传，不能被压成通用错误。 */
    private static void uploadSurfacesTheServerReason(Path workspace) throws Exception {
        Path file = workspace.resolve("rejected.xlsx");
        Files.write(file, CONTENT);
        Peer peer = new Peer(socket -> {
            DataInputStream in = input(socket);
            readMetadata(in);
            in.readFully(new byte[CONTENT.length]);
            writeResponse(output(socket), "ERROR", "文件内容校验失败");
        });
        try {
            expectFailure(transport().upload(ticket(peer.port(), TeacherFileTicketDTO.DIRECTION_UPLOAD,
                    CONTENT.length, sha256(CONTENT)), file), "文件内容校验失败");
            peer.await();
        } finally {
            peer.close();
        }
    }

    /** 本地文件与票据不符：一个字节都不该上网，短连接也不该被打开。 */
    private static void uploadRefusesAMismatchedLocalFile(Path workspace) throws Exception {
        Path file = workspace.resolve("tampered.xlsx");
        Files.write(file, CONTENT);
        Peer peer = new Peer(socket -> {
            throw new AssertionError("a mismatched local file must never open a short connection");
        }, 1000);
        try {
            expectFailure(transport().upload(ticket(peer.port(), TeacherFileTicketDTO.DIRECTION_UPLOAD,
                    CONTENT.length, sha256("other".getBytes(StandardCharsets.UTF_8))), file),
                    "摘要不一致");
            peer.requireNoConnection();
        } finally {
            peer.close();
        }
    }

    /** 下载闭环：目标文件同目录写临时文件，核对通过后才落到用户选定路径。 */
    private static void downloadRoundTrip(Path workspace) throws Exception {
        Path target = workspace.resolve("名单.xlsx");
        Peer peer = new Peer(socket -> {
            DataInputStream in = input(socket);
            readMetadata(in);
            DataOutputStream out = output(socket);
            out.write(CONTENT);
            out.flush();
            writeResponse(out, "OK", "");
        });
        try {
            transport().download(ticket(peer.port(), TeacherFileTicketDTO.DIRECTION_DOWNLOAD,
                    CONTENT.length, sha256(CONTENT)), target).get(15, TimeUnit.SECONDS);
            peer.await();
        } finally {
            peer.close();
        }
        require(Files.exists(target), "a verified download must land on the target path");
        require(Arrays.equals(Files.readAllBytes(target), CONTENT),
                "the downloaded file must match the server bytes");
        requireNoTemporaryFile(workspace);
    }

    /** 下载内容摘要不符：目标文件不得被创建，临时文件必须被删除。 */
    private static void downloadRejectsAMismatchedDigest(Path workspace) throws Exception {
        Path target = workspace.resolve("corrupted.xlsx");
        byte[] tampered = Arrays.copyOf(CONTENT, CONTENT.length);
        tampered[0] = (byte) 'X';
        Peer peer = new Peer(socket -> {
            DataInputStream in = input(socket);
            readMetadata(in);
            DataOutputStream out = output(socket);
            out.write(tampered);
            out.flush();
            writeResponse(out, "OK", "");
        });
        try {
            expectFailure(transport().download(ticket(peer.port(),
                    TeacherFileTicketDTO.DIRECTION_DOWNLOAD, CONTENT.length, sha256(CONTENT)),
                    target), "下载内容校验失败");
            peer.await();
        } finally {
            peer.close();
        }
        require(!Files.exists(target), "a mismatched download must never replace the target file");
        requireNoTemporaryFile(workspace);
    }

    /** 下载被截断：提前 EOF 必须失败，目标文件与临时文件都不留。 */
    private static void downloadRejectsATruncatedFile(Path workspace) throws Exception {
        Path target = workspace.resolve("truncated.xlsx");
        Peer peer = new Peer(socket -> {
            DataInputStream in = input(socket);
            readMetadata(in);
            DataOutputStream out = output(socket);
            out.write(CONTENT, 0, 5);
            out.flush();
            // 服务端在发送过程中断开：既没有余下的字节，也没有回执。
        });
        try {
            expectFailure(transport().download(ticket(peer.port(),
                    TeacherFileTicketDTO.DIRECTION_DOWNLOAD, CONTENT.length, sha256(CONTENT)),
                    target), "中断");
            peer.await();
        } finally {
            peer.close();
        }
        require(!Files.exists(target), "a truncated download must never create the target file");
        requireNoTemporaryFile(workspace);
    }

    /** 取消（离开上传页）：Future 立即结束，短连接随即关闭。 */
    private static void cancelClosesTheShortConnection(Path workspace) throws Exception {
        Path file = workspace.resolve("cancelled.xlsx");
        Files.write(file, CONTENT);
        CountDownLatch metadataReceived = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        Peer peer = new Peer(socket -> {
            DataInputStream in = input(socket);
            readMetadata(in);
            metadataReceived.countDown();
            try {
                while (in.read() >= 0) {
                    // 一直读到对端关闭：取消必须表现为关闭短连接，而不是把线程留在阻塞读上。
                }
            } catch (IOException closed) {
                // 对端关闭连接，正是取消的预期结果。
            }
            connectionClosed.countDown();
        });
        try {
            CompletableFuture<Void> future = transport().upload(ticket(peer.port(),
                    TeacherFileTicketDTO.DIRECTION_UPLOAD, CONTENT.length, sha256(CONTENT)), file);
            require(metadataReceived.await(15, TimeUnit.SECONDS),
                    "the upload must reach the peer before it is cancelled");
            require(!future.isDone(), "the transfer must still be in flight before cancel");
            require(future.cancel(true), "cancel must win the race while the peer withholds the ack");
            require(connectionClosed.await(15, TimeUnit.SECONDS),
                    "cancelling must close the short connection");
            require(future.isCancelled(), "the cancelled future must report cancelled");
        } finally {
            peer.close();
        }
    }

    private static TeacherFileTicketDTO ticket(int port, String direction, long length,
            String digest) {
        return new TeacherFileTicketDTO("ticket-" + direction, direction, port, length,
                5L * 1024 * 1024, digest, "2026-09-16T00:02:00Z");
    }

    private static SocketTeacherFileTransport transport() {
        return new SocketTeacherFileTransport();
    }

    private static void expectFailure(CompletableFuture<Void> future, String reason)
            throws Exception {
        try {
            future.get(15, TimeUnit.SECONDS);
            throw new AssertionError("the transfer must fail with 「" + reason + "」");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            require(cause != null && cause.getMessage() != null
                            && cause.getMessage().contains(reason),
                    "the failure must report 「" + reason + "」, saw " + cause);
        }
    }

    private static DataInputStream input(Socket socket) throws IOException {
        return new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    }

    private static DataOutputStream output(Socket socket) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    private static JsonObject readMetadata(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] metadata = new byte[length];
        in.readFully(metadata);
        return JsonParser.parseString(new String(metadata, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void writeResponse(DataOutputStream out, String status, String message)
            throws IOException {
        Map<String, Object> response = new HashMap<>();
        response.put("status", status);
        response.put("message", message);
        byte[] body = new Gson().toJson(response).getBytes(StandardCharsets.UTF_8);
        out.writeInt(body.length);
        out.write(body);
        out.flush();
    }

    private static void requireNoTemporaryFile(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            List<Path> leftovers = paths.filter(path -> path.getFileName().toString()
                    .contains(".part")).toList();
            require(leftovers.isEmpty(),
                    "a failed download must delete its temporary file, saw " + leftovers);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** 测试内的对端：只处理一条短连接，异常与断言都带回主线程。 */
    @FunctionalInterface
    private interface PeerHandler {
        void handle(Socket socket) throws Exception;
    }

    private static final class Peer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Peer(PeerHandler handler) throws IOException {
            this(handler, 0);
        }

        Peer(PeerHandler handler, int acceptTimeoutMillis) throws IOException {
            this.server = new ServerSocket(0, 1, InetAddress.getByName(HOST));
            this.server.setSoTimeout(acceptTimeoutMillis);
            this.thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(15000);
                    handler.handle(socket);
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "file-transport-peer");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        void await() throws Exception {
            thread.join(TimeUnit.SECONDS.toMillis(20));
            require(!thread.isAlive(), "the peer handler did not finish");
            Throwable thrown = failure.get();
            if (thrown instanceof AssertionError assertion) {
                throw assertion;
            }
            if (thrown != null) {
                throw new AssertionError(thrown);
            }
        }

        /** 证明客户端根本没有打开短连接：接受超时是唯一可接受的结局。 */
        void requireNoConnection() throws Exception {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            require(!thread.isAlive(), "the peer handler did not finish");
            require(failure.get() instanceof SocketTimeoutException,
                    "no short connection may be opened, saw " + failure.get());
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // 对端已经结束：关闭是幂等的。
            }
        }
    }
}
