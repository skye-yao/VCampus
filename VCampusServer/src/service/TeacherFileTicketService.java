package service;

import dto.course.teacher.TeacherFileTicketDTO;
import session.UserSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 教师成绩 Excel 文件短连接的票据签发与兑换。
 *
 * <p>Excel 文件不进业务 JSON/TCP：业务请求（{@code beginGradeUpload} 等）在这里签发一张短时票据，
 * 文件本身随后在独立端口上用「长度前缀元数据 + 原始字节」协议传输。票据绑定签发时的 Session、
 * 教师 UID、教学班、用途（上传/下载）与精确长度，2 分钟过期且只能领取一次；重试必须重新申请。
 *
 * <p>所有临时文件由本服务在自建临时目录下自行命名，绝不使用客户端提供的路径——客户端文件名只
 * 参与扩展名的白名单化。临时目录在 {@link #close()} 时整体删除，过期票据的临时文件由后台清理器
 * 定期回收，因此停服后不会留下半截上传或生成的表格。
 *
 * <p>时钟可注入：过期用例必须能在不等待真实两分钟的情况下推进时间。
 */
public final class TeacherFileTicketService implements AutoCloseable {

    /** 单个文件（模板、名单导出、待解析工作簿）的字节上限，与设计第 9 节的 5 MiB 一致。 */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    /** 票据有效期：2 分钟。 */
    public static final long TICKET_TTL_MILLIS = 2L * 60 * 1000;

    /** 过期票据与其临时文件的回收周期。 */
    private static final long PURGE_INTERVAL_SECONDS = 30;

    private static final String TEMP_DIRECTORY_PREFIX = "vcampus-course-file-";
    private static final String DEFAULT_SUFFIX = ".xlsx";

    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();
    private final Path tempDirectory;
    private final Clock clock;
    private final ScheduledExecutorService purgeExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile int port;

    public TeacherFileTicketService(int filePort) {
        this(filePort, Clock.systemUTC());
    }

    public TeacherFileTicketService(int filePort, Clock clock) {
        if (filePort < 0 || filePort > 65535) {
            throw new IllegalArgumentException("文件端口无效: " + filePort);
        }
        this.port = filePort;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        try {
            this.tempDirectory = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX);
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建文件传输临时目录", failure);
        }
        this.purgeExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "course-file-purge");
            thread.setDaemon(true);
            return thread;
        });
        this.purgeExecutor.scheduleWithFixedDelay(this::purgeExpired,
                PURGE_INTERVAL_SECONDS, PURGE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 文件监听器真正绑定端口后回填实际端口：测试用 0 绑定随机端口，客户端拿到的必须是真实端口。
     */
    public void bindPort(int boundPort) {
        this.port = boundPort;
    }

    /** 临时目录，供后续任务写入生成的模板/名单文件；调用方不得把客户端路径拼进来。 */
    public Path getTempDirectory() {
        return tempDirectory;
    }

    /**
     * 在临时目录里预留一个不存在的文件路径：文件名由服务端生成，客户端文件名只贡献扩展名。
     */
    public Path newTempFile(String clientFileName) {
        requireOpen();
        return tempDirectory.resolve(UUID.randomUUID() + suffixOf(clientFileName));
    }

    /**
     * 签发上传票据。长度与摘要由业务请求声明，兑换时按声明精确读取并核对，超出 5 MiB 直接拒绝。
     */
    public TeacherFileTicketDTO issueUpload(UserSession session, String offeringId,
            long expectedRevision, String fileName, long byteLength, String sha256) {
        requireOpen();
        requireSession(session);
        if (byteLength < 1 || byteLength > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件大小必须为 1 字节至 5 MiB");
        }
        String digest = requireDigest(sha256);
        Path target = newTempFile(fileName);
        return issue(session, TeacherFileTicketDTO.DIRECTION_UPLOAD, offeringId, expectedRevision,
                byteLength, digest, target);
    }

    /**
     * 签发下载票据：长度与摘要取自服务端文件本身，客户端据此核对下载结果。
     */
    public TeacherFileTicketDTO issueDownload(UserSession session, String offeringId, Path file) {
        requireOpen();
        requireSession(session);
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("待下载文件不存在");
        }
        long length;
        try {
            length = Files.size(file);
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取待下载文件大小", failure);
        }
        if (length < 1 || length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件大小必须为 1 字节至 5 MiB");
        }
        return issue(session, TeacherFileTicketDTO.DIRECTION_DOWNLOAD, offeringId, 0L, length,
                sha256(file), file);
    }

    /**
     * 兑换票据：校验归属会话、有效期、用途与长度后单次消费。
     *
     * <p>校验全部通过才摘除票据，因此并发或重复兑换只有一次成功，而无关的伪造输入不会烧掉别人
     * 手里那张票。任何失败都抛出 {@link IllegalArgumentException}，消息可直接回给客户端。
     */
    public Ticket claim(String ticket, UserSession session, String direction) {
        requireOpen();
        if (ticket == null || ticket.isBlank()) {
            throw new IllegalArgumentException("文件票据无效");
        }
        Entry entry = tickets.get(ticket);
        if (entry == null) {
            throw new IllegalArgumentException("文件票据无效或已被使用");
        }
        if (entry.expiresAtMillis <= clock.millis()) {
            throw new IllegalArgumentException("文件票据已过期，请重新申请");
        }
        if (session == null) {
            throw new IllegalArgumentException("登录会话已失效，请重新登录");
        }
        if (!entry.ticket.teacherUid().equals(session.getUsername())
                || !entry.sessionToken.equals(session.getToken())) {
            throw new IllegalArgumentException("文件票据不属于当前登录会话");
        }
        if (!entry.ticket.direction().equals(direction)) {
            throw new IllegalArgumentException("文件票据用途不匹配");
        }
        if (!tickets.remove(ticket, entry)) {
            throw new IllegalArgumentException("文件票据无效或已被使用");
        }
        return entry.ticket;
    }

    /**
     * 文件内容摘要，小写十六进制；供服务端核验上传内容与生成下载票据使用。
     */
    public static String sha256(Path file) {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream source = Files.newInputStream(file)) {
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取文件内容", failure);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行环境缺少 SHA-256", failure);
        }
    }

    /**
     * 停服清理：幂等地停止回收线程并删除整个临时目录（半截上传、已发送的模板一并清理）。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        purgeExecutor.shutdownNow();
        tickets.clear();
        deleteRecursively(tempDirectory);
    }

    private TeacherFileTicketDTO issue(UserSession session, String direction, String offeringId,
            long expectedRevision, long byteLength, String sha256, Path file) {
        if (offeringId == null || offeringId.isBlank()) {
            throw new IllegalArgumentException("offeringId 不能为空");
        }
        String id = UUID.randomUUID().toString();
        long expiresAtMillis = clock.millis() + TICKET_TTL_MILLIS;
        Ticket claimed = new Ticket(direction, session.getUsername(), offeringId, expectedRevision,
                byteLength, sha256, file);
        tickets.put(id, new Entry(session.getToken(), expiresAtMillis, claimed));
        return new TeacherFileTicketDTO(id, direction, port, byteLength, MAX_FILE_BYTES, sha256,
                Instant.ofEpochMilli(expiresAtMillis).toString());
    }

    private void purgeExpired() {
        long now = clock.millis();
        for (Map.Entry<String, Entry> entry : tickets.entrySet()) {
            if (entry.getValue().expiresAtMillis > now) {
                continue;
            }
            if (tickets.remove(entry.getKey(), entry.getValue())) {
                deleteQuietly(entry.getValue().ticket.path());
            }
        }
    }

    private static void requireSession(UserSession session) {
        if (session == null) {
            throw new IllegalArgumentException("登录会话已失效，请重新登录");
        }
    }

    private static String requireDigest(String sha256) {
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 必须为 64 位十六进制摘要");
        }
        return sha256.toLowerCase(Locale.ROOT);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("文件服务已停止");
        }
    }

    /** 客户端文件名只用来推断扩展名：非字母数字一律丢弃，绝不参与路径拼接。 */
    private static String suffixOf(String clientFileName) {
        if (clientFileName == null) {
            return DEFAULT_SUFFIX;
        }
        int dot = clientFileName.lastIndexOf('.');
        if (dot < 0 || dot == clientFileName.length() - 1) {
            return DEFAULT_SUFFIX;
        }
        String extension = clientFileName.substring(dot + 1);
        if (extension.length() > 8 || !extension.matches("[A-Za-z0-9]+")) {
            return DEFAULT_SUFFIX;
        }
        return "." + extension.toLowerCase(Locale.ROOT);
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(TeacherFileTicketService::deleteQuietly);
        } catch (IOException ignored) {
            // 目录已经消失或无法枚举：清理是尽力而为，绝不因清理失败而让停服报错。
        }
    }

    /** 尽力删除临时文件：文件连接用它清理半截上传，服务本身也用它回收过期票据的落点。 */
    public static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件已被领取方删除或仍在使用：留待整体目录清理。
        }
    }

    /**
     * 已兑换的票据内容：文件连接据此决定读还是写、读多少、核对什么。
     *
     * @param direction 上传或下载
     * @param teacherUid 签发时的教师 UID
     * @param offeringId 票据绑定的教学班（导入确认时重新核验归属）
     * @param expectedRevision 签发时的草稿版本
     * @param byteLength 本次传输的精确字节数
     * @param sha256 小写十六进制内容摘要
     * @param path 服务端自建临时文件（上传为落点，下载为来源）
     */
    public record Ticket(String direction, String teacherUid, String offeringId,
            long expectedRevision, long byteLength, String sha256, Path path) {
    }

    private record Entry(String sessionToken, long expiresAtMillis, Ticket ticket) {
    }
}
