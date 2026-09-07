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

        // 登录成功时，将学籍系统（学院、专业、姓名、性别）及银行余额数据同步到 tbl_user
        userDAO.syncUserInfo(user.getUID());

        // 登录成功，创建 Session
        String roleDescription = user.getRole() != null ? user.getRole().getDescription() : "学生";
        return SessionManager.getInstance().createSession(user.getUID(), roleDescription);
    }

    /**
     * 获取用户信息 (脱敏，去除密码和盐值，获取前先同步最新学籍与银行数据)
     */
    public User getUserInfo(String UID) throws BusinessException, DatabaseException {
        try {
            userDAO.syncUserInfo(UID);
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

    /**
     * 修改用户头像
     */
    public void updateAvatar(String UID, String avatarBase64) throws BusinessException, DatabaseException {


        try {
            if(UID == null){
                throw new BusinessException("UID为空");
            }
            if(avatarBase64 == null){
                throw new BusinessException("头像内容为空");
            }
            if(avatarBase64.length()>2800000){//2800000是2Mb图片转base64之后的大约大小
                throw new BusinessException("头像大于2Mb");
            }

            boolean success = userDAO.updateAvatar(UID, avatarBase64);
            if (!success) {
                throw new BusinessException("头像更新失败");
            }
        } catch (SQLException e) {
            throw new DatabaseException("数据库更新个人信息异常", e);
        }
    }

    private static class SmsRecord {
        final String code;
        final long expireAt;
        SmsRecord(String code, long expireAt) {
            this.code = code;
            this.expireAt = expireAt;
        }
    }
    private final java.util.Map<String, SmsRecord> smsCodeCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 发送短信验证码（模拟服务：生成6位随机数字，缓存5分钟有效）
     */
    public String sendVerificationCode(String phone, String uid) throws BusinessException, DatabaseException {
        if (phone == null || phone.trim().isEmpty()) {
            throw new BusinessException("手机号不能为空");
        }
        phone = phone.trim();
        if (!phone.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException("请输入有效的11位手机号码");
        }

        // 预检 UID 是否已被注册
        if (uid != null && !uid.trim().isEmpty()) {
            try {
                if (userDAO.findByUID(uid.trim()) != null) {
                    throw new BusinessException("该一卡通号已被注册，请直接登录！");
                }
            } catch (SQLException e) {
                throw new DatabaseException("检查UID重复失败", e);
            }
        }

        String code = String.format("%06d", new java.util.Random().nextInt(1000000));
        smsCodeCache.put(phone, new SmsRecord(code, System.currentTimeMillis() + 5 * 60 * 1000L));
        System.out.println("[短信验证服务] 向手机号 " + phone + " 发送验证码: " + code + "（5分钟内有效）");
        return code;
    }

    /**
     * 新用户注册（支持角色选择：学生 / 教师）
     */
    public void register(String uid, String name, String phone, String code, String password, String roleStr) throws BusinessException, DatabaseException {
        if (uid == null || uid.trim().isEmpty()) {
            throw new BusinessException("一卡通号不能为空");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException("姓名不能为空");
        }
        if (phone == null || phone.trim().isEmpty()) {
            throw new BusinessException("手机号不能为空");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException("验证码不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new BusinessException("密码长度不能少于6位");
        }

        uid = uid.trim();
        name = name.trim();
        phone = phone.trim();
        code = code.trim();

        // 1. 校验 UID 是否重复
        try {
            if (userDAO.findByUID(uid) != null) {
                throw new BusinessException("该一卡通号（" + uid + "）已被注册，不能重复注册！");
            }
        } catch (SQLException e) {
            throw new DatabaseException("校验UID失败", e);
        }

        // 2. 校验验证码正确性与时效
        SmsRecord record = smsCodeCache.get(phone);
        if (record == null || System.currentTimeMillis() > record.expireAt) {
            throw new BusinessException("验证码不存在或已过期，请重新获取！");
        }
        if (!record.code.equals(code)) {
            throw new BusinessException("验证码不正确，请重新输入！");
        }

        // 验证通过，清理验证码
        smsCodeCache.remove(phone);

        // 3. 密码加盐哈希
        String salt = PasswordUtil.generateSalt();
        String hashedPassword = PasswordUtil.hashPassword(password, salt);

        // 4. 组装新用户并初始化（未填写的属性全部置空）
        Role role = ("教师".equals(roleStr) || "TEACHER".equalsIgnoreCase(roleStr)) ? Role.TEACHER : Role.STUDENT;
        User user = new User();
        user.setUID(uid);
        user.setName(name);
        user.setGender("男");
        user.setPassword(hashedPassword);
        user.setSalt(salt);
        user.setRole(role);
        user.setCollege("");
        user.setMajor("");
        user.setPhone(phone);
        user.setEmail("");
        user.setBalance(new java.math.BigDecimal("1000.00"));

        try {
            userDAO.register(user);
            System.out.println("[用户服务] 新用户注册成功: UID=" + uid + ", 姓名=" + name + ", 身份=" + role.getDescription() + ", 手机=" + phone);
        } catch (SQLException e) {
            throw new DatabaseException("注册新用户数据库操作失败: " + e.getMessage(), e);
        }
    }

    public void register(String uid, String name, String phone, String code, String password) throws BusinessException, DatabaseException {
        register(uid, name, phone, code, password, "学生");
    }

    private final java.util.Map<String, SmsRecord> resetCodeCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 发送找回密码短信验证码
     */
    public String sendResetPasswordCode(String phone, String uid) throws BusinessException, DatabaseException {
        if (uid == null || uid.trim().isEmpty()) {
            throw new BusinessException("一卡通号不能为空");
        }
        if (phone == null || phone.trim().isEmpty()) {
            throw new BusinessException("手机号不能为空");
        }
        uid = uid.trim();
        phone = phone.trim();
        if (!phone.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException("请输入有效的11位手机号码");
        }

        // 验证用户是否存在以及预留手机号是否匹配
        try {
            User user = userDAO.findByUID(uid);
            if (user == null) {
                throw new BusinessException("该一卡通号不存在！");
            }
            if (user.getPhone() == null || user.getPhone().trim().isEmpty()) {
                throw new BusinessException("该一卡通账号未绑定手机号，请联系系统管理员重置密码！");
            }
            if (!phone.equals(user.getPhone().trim())) {
                throw new BusinessException("输入的手机号码与该一卡通账号绑定的手机号不匹配！");
            }
        } catch (SQLException e) {
            throw new DatabaseException("验证用户信息失败", e);
        }

        String code = String.format("%06d", new java.util.Random().nextInt(1000000));
        resetCodeCache.put(phone, new SmsRecord(code, System.currentTimeMillis() + 5 * 60 * 1000L));
        System.out.println("[找回密码服务] 向手机号 " + phone + "（UID=" + uid + "）发送重置验证码: " + code + "（5分钟内有效）");
        return code;
    }

    /**
     * 找回密码：通过短信验证码重置密码（校验一致性与复杂度）
     */
    public void resetPassword(String uid, String phone, String code, String newPassword) throws BusinessException, DatabaseException {
        if (uid == null || uid.trim().isEmpty()) {
            throw new BusinessException("一卡通号不能为空");
        }
        if (phone == null || phone.trim().isEmpty()) {
            throw new BusinessException("手机号不能为空");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException("验证码不能为空");
        }
        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new BusinessException("新密码不能为空");
        }

        uid = uid.trim();
        phone = phone.trim();
        code = code.trim();

        // 1. 校验密码长度：至少6位
        if (newPassword.length() < 6) {
            throw new BusinessException("新密码长度不能少于6位！");
        }

        // 2. 校验用户存在性与预留手机号是否匹配
        try {
            User user = userDAO.findByUID(uid);
            if (user == null) {
                throw new BusinessException("该一卡通号不存在！");
            }
            if (user.getPhone() == null || !phone.equals(user.getPhone().trim())) {
                throw new BusinessException("输入的手机号码与该一卡通账号绑定的手机号不匹配！");
            }
        } catch (SQLException e) {
            throw new DatabaseException("验证用户信息失败", e);
        }

        // 3. 校验验证码正确性与有效性
        SmsRecord record = resetCodeCache.get(phone);
        if (record == null || System.currentTimeMillis() > record.expireAt) {
            throw new BusinessException("验证码不存在或已过期，请重新获取！");
        }
        if (!record.code.equals(code)) {
            throw new BusinessException("验证码不正确，请重新输入！");
        }

        // 验证通过，清理验证码
        resetCodeCache.remove(phone);

        // 4. 加盐哈希加密新密码
        String newSalt = PasswordUtil.generateSalt();
        String newHash = PasswordUtil.hashPassword(newPassword, newSalt);

        // 5. 更新数据库密码
        try {
            boolean success = userDAO.updatePassword(uid, newHash, newSalt);
            if (!success) {
                throw new BusinessException("密码重置失败");
            }
            System.out.println("[找回密码服务] 用户 " + uid + " 密码重置成功");
        } catch (SQLException e) {
            throw new DatabaseException("更新密码数据库异常", e);
        }
    }
}
