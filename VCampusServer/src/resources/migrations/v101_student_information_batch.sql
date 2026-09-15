-- Run against the existing VCampus database. This migration only creates the receipt table.
CREATE TABLE IF NOT EXISTS tblStudentInformationBatch (
    operationId CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    adminId VARCHAR(32) NOT NULL,
    operationType VARCHAR(10) NOT NULL,
    payloadDigest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    successCount INT,
    resultJson TEXT,
    createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completedAt DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
