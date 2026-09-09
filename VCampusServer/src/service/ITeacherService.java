package service;

import entity.*;
import enums.StudentChangeStatus;
import protocol.LockRequest;
import session.UserSession;
import vo.TeacherOverviewVO;
import java.sql.SQLException;
import java.util.List;

/** Teacher information business operations. */
public interface ITeacherService {
    void cancel(String UID,long requestId)throws SQLException;
    void authorizeLock(UserSession user,LockRequest proof)throws SQLException;
    TeacherOverviewVO queryByUID(String UID)throws SQLException;
    TeacherOverviewVO queryByTeacherId(String id)throws SQLException;
    List<Teacher> listTeachers()throws SQLException;
    List<TeacherChangeRequest> listAll()throws SQLException;
    TeacherChangeRequest queryRequest(long id)throws SQLException;
    long submit(String UID,TeacherChangeRequest r)throws SQLException;
    void review(long id,StudentChangeStatus result,String reviewer,String note)throws SQLException;
    boolean updateByAdmin(Teacher t)throws SQLException;
    boolean addWorkExperience(String UID,TeacherWorkExperience value)throws SQLException;
    boolean updateWorkExperience(String UID,TeacherWorkExperience value)throws SQLException;
    boolean deleteWorkExperience(String UID,long id)throws SQLException;
    boolean addFamilyMember(String UID,TeacherFamilyMember value)throws SQLException;
    boolean updateFamilyMember(String UID,TeacherFamilyMember value)throws SQLException;
    boolean deleteFamilyMember(String UID,long id)throws SQLException;
}
