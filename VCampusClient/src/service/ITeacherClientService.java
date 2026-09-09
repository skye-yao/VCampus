package service;

import entity.*;
import protocol.Message;
import vo.TeacherReviewVO;
import java.util.function.Consumer;

/** Teacher information client operations. */
public interface ITeacherClientService {
    void cancel(long id,Consumer<Message> c);
    void overview(Consumer<Message> c);
    void submit(TeacherChangeRequest r,Consumer<Message> c);
    void list(Consumer<Message> c);
    void query(String id,Consumer<Message> c);
    void update(Teacher t,Consumer<Message> c);
    void reviews(Consumer<Message> c);
    void reviewQuery(long id,Consumer<Message> c);
    void review(TeacherReviewVO v,Consumer<Message> c);
    void addWorkExperience(TeacherWorkExperience x,Consumer<Message> c);
    void updateWorkExperience(TeacherWorkExperience x,Consumer<Message> c);
    void deleteWorkExperience(long id,Consumer<Message> c);
    void addFamilyMember(TeacherFamilyMember x,Consumer<Message> c);
    void updateFamilyMember(TeacherFamilyMember x,Consumer<Message> c);
    void deleteFamilyMember(long id,Consumer<Message> c);
    void onEditLeaseLost(Runnable callback);
    void releaseEditLease();
    void dispose();
    void beginEdit(String teacherId, Consumer<Message> callback);
    void endEdit(String teacherId, Consumer<Message> callback);
}
