package dto.course.teacher;

/**
 * 一次文件短连接的票据，由业务 TCP（{@code courseTeacher}）签发，在独立文件端口上兑换。
 *
 * <p>Excel 文件绝不进业务 JSON：业务请求只带回这张票据，客户端再按 {@code port} 打开一条短连接，
 * 用长度前缀元数据声明 {@code ticket}/方向/字节数与 SHA-256，之后才传输原始字节。
 *
 * <p>{@code direction} 是 {@link #DIRECTION_UPLOAD} 或 {@link #DIRECTION_DOWNLOAD}；{@code byteLength}
 * 是本次要传输的精确字节数（上传＝客户端声明并核对过的本地文件长度，下载＝服务端临时文件长度）；
 * {@code maxBytes} 是协议上限；{@code sha256} 是小写十六进制摘要，
 * 两个方向都必须核对；{@code expiresAt} 是 ISO-8601 瞬时字符串，票据 2 分钟有效且只能领取一次。
 *
 * <p>票据只服务于当前登录 Session：文件连接上的 token 必须与签发时的会话一致，其他教师无法兑换。
 */
public final class TeacherFileTicketDTO {

    /** 客户端 → 服务端：上传一份待解析的工作簿。 */
    public static final String DIRECTION_UPLOAD = "UPLOAD";

    /** 服务端 → 客户端：下载服务端生成的模板或名单导出。 */
    public static final String DIRECTION_DOWNLOAD = "DOWNLOAD";

    private final String ticket;
    private final String direction;
    private final int port;
    private final long byteLength;
    private final long maxBytes;
    private final String sha256;
    private final String expiresAt;

    public TeacherFileTicketDTO(String ticket, String direction, int port, long byteLength,
            long maxBytes, String sha256, String expiresAt) {
        this.ticket = ticket;
        this.direction = direction;
        this.port = port;
        this.byteLength = byteLength;
        this.maxBytes = maxBytes;
        this.sha256 = sha256;
        this.expiresAt = expiresAt;
    }

    /** 获取 Ticket。 */
    public String getTicket() {
        return ticket;
    }

    /** 获取 Direction。 */
    public String getDirection() {
        return direction;
    }

    /** 获取 Port。 */
    public int getPort() {
        return port;
    }

    /** 获取 ByteLength。 */
    public long getByteLength() {
        return byteLength;
    }

    /** 获取 MaxBytes。 */
    public long getMaxBytes() {
        return maxBytes;
    }

    /** 获取 Sha256。 */
    public String getSha256() {
        return sha256;
    }

    /** 获取 ExpiresAt。 */
    public String getExpiresAt() {
        return expiresAt;
    }
}
