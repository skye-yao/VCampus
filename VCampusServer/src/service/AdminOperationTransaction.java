package service;

import dao.AdminCourseOperationDAO;
import dto.course.admin.result.AdminOperationResultDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared transaction skeleton for administrator course mutations. One call opens a
 * connection, runs READ_COMMITTED with autoCommit off, locks the aggregate, replays the
 * stored result when the same operationId is retried with the same request, otherwise
 * executes the mutation, writes the operation log row and commits. Every failure rolls back
 * and is never logged; autoCommit is always restored.
 */
final class AdminOperationTransaction {
    private final AdminCourseOperationDAO operationDAO;
    private final Clock clock;
    private final String targetType;
    private final String failureMessage;

    AdminOperationTransaction(AdminCourseOperationDAO operationDAO, Clock clock,
                              String targetType, String failureMessage) {
        this.operationDAO = operationDAO;
        this.clock = clock;
        this.targetType = targetType;
        this.failureMessage = failureMessage;
    }

    <T> AdminOperationResultDTO<T> execute(String adminUid, String operationId, String action,
                                           Object request, Type resultType, Lock lock,
                                           Mutation<T> mutation) {
        validate(adminUid, operationId);
        String digest = operationDAO.digest(action, request);
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                lock.apply(connection);
                AdminCourseOperationDAO.StoredOperation stored = operationDAO.find(
                        connection, adminUid, operationId);
                AdminOperationResultDTO<T> result;
                if (stored != null) {
                    if (!digest.equals(stored.requestDigest())) {
                        throw new IllegalArgumentException("operationId 已用于不同的业务请求");
                    }
                    result = operationDAO.decode(stored.responseJson(), resultType);
                } else {
                    Execution<T> execution = mutation.execute(connection);
                    result = execution.result();
                    operationDAO.insert(connection, adminUid, operationId, action, targetType,
                            Long.toString(execution.targetId()), digest, request,
                            result.getOutcomeCode(), result, clock.instant());
                }
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException failure) {
            throw new DatabaseException(failureMessage, failure);
        }
    }

    static Map<String, Object> targetRequest(long targetId, int expectedVersion) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("targetId", Long.toString(targetId));
        request.put("expectedVersion", expectedVersion);
        return request;
    }

    static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static long parseId(String value, String label) {
        try {
            long id = Long.parseLong(value == null ? "" : value.trim());
            if (id <= 0) throw new IllegalArgumentException(label + " 必须为正整数");
            return id;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(label + " 必须为正整数");
        }
    }

    static int version(int expectedVersion) {
        if (expectedVersion <= 0) throw new IllegalArgumentException("expectedVersion 必须为正整数");
        return expectedVersion;
    }

    static void validate(String adminUid, String operationId) {
        if (adminUid == null || adminUid.isBlank()) throw new IllegalArgumentException("UID 不能为空");
        if (operationId == null || operationId.length() != 36) {
            throw new IllegalArgumentException("operationId 必须是 UUID");
        }
        try {
            if (!UUID.fromString(operationId).toString().equalsIgnoreCase(operationId)) {
                throw new IllegalArgumentException("operationId 必须是 UUID");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("operationId 必须是 UUID");
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    interface Lock {
        void apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    interface Mutation<T> {
        Execution<T> execute(Connection connection) throws SQLException;
    }

    record Execution<T>(AdminOperationResultDTO<T> result, long targetId) {
    }
}
