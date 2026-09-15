package vo;

import entity.StudentAid;
import entity.StudentAward;

import java.io.Serializable;
import java.util.List;

/**
 * 批量添加奖励/资助的请求载荷。
 * <p>{@code operationId} 由客户端生成（UUID），重试同一批操作时必须复用同一个值，
 * 服务端据此做幂等去重（见 {@code tblStudentInformationBatch}）。
 */
public record StudentBatchRequest(
        String operationId,
        List<String> studentIds,
        StudentAward award,
        StudentAid aid
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
