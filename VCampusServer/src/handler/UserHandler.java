package handler;

import entity.AdminPermission;
import dao.AdminPermissionDAO;
import entity.User;
import exception.BusinessException;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.UserService;
import session.SessionManager;
import session.UserSession;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 用户模块请求处理器
 */
public class UserHandler {

    private final UserService userService = new UserService();
    private final AdminPermissionDAO adminPermissionDAO = new AdminPermissionDAO();

    public Message handle(Message request) {
        String action = request.getAction();
        Message response = new Message(MessageType.RESPONSE, "user", action);
        response.setUID(request.getUID());

        if (action == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("Action 不能为空");
            return response;
        }

        try {
            switch (action.toLowerCase()) {
                case "login":
                    return handleLogin(request, response);
                case "sendsmscode":
                case "send_code":
                    return handleSendSmsCode(request, response);
                case "sendresetcode":
                case "send_reset_code":
                    return handleSendResetCode(request, response);
                case "register":
                    return handleRegister(request, response);
                case "resetpassword":
                case "reset_password":
                    return handleResetPassword(request, response);
                case "getuserinfo":
                    return handleGetUserInfo(request, response);
                case "changepassword":
                    return handleChangePassword(request, response);
                case "updateprofile":
                    return handleUpdateProfile(request, response);
                case "updateavatar":
                    return handleUpdateAvatar(request,response);
                case "logout":
                    return handleLogout(request, response);
                case "list_admin_permissions":
                case "get_admin_permissions":
                    return handleListAdminPermissions(request, response);
                case "update_admin_permissions":
                    return handleUpdateAdminPermissions(request, response);
                case "get_my_permissions":
                    return handleGetMyPermissions(request, response);
                default:
                    response.setCode(MessageCode.BAD_REQUEST);
                    response.setMessage("不支持的操作: " + action);
                    return response;
            }
        } catch (BusinessException e) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage(e.getMessage());
        } catch (DatabaseException e) {
            System.err.println("用户服务数据库异常: " + e.getMessage());
            if (e.getCause() != null) e.getCause().printStackTrace();
            response.setCode(MessageCode.ERROR);
            response.setMessage("服务端数据库异常: " + e.getMessage() + databaseCause(e));
        } catch (Exception e) {
            response.setCode(MessageCode.ERROR);
            response.setMessage("服务端内部错误: " + e.getMessage());
        }

        return response;
    }

    private String databaseCause(DatabaseException exception) {
        Throwable cause=exception.getCause();
        return cause==null||cause.getMessage()==null?"":"（"+cause.getMessage()+"）";
    }

    private Message handleLogin(Message request, Message response) throws BusinessException, DatabaseException {
        String cardNo = request.getData("cardNo");
        String password = request.getData("password");
        String role = request.getData("role");

        UserSession session = userService.login(cardNo, password, role);
        User userInfo = userService.getUserInfo(cardNo);

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("登录成功");
        response.setToken(session.getToken());
        response.putData("token", session.getToken());
        response.putData("username", session.getUsername());
        response.putData("role", session.getRole());
        response.putData("user", userInfo);

        if (userInfo != null && (userInfo.getRole() == enums.Role.ADMIN || "管理员".equals(session.getRole()))) {
            try {
                AdminPermission perm = adminPermissionDAO.findByUid(cardNo);
                response.putData("adminPermission", perm);
            } catch (Exception e) {
                System.err.println("获取管理员权限失败: " + e.getMessage());
            }
        }

        return response;
    }

    private Message handleSendSmsCode(Message request, Message response) throws BusinessException, DatabaseException {
        String phone = request.getData("phone");
        String uid = request.getData("uid");
        String code = userService.sendVerificationCode(phone, uid);

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("验证码发送成功");
        response.putData("code", code);
        return response;
    }

    private Message handleSendResetCode(Message request, Message response) throws BusinessException, DatabaseException {
        String phone = request.getData("phone");
        String uid = request.getData("uid");
        String code = userService.sendResetPasswordCode(phone, uid);

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("重置验证码发送成功");
        response.putData("code", code);
        return response;
    }

    private Message handleRegister(Message request, Message response) throws BusinessException, DatabaseException {
        String uid = request.getData("uid");
        String name = request.getData("name");
        String phone = request.getData("phone");
        String code = request.getData("code");
        String password = request.getData("password");
        String role = request.getData("role");

        userService.register(uid, name, phone, code, password, role);
        response.setCode(MessageCode.SUCCESS);
        response.setMessage("注册成功！请使用新账号登录");
        return response;
    }

    private Message handleResetPassword(Message request, Message response) throws BusinessException, DatabaseException {
        String uid = request.getData("uid");
        String phone = request.getData("phone");
        String code = request.getData("code");
        String newPassword = request.getData("newPassword");

        userService.resetPassword(uid, phone, code, newPassword);
        response.setCode(MessageCode.SUCCESS);
        response.setMessage("密码修改成功！请使用新密码重新登录");
        return response;
    }

    private Message handleGetUserInfo(Message request, Message response) throws BusinessException, DatabaseException {
        String token = request.getToken();
        if (!SessionManager.getInstance().isValid(token)) {
            response.setCode(MessageCode.UNAUTHORIZED);
            response.setMessage("登录会话已失效，请重新登录");
            return response;
        }

        String cardNo = request.getData("cardNo");
        if (cardNo == null || cardNo.isEmpty()) {
            cardNo = request.getSender();
        }

        User user = userService.getUserInfo(cardNo);
        response.setCode(MessageCode.SUCCESS);
        response.putData("user", user);
        return response;
    }

    private Message handleChangePassword(Message request, Message response) throws BusinessException, DatabaseException {
        String token = request.getToken();
        if (!SessionManager.getInstance().isValid(token)) {
            response.setCode(MessageCode.UNAUTHORIZED);
            response.setMessage("登录会话已失效，请重新登录");
            return response;
        }

        String cardNo = request.getSender();
        String oldPassword = request.getData("oldPassword");
        String newPassword = request.getData("newPassword");

        userService.changePassword(cardNo, oldPassword, newPassword);
        response.setCode(MessageCode.SUCCESS);
        response.setMessage("密码修改成功");
        return response;
    }

    private Message handleUpdateProfile(Message request, Message response) throws BusinessException, DatabaseException {
        String token = request.getToken();
        if (!SessionManager.getInstance().isValid(token)) {
            response.setCode(MessageCode.UNAUTHORIZED);
            response.setMessage("登录会话已失效，请重新登录");
            return response;
        }

        // 此处可从 request 的 data 中组装 user
        User user = new User();
        user.setUID(request.getSender());
        user.setName(request.getData("name"));
        user.setGender(request.getData("gender"));
        user.setCollege(request.getData("college"));
        user.setMajor(request.getData("major"));
        user.setPhone(request.getData("phone"));
        user.setEmail(request.getData("email"));

        userService.updateProfile(user);
        response.setCode(MessageCode.SUCCESS);
        response.setMessage("个人资料更新成功");
        return response;
    }

    private Message handleUpdateAvatar(Message request, Message response) throws BusinessException, DatabaseException {
        String token = request.getToken();
        if (!SessionManager.getInstance().isValid(token)) {
            response.setCode(MessageCode.UNAUTHORIZED);
            response.setMessage("登录会话已失效，请重新登录");
            return response;
        }

        // 此处可从 request 的 data 中组装 user
        String UID = request.getSender();
        String avatarBase64 = request.getData("avatar");
        userService.updateAvatar(UID,avatarBase64);
        response.setCode(MessageCode.SUCCESS);
        response.setMessage("个人资料更新成功");
        return response;
    }

    private Message handleLogout(Message request, Message response) {
        String token = request.getToken();
        if (token != null) {
            SessionManager.getInstance().removeSession(token);
        }
        response.setCode(MessageCode.SUCCESS);
        response.setMessage("已安全退出");
        return response;
    }

    private Message handleListAdminPermissions(Message request, Message response) throws SQLException {
        List<AdminPermission> list = adminPermissionDAO.getAllAdminPermissions();
        response.setCode(MessageCode.SUCCESS);
        response.setMessage("获取管理员权限列表成功");
        response.putData("permissions", list);
        return response;
    }

    private Message handleUpdateAdminPermissions(Message request, Message response) throws SQLException {
        Object permObj = request.getData("permissions");
        if (permObj != null) {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            String json = gson.toJson(permObj);
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<AdminPermission>>(){}.getType();
            List<AdminPermission> list = gson.fromJson(json, type);
            adminPermissionDAO.updatePermissions(list);
        }
        response.setCode(MessageCode.SUCCESS);
        response.setMessage("管理员权限更新成功");
        return response;
    }

    private Message handleGetMyPermissions(Message request, Message response) throws SQLException {
        String uid = request.getData("uid");
        if (uid == null || uid.isBlank()) {
            uid = request.getSender();
        }
        if (uid == null || uid.isBlank()) {
            String token = request.getToken();
            UserSession session = SessionManager.getInstance().getSession(token);
            if (session != null) {
                uid = session.getUsername();
            }
        }
        AdminPermission perm = adminPermissionDAO.findByUid(uid);
        response.setCode(MessageCode.SUCCESS);
        response.setMessage("获取当前权限成功");
        response.putData("adminPermission", perm);
        return response;
    }
}
