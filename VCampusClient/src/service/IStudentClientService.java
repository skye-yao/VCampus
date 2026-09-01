package service;
import java.util.function.Consumer; import entity.*; import protocol.Message; import vo.StudentReviewVO;
public interface IStudentClientService {
 void queryOverview(Consumer<Message> c); void submitChangeRequest(StudentChangeRequest r,Consumer<Message> c); void queryMyRequests(Consumer<Message> c); void cancelChangeRequest(long id,Consumer<Message> c);
 void listStudents(Consumer<Message> c); void queryStudentOverview(String id,Consumer<Message> c); void listPendingRequests(Consumer<Message> c); void queryChangeRequest(long id,Consumer<Message> c); void reviewChangeRequest(StudentReviewVO r,Consumer<Message> c);
 void updateStudentByAdmin(Student s,Consumer<Message> c); void addAward(StudentAward a,Consumer<Message> c); void updateAward(StudentAward a,Consumer<Message> c); void deleteAward(long id,Consumer<Message> c); void addAid(StudentAid a,Consumer<Message> c); void updateAid(StudentAid a,Consumer<Message> c); void deleteAid(long id,Consumer<Message> c);
}
