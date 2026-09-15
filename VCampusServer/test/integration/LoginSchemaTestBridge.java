package integration;

import util.DBUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Supplies the latest main-branch login dependency in the protected course test schema. */
final class LoginSchemaTestBridge {
    private static final String TEST_DATABASE = "virtual_campus_course_test";

    private LoginSchemaTestBridge() {
    }

    static void ensureBankAccountTable() throws SQLException {
        try (Connection connection = DBUtil.getConnection()) {
            if (!TEST_DATABASE.equals(connection.getCatalog())) {
                throw new AssertionError("refusing login fixture outside " + TEST_DATABASE);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS tbl_bank_account ("
                        + "account_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "user_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL UNIQUE,"
                        + "balance DECIMAL(12,2) NOT NULL DEFAULT 10000.00) ENGINE=InnoDB");
                statement.execute("ALTER TABLE tbl_bank_account MODIFY user_id VARCHAR(32)"
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL");
            }
        }
    }
}
