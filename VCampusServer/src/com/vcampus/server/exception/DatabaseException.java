package com.vcampus.server.exception;

/**
 * 数据库异常
 *
 * 用于表示数据库连接、SQL执行、事务处理等过程中出现的异常。
 */
public class DatabaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造数据库异常
     *
     * @param message 异常描述
     */
    public DatabaseException(String message) {
        super(message);
    }

    /**
     * 构造数据库异常
     *
     * @param message 异常描述
     * @param cause 原始数据库异常
     */
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}