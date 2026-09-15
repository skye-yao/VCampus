package dto.course.teacher;

/**
 * 申请上传票据（{@code beginGradeUpload}）的请求体。
 *
 * <p>这是业务 TCP 上的唯一上传入口：客户端只在这里声明要传哪个教学班、基于哪个草稿版本、文件名、
 * 精确字节数与 SHA-256，服务端据此签发一张绑定当前 Session、教师、教学班与长度的单次票据。
 * 文件内容本身不在这个请求里，只走独立的文件短连接。
 *
 * <p>{@code offeringId} 在网络上是十进制字符串（BIGINT 不经 double）；{@code expectedRevision} 是客户端
 * 当前草稿版本，供后续确认导入时核对；{@code fileName} 只用于诊断与临时文件扩展名，服务端绝不用它
 * 作为落盘路径。
 */
public final class TeacherFileUploadRequestDTO {
    private final String offeringId;
    private final long expectedRevision;
    private final String fileName;
    private final long byteLength;
    private final String sha256;

    public TeacherFileUploadRequestDTO(String offeringId, long expectedRevision, String fileName,
            long byteLength, String sha256) {
        this.offeringId = offeringId;
        this.expectedRevision = expectedRevision;
        this.fileName = fileName;
        this.byteLength = byteLength;
        this.sha256 = sha256;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public long getExpectedRevision() {
        return expectedRevision;
    }

    public String getFileName() {
        return fileName;
    }

    public long getByteLength() {
        return byteLength;
    }

    public String getSha256() {
        return sha256;
    }
}
