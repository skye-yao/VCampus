package service;

import entity.*;
import enums.StudentChangeStatus;
import protocol.LockRequest;
import session.UserSession;
import vo.StudentOverviewVO;
import java.sql.SQLException;
import java.util.List;

/** Student information business operations. */
public interface IStudentService {
    void authorizeLock(UserSession user, LockRequest proof) throws SQLException;
    StudentOverviewVO queryByUID(String UID) throws SQLException;
    StudentOverviewVO queryByStudentId(String studentId) throws SQLException;
    List<Student> listStudents() throws SQLException;
    List<StudentChangeRequest> listMyRequests(String UID) throws SQLException;
    List<StudentChangeRequest> listPending() throws SQLException;
    StudentChangeRequest queryRequest(long requestId) throws SQLException;
    void cancel(String UID, long requestId) throws SQLException;
    long submit(String UID, StudentChangeRequest request) throws SQLException;
    void review(long requestId, StudentChangeStatus result, String reviewer, String remark) throws SQLException;
    boolean updateByAdmin(String adminId,Student student,Student original) throws SQLException;
    String studentIdForUser(String uid)throws SQLException;
    String recordStudentId(String table,String column,long id)throws SQLException;
    boolean addAward(StudentAward award) throws SQLException;
    boolean updateAward(StudentAward award) throws SQLException;
    boolean deleteAward(long awardId) throws SQLException;
    boolean addAid(StudentAid aid) throws SQLException;
    boolean updateAid(StudentAid aid) throws SQLException;
    boolean deleteAid(long aidId) throws SQLException;
    boolean addExperience(String UID,StudentExperience value)throws SQLException;
    boolean addFamilyMember(String UID,StudentFamilyMember value)throws SQLException;
    boolean updateExperience(String UID,StudentExperience value)throws SQLException;
    boolean deleteExperience(String UID,long id)throws SQLException;
    boolean updateFamilyMember(String UID,StudentFamilyMember value)throws SQLException;
    boolean deleteFamilyMember(String UID,long id)throws SQLException;
}
