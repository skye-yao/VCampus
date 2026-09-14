package service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;

/**
 * 教师端只读课程查询的客户端契约。
 *
 * <p>与 {@code TeacherCourseQueryService} 同名，但去掉 uid 参数：教师身份只由服务端从有效
 * Session token 解析，客户端不携带也不再伪造归属信息。方法名与响应键一一对应。
 */
public interface TeacherCourseService {
    CompletableFuture<List<CourseTermDTO>> listTerms();

    CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
            int academicYear, int semester, String query, int page, int size);

    CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId);

    CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
            String offeringId, String query, Integer enrollmentStatus, int page, int size);

    CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(String offeringId);
}
