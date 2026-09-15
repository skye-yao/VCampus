package handler;

import dto.course.CourseMutationResultDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CourseTermDTO;
import dto.course.SelectionStateDTO;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.CourseQueryService;
import service.CourseSelectionService;
import session.SessionManager;
import session.UserSession;

import java.util.List;

public final class CourseMutationHandlerTest {
    private static final String OPERATION_ID = "10000000-0000-0000-0000-000000000001";

    private CourseMutationHandlerTest() {
    }

    public static void main(String[] args) {
        FakeSelectionService selection = new FakeSelectionService();
        CourseHandler handler = new CourseHandler(new FakeQueryService(), selection);
        SessionManager sessions = SessionManager.getInstance();
        UserSession student = sessions.createSession("student-alpha", "学生");
        try {
            for (String action : List.of("addToPlan", "removeFromPlan", "selectOffering",
                    "dropOffering")) {
                selection.mode = Mode.SUCCESS;
                Message request = mutation(action, student.getToken(), OPERATION_ID);
                request.setSender("attacker");
                request.putData("uid", "student-beta");
                Message response = handler.handle(request);
                require(response.getCode() == MessageCode.SUCCESS,
                        action + " must return success");
                require(response.getData().size() == 1
                                && response.getData().containsKey("result"),
                        action + " must use result response key");
                require("student-alpha".equals(selection.lastUid),
                        action + " must use the authenticated UID");
                require(response.getUID().equals(request.getUID()),
                        action + " must preserve the current transport request UID");
            }

            Message malformedUuid = mutation("addToPlan", student.getToken(), "not-a-uuid");
            selection.mode = Mode.BAD_OPERATION;
            require(handler.handle(malformedUuid).getCode() == MessageCode.BAD_REQUEST,
                    "malformed operation ID must be bad request");

            selection.mode = Mode.NOT_FOUND;
            require(handler.handle(mutation("addToPlan", student.getToken(), OPERATION_ID))
                            .getCode() == MessageCode.NOT_FOUND,
                    "missing offering must be not found");

            selection.mode = Mode.CONFLICT;
            require(handler.handle(mutation("selectOffering", student.getToken(), OPERATION_ID))
                            .getCode() == MessageCode.CONFLICT,
                    "business conflict must use conflict code");

            selection.mode = Mode.DATABASE;
            Message database = handler.handle(
                    mutation("dropOffering", student.getToken(), OPERATION_ID));
            require(database.getCode() == MessageCode.ERROR,
                    "database failure must use error code");
            require(database.getMessage() != null
                            && !database.getMessage().contains("SELECT secret"),
                    "database details must not leak");

            selection.mode = Mode.SUCCESS;
            Message first = mutation("addToPlan", student.getToken(), OPERATION_ID);
            Message second = mutation("addToPlan", student.getToken(), OPERATION_ID);
            require(!first.getUID().equals(second.getUID()), "test requests must be distinct");
            require(handler.handle(first).getUID().equals(first.getUID())
                            && handler.handle(second).getUID().equals(second.getUID()),
                    "replay payload must still use each request UID");
        } finally {
            sessions.removeSession(student.getToken());
        }
        System.out.println("Course mutation handler test passed.");
    }

    private static Message mutation(String action, String token, String operationId) {
        Message request = new Message(MessageType.REQUEST, "course", action);
        request.setToken(token);
        request.putData("academicYear", 2026);
        request.putData("semester", 2);
        request.putData("offeringId", "2001");
        request.putData("operationId", operationId);
        return request;
    }

    private enum Mode { SUCCESS, BAD_OPERATION, NOT_FOUND, CONFLICT, DATABASE }

    private static final class FakeSelectionService extends CourseSelectionService {
        private String lastUid;
        private Mode mode = Mode.SUCCESS;

        @Override
        public CourseMutationResultDTO addToPlan(String uid, CourseTermDTO term,
                                                 long offeringId, String operationId) {
            return result(uid, operationId);
        }

        @Override
        public CourseMutationResultDTO removeFromPlan(String uid, CourseTermDTO term,
                                                      long offeringId, String operationId) {
            return result(uid, operationId);
        }

        @Override
        public CourseMutationResultDTO selectOffering(String uid, CourseTermDTO term,
                                                      long offeringId, String operationId) {
            return result(uid, operationId);
        }

        @Override
        public CourseMutationResultDTO dropOffering(String uid, CourseTermDTO term,
                                                    long offeringId, String operationId) {
            return result(uid, operationId);
        }

        private CourseMutationResultDTO result(String uid, String operationId) {
            lastUid = uid;
            switch (mode) {
                case BAD_OPERATION -> throw new OperationConflictException("bad operation");
                case NOT_FOUND -> throw new NotFoundException("missing");
                case CONFLICT -> throw new ConflictException("conflict");
                case DATABASE -> throw new exception.DatabaseException("SELECT secret FROM users");
                case SUCCESS -> { }
            }
            CoursePlanSnapshotDTO snapshot = new CoursePlanSnapshotDTO(
                    new CourseTermDTO(2026, 2, "term"), List.of(), List.of(), List.of());
            return new CourseMutationResultDTO(operationId, null,
                    SelectionStateDTO.PLANNED, "PLANNED", "ok", snapshot);
        }
    }

    private static final class FakeQueryService extends CourseQueryService {
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
