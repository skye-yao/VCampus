package service;

import entity.*;
import protocol.Message;
import vo.StudentReviewVO;
import java.util.function.Consumer;

/** Student information client operations. */
public interface IStudentClientService {
    void onEditLeaseLost(Runnable callback);
    void releaseEditLease();
    void dispose();
    void beginEdit(String id,Consumer<Message> callback);
    void endEdit(String id,Consumer<Message> callback);
    void queryOverview(Consumer<Message> c);
    void submitChangeRequest(StudentChangeRequest r,Consumer<Message> c);
    void queryMyRequests(Consumer<Message> c);
    void cancelChangeRequest(long id,Consumer<Message> c);
    void listStudents(Consumer<Message> c);
    void queryStudentOverview(String id,Consumer<Message> c);
    void listPendingRequests(Consumer<Message> c);
    void queryChangeRequest(long id,Consumer<Message> c);
    void reviewChangeRequest(StudentReviewVO r,Consumer<Message> c);
    void updateStudentByAdmin(Student s,Student original,Consumer<Message> c);
    void addAward(StudentAward a,Consumer<Message> c);
    void updateAward(StudentAward a,Consumer<Message> c);
    void deleteAward(long id,Consumer<Message> c);
    void addAid(StudentAid a,Consumer<Message> c);
    void updateAid(StudentAid a,Consumer<Message> c);
    void deleteAid(long id,Consumer<Message> c);
    void addExperience(StudentExperience x,Consumer<Message> c);
    void addFamilyMember(StudentFamilyMember x,Consumer<Message> c);
    void updateExperience(StudentExperience x,Consumer<Message> c);
    void deleteExperience(long id,Consumer<Message> c);
    void updateFamilyMember(StudentFamilyMember x,Consumer<Message> c);
    void deleteFamilyMember(long id,Consumer<Message> c);
}
