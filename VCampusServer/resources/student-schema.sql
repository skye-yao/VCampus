CREATE TABLE IF NOT EXISTS tblStudent (
 studentId VARCHAR(20) PRIMARY KEY, userId VARCHAR(32) NOT NULL UNIQUE, name VARCHAR(50) NOT NULL, gender VARCHAR(10) NOT NULL,
 politicalStatus VARCHAR(30) NOT NULL, nationality VARCHAR(30) NOT NULL, idType VARCHAR(30) NOT NULL, idNumber VARCHAR(30) NOT NULL,
 idIssueDate DATE NOT NULL, birthDate DATE NOT NULL, nativePlace VARCHAR(100) NOT NULL, householdType VARCHAR(30) NOT NULL,
 birthPlace VARCHAR(100) NOT NULL, sourcePlace VARCHAR(100) NOT NULL, registeredResidence VARCHAR(150) NOT NULL,
 leagueMember BOOLEAN NOT NULL DEFAULT FALSE, leagueJoinDate DATE NULL, partyMember BOOLEAN NOT NULL DEFAULT FALSE, partyJoinDate DATE NULL,
 healthStatus VARCHAR(50) NOT NULL, studentCategory VARCHAR(30) NOT NULL, registered BOOLEAN NOT NULL DEFAULT TRUE,
 inSchool BOOLEAN NOT NULL DEFAULT TRUE, studentStatus VARCHAR(30) NOT NULL, campus VARCHAR(50), grade VARCHAR(20) NOT NULL,
 college VARCHAR(100) NOT NULL, major VARCHAR(100) NOT NULL, className VARCHAR(100) NOT NULL, educationLevel VARCHAR(30) NOT NULL,
 trainingMode VARCHAR(30) NOT NULL, schoolingLength INT NOT NULL, counselorName VARCHAR(50), counselorPhone VARCHAR(30),
 candidateCategory VARCHAR(30) NOT NULL, admissionDate DATE NOT NULL, admissionMethod VARCHAR(50) NOT NULL,
 graduationSchool VARCHAR(150) NOT NULL, middleSchoolClass VARCHAR(100), middleSchoolTeacher VARCHAR(50), telephone VARCHAR(30) NOT NULL,
 mobile VARCHAR(30) NOT NULL, email VARCHAR(100) NOT NULL, qq VARCHAR(30), wechat VARCHAR(50), campusAddress VARCHAR(150),
 emergencyContact VARCHAR(50) NOT NULL, emergencyPhone VARCHAR(30) NOT NULL
);
CREATE TABLE IF NOT EXISTS tblStudentChangeRequest (requestId BIGINT PRIMARY KEY AUTO_INCREMENT,studentId VARCHAR(20) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'PENDING',submitTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,reviewerId VARCHAR(32),reviewTime DATETIME,reviewRemark VARCHAR(255),FOREIGN KEY(studentId) REFERENCES tblStudent(studentId));
CREATE TABLE IF NOT EXISTS tblStudentChangeItem (itemId BIGINT PRIMARY KEY AUTO_INCREMENT,requestId BIGINT NOT NULL,fieldName VARCHAR(50) NOT NULL,oldValue VARCHAR(255),newValue VARCHAR(255) NOT NULL,FOREIGN KEY(requestId) REFERENCES tblStudentChangeRequest(requestId) ON DELETE CASCADE);
CREATE TABLE IF NOT EXISTS tblStudentAward (awardId BIGINT PRIMARY KEY AUTO_INCREMENT,studentId VARCHAR(20) NOT NULL,awardName VARCHAR(100) NOT NULL,awardType VARCHAR(50) NOT NULL,awardLevel VARCHAR(50) NOT NULL,awardDate DATE NOT NULL,organization VARCHAR(100),description VARCHAR(255),FOREIGN KEY(studentId) REFERENCES tblStudent(studentId));
CREATE TABLE IF NOT EXISTS tblStudentAid (aidId BIGINT PRIMARY KEY AUTO_INCREMENT,studentId VARCHAR(20) NOT NULL,aidName VARCHAR(100) NOT NULL,aidType VARCHAR(50) NOT NULL,amount DECIMAL(10,2),aidDate DATE NOT NULL,provider VARCHAR(100),status VARCHAR(20) NOT NULL DEFAULT 'PENDING',description VARCHAR(255),FOREIGN KEY(studentId) REFERENCES tblStudent(studentId),CHECK(amount IS NULL OR amount>=0));
CREATE INDEX idx_student_request_status ON tblStudentChangeRequest(status,submitTime);
CREATE INDEX idx_student_award_student ON tblStudentAward(studentId);
CREATE INDEX idx_student_aid_student ON tblStudentAid(studentId);
