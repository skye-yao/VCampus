package exception;

import vo.StudentBatchResult;

public final class StudentBatchConflictException extends IllegalStateException {
    private final StudentBatchResult result;
    public StudentBatchConflictException(StudentBatchResult result) {
        super("本批次未保存，请处理名单中的冲突后重试");
        this.result = result;
    }
    public StudentBatchResult getResult() { return result; }
}
