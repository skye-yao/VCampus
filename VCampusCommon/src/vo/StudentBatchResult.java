package vo;

import java.util.List;

public record StudentBatchResult(String operationId, int successCount, boolean replayed,
                                 List<Conflict> conflicts) {
    public record Conflict(String studentId, String name, String reason) {}
}
