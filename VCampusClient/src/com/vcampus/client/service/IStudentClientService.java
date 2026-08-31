package com.vcampus.client.service;
import java.util.function.Consumer;
import com.vcampus.common.entity.*;import com.vcampus.common.protocol.Message;import com.vcampus.common.vo.StudentReviewVO;
public interface IStudentClientService {
 void queryOverview(Consumer<Message> callback); void submitChangeRequest(StudentChangeRequest request,Consumer<Message> callback); void queryMyRequests(Consumer<Message> callback); void cancelChangeRequest(long requestId,Consumer<Message> callback);
 void listStudents(Consumer<Message> callback); void queryStudentOverview(String studentId,Consumer<Message> callback); void listPendingRequests(Consumer<Message> callback); void queryChangeRequest(long requestId,Consumer<Message> callback); void reviewChangeRequest(StudentReviewVO review,Consumer<Message> callback);
 void updateStudentByAdmin(Student student,Consumer<Message> callback); void addAward(StudentAward award,Consumer<Message> callback); void updateAward(StudentAward award,Consumer<Message> callback); void deleteAward(long id,Consumer<Message> callback); void addAid(StudentAid aid,Consumer<Message> callback); void updateAid(StudentAid aid,Consumer<Message> callback); void deleteAid(long id,Consumer<Message> callback);
}
