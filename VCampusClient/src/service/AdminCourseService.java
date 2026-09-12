package service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;

public interface AdminCourseService {
    CompletableFuture<List<AdminCourseView>> listCourses(String query, String status);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(
            CourseEditorRequestDTO request);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(
            CourseEditorRequestDTO request);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
            String courseId, int expectedVersion, String operationId);

    CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
            String courseId, int expectedVersion, String operationId);

    CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId);

    CompletableFuture<AdminOperationResultView<AdminOfferingView>> createOffering(
            OfferingEditorRequestDTO request);

    CompletableFuture<AdminOperationResultView<AdminOfferingView>> updateOffering(
            OfferingEditorRequestDTO request);

    CompletableFuture<AdminOperationResultView<AdminOfferingView>> cancelOffering(
            String offeringId, int expectedVersion, String operationId);

    CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
            String offeringId, int expectedVersion, String operationId);
}
