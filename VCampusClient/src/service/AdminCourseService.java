package service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import model.course.admin.ScheduleArrangementView;
import model.course.admin.SchedulePlanView;

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

    // Declared as defaults so the existing final implementations keep compiling until the
    // scheduling transport task wires them to the server.
    default CompletableFuture<List<ScheduleResourceDTO>> listScheduleResources(
            String type, String query) {
        throw new UnsupportedOperationException("listScheduleResources");
    }

    default CompletableFuture<SchedulePlanDTO> loadSchedulePlan(
            int academicYear, int semester) {
        throw new UnsupportedOperationException("loadSchedulePlan");
    }

    default CompletableFuture<List<ScheduleArrangementView>> loadOfferingArrangements(
            String planId, String offeringId) {
        throw new UnsupportedOperationException("loadOfferingArrangements");
    }

    default CompletableFuture<List<ScheduleConflictDTO>> checkArrangement(
            SaveArrangementRequestDTO request) {
        throw new UnsupportedOperationException("checkArrangement");
    }

    default CompletableFuture<AdminOperationResultView<ScheduleArrangementView>> saveArrangement(
            SaveArrangementRequestDTO request) {
        throw new UnsupportedOperationException("saveArrangement");
    }

    default CompletableFuture<AdminOperationResultView<Void>> deleteArrangement(
            String arrangementId, int expectedVersion, String operationId) {
        throw new UnsupportedOperationException("deleteArrangement");
    }

    default CompletableFuture<AdminOperationResultView<SchedulePlanView>> publishSchedulePlan(
            String planId, int expectedRevision, String operationId,
            boolean force, String overrideReason) {
        throw new UnsupportedOperationException("publishSchedulePlan");
    }
}
