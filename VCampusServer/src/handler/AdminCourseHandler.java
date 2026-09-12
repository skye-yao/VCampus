package handler;

import com.google.gson.Gson;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.AdminCourseCatalogService;
import service.AdminOfferingService;
import session.SessionManager;
import session.UserSession;

import java.util.Map;
import java.util.Set;

public class AdminCourseHandler {
    private static final String MODULE = "courseAdmin";

    /** 动作登记表里已定义、但由后续计划开放的管理员操作。 */
    private static final Set<String> UNAVAILABLE_ACTIONS = Set.of(
            AdminCourseActions.LIST_SCHEDULE_RESOURCES,
            AdminCourseActions.LOAD_SCHEDULE_PLAN,
            AdminCourseActions.LOAD_OFFERING_ARRANGEMENTS,
            AdminCourseActions.CHECK_ARRANGEMENT,
            AdminCourseActions.SAVE_ARRANGEMENT,
            AdminCourseActions.DELETE_ARRANGEMENT,
            AdminCourseActions.PUBLISH_SCHEDULE_PLAN,
            AdminCourseActions.SEARCH_STUDENTS,
            AdminCourseActions.LIST_OFFERING_STUDENTS,
            AdminCourseActions.PREVIEW_ADMIN_ENROLLMENT,
            AdminCourseActions.ADD_STUDENT_TO_OFFERING,
            AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING,
            AdminCourseActions.LIST_ADJUSTMENT_REQUESTS,
            AdminCourseActions.GET_ADJUSTMENT_REQUEST,
            AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST,
            AdminCourseActions.LIST_GRADE_SUBMISSIONS,
            AdminCourseActions.GET_GRADE_SUBMISSION,
            AdminCourseActions.REVIEW_GRADE_SUBMISSION);

    private final AdminCourseCatalogService catalog;
    private final AdminOfferingService offerings;
    private final Gson gson = new Gson();

    public AdminCourseHandler() {
        this(new AdminCourseCatalogService(), new AdminOfferingService());
    }

    public AdminCourseHandler(AdminCourseCatalogService catalog,
                              AdminOfferingService offerings) {
        this.catalog = catalog;
        this.offerings = offerings;
    }

    public Message handle(Message request) {
        Message response = response(request);
        UserSession session = SessionManager.getInstance().getSession(request.getToken());
        if (session == null) {
            return failure(response, MessageCode.UNAUTHORIZED, "登录会话已失效，请重新登录");
        }
        if (!"管理员".equals(session.getRole())) {
            return failure(response, MessageCode.FORBIDDEN, "仅管理员可以访问课程管理服务");
        }
        String action = request.getAction();
        if (action == null || action.isBlank()) {
            return failure(response, MessageCode.BAD_REQUEST, "Action 不能为空");
        }

        try {
            String uid = session.getUsername();
            switch (action) {
                case AdminCourseActions.LIST_COURSES -> response.putData("courses", catalog.list(
                        optionalText(request, "query"), optionalText(request, "status")));
                case AdminCourseActions.CREATE_COURSE -> mutation(response,
                        catalog.create(uid, payload(request, CourseEditorRequestDTO.class)));
                case AdminCourseActions.UPDATE_COURSE -> mutation(response,
                        catalog.update(uid, payload(request, CourseEditorRequestDTO.class)));
                case AdminCourseActions.ARCHIVE_COURSE -> mutation(response, catalog.archive(uid,
                        decimalId(request, "courseId"), integer(request, "expectedVersion"),
                        text(request, "operationId")));
                case AdminCourseActions.RESTORE_COURSE -> mutation(response, catalog.restore(uid,
                        decimalId(request, "courseId"), integer(request, "expectedVersion"),
                        text(request, "operationId")));
                case AdminCourseActions.LIST_OFFERINGS -> response.putData("offerings",
                        offerings.list(decimalId(request, "courseId")));
                case AdminCourseActions.CREATE_OFFERING -> mutation(response,
                        offerings.create(uid, payload(request, OfferingEditorRequestDTO.class)));
                case AdminCourseActions.UPDATE_OFFERING -> mutation(response,
                        offerings.update(uid, payload(request, OfferingEditorRequestDTO.class)));
                case AdminCourseActions.CANCEL_OFFERING -> mutation(response, offerings.cancel(uid,
                        decimalId(request, "offeringId"), integer(request, "expectedVersion"),
                        text(request, "operationId")));
                case AdminCourseActions.DELETE_DRAFT_OFFERING -> mutation(response,
                        offerings.deleteDraft(uid, decimalId(request, "offeringId"),
                                integer(request, "expectedVersion"), text(request, "operationId")));
                default -> {
                    return failure(response, MessageCode.BAD_REQUEST,
                            UNAVAILABLE_ACTIONS.contains(action)
                                    ? "该管理员操作尚未开放" : "不支持的课程管理操作");
                }
            }
            response.setCode(MessageCode.SUCCESS);
            return response;
        } catch (IllegalArgumentException failure) {
            return failure(response, MessageCode.BAD_REQUEST, failure.getMessage());
        } catch (AdminCourseCatalogService.NotFoundException
                 | AdminOfferingService.NotFoundException failure) {
            return failure(response, MessageCode.NOT_FOUND, "课程资源不存在");
        } catch (AdminCourseCatalogService.ConflictException failure) {
            return conflict(response, failure.getMessage(), failure.getLatest());
        } catch (AdminOfferingService.ConflictException failure) {
            return conflict(response, failure.getMessage(), failure.getLatest());
        } catch (DatabaseException failure) {
            return failure(response, MessageCode.ERROR, "课程管理服务暂不可用");
        } catch (RuntimeException failure) {
            return failure(response, MessageCode.ERROR, "服务端内部错误");
        }
    }

    private static Message response(Message request) {
        Message response = new Message(MessageType.RESPONSE, MODULE, request.getAction());
        response.setUID(request.getUID());
        return response;
    }

    private static Message failure(Message response, MessageCode code, String message) {
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    private static Message mutation(Message response, AdminOperationResultDTO<?> result) {
        response.putData("result", result);
        response.setCode(MessageCode.SUCCESS);
        response.setMessage(result.getMessage());
        return response;
    }

    private static Message conflict(Message response, String message, Object latest) {
        response.setCode(MessageCode.CONFLICT);
        response.setMessage(message);
        if (latest != null) response.putData("latest", latest);
        return response;
    }

    private <T> T payload(Message request, Class<T> type) {
        Object value = request.getData() == null ? null : request.getData().get("request");
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("request 必须为 JSON 对象");
        }
        return gson.fromJson(gson.toJson(value), type);
    }

    private static String optionalText(Message request, String key) {
        Map<String, Object> data = request.getData();
        Object value = data == null ? null : data.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " 必须为字符串");
        }
        return text.isBlank() ? null : text;
    }

    private static int integer(Message request, String key) {
        Object value = data(request, key);
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)
                    || decimal < Integer.MIN_VALUE || decimal > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(key + " 必须为整数");
            }
            return (int) decimal;
        }
        if (value instanceof String text && text.matches("-?[0-9]+")) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException(key + " 超出整数范围");
            }
        }
        throw new IllegalArgumentException(key + " 必须为整数");
    }

    private static String decimalId(Message request, String key) {
        Object value = data(request, key);
        if (!(value instanceof String text) || !text.matches("[0-9]+")) {
            throw new IllegalArgumentException(key + " 必须为十进制字符串");
        }
        try {
            if (Long.parseLong(text) <= 0) {
                throw new IllegalArgumentException(key + " 必须为正整数");
            }
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " 超出 BIGINT 范围");
        }
        return text;
    }

    private static String text(Message request, String key) {
        Object value = data(request, key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " 必须为非空字符串");
        }
        return text;
    }

    private static Object data(Message request, String key) {
        Map<String, Object> data = request.getData();
        Object value = data == null ? null : data.get(key);
        if (value == null) throw new IllegalArgumentException("缺少参数: " + key);
        return value;
    }
}
