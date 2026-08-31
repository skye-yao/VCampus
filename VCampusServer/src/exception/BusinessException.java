package exception;

/**
 * 业务异常
 *
 * 用于表示业务逻辑处理过程中出现的异常情况。
 * 例如：用户不存在、余额不足、图书不可借阅、无权限等。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造业务异常
     *
     * @param message 异常描述
     */
    public BusinessException(String message) {
        super(message);
    }

    /**
     * 构造业务异常
     *
     * @param message 异常描述
     * @param cause 原始异常
     */
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}