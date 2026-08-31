package service;

import dao.UserDAO;
import entity.User;
import enums.Role;
import exception.BusinessException;
import exception.DatabaseException;
import session.SessionManager;
import session.UserSession;
import util.PasswordUtil;

import java.sql.SQLException;

/**
 * 用户业务逻辑服务类
 */
public class UserService {

    private final UserDAO userDAO = new UserDAO();

    /**
     * 用户登录验证
     *
     * @param UID   一卡通号/学工号
     * @param password 明文密码
     * @param roleStr  登录所选角色名称 ("学生", "教师", "管理员")
     * @return 包含 Token 和 User 实体的 UserSession
     */
    public UserSession login(String UID, String password, String roleStr) throws BusinessException, DatabaseException {
        if (UID == null || UID.trim().isEmpty()) {
            throw new BusinessException("一卡通号不能为空");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new BusinessException("密码不能为空");
        }

        User user;
        try {
            user = userDAO.findByUID(UID.trim());
        } catch (SQLException e) {
            throw new DatabaseException("数据库查询失败", e);
        }

        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 校验身份角色
        if (roleStr != null && !roleStr.trim().isEmpty()) {
            Role expectedRole = user.getRole();
            if (expectedRole != null && !expectedRole.getDescription().equals(roleStr.trim())) {
                throw new BusinessException("登录身份与账户角色不匹配");
            }
        }

        // 校验密码哈希
        boolean passwordMatch = PasswordUtil.verifyPassword(password, user.getSalt(), user.getPassword());
        if (!passwordMatch) {
            throw new BusinessException("密码错误，请重新输入");
        }

        // 登录成功，创建 Session
        String roleDescription = user.getRole() != null ? user.getRole().getDescription() : "学生";
        return SessionManager.getInstance().createSession(user.getUID(), roleDescription);
    }

    /**
     * 获取用户信息 (脱敏，去除密码和盐值)
     */
    public User getUserInfo(String UID) throws BusinessException, DatabaseException {
        try {
            User user = userDAO.findByUID(UID);
            if (user == null) {
                throw new BusinessException("用户不存在");
            }
            user.setPassword(null);
            user.setSalt(null);
            return user;
        } catch (SQLException e) {
            throw new DatabaseException("查询用户信息失败", e);
        }
    }

    /**
     * 修改密码
     */
    public void changePassword(String UID, String oldPassword, String newPassword) throws BusinessException, DatabaseException {
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException("新密码长度不能少于6位");
        }

        try {
            User user = userDAO.findByUID(UID);
            if (user == null) {
                throw new BusinessException("用户不存在");
            }

            if (!PasswordUtil.verifyPassword(oldPassword, user.getSalt(), user.getPassword())) {
                throw new BusinessException("原密码不正确");
            }

            String newSalt = PasswordUtil.generateSalt();
            String newHash = PasswordUtil.hashPassword(newPassword, newSalt);

            boolean success = userDAO.updatePassword(UID, newHash, newSalt);
            if (!success) {
                throw new BusinessException("密码修改失败");
            }
        } catch (SQLException e) {
            throw new DatabaseException("数据库修改密码异常", e);
        }
    }

    /**
     * 修改用户基本信息
     */
    public void updateProfile(User user) throws BusinessException, DatabaseException {
        try {
            boolean success = userDAO.updateProfile(user);
            if (!success) {
                throw new BusinessException("个人信息更新失败");
            }
        } catch (SQLException e) {
            throw new DatabaseException("数据库更新个人信息异常", e);
        }
    }
}
