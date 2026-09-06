package entity;

import enums.Role;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 用户实体类
 */
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 一卡通号 */
    private String UID;

    /** 姓名 */
    private String name;

    /** 性别 (男/女) */
    private String gender;

    /** 密码哈希值 */
    private String password;

    /** 密码盐值 */
    private String salt;

    /** 用户角色 */
    private Role role;

    /** 学院 */
    private String college;

    /** 专业 for 学生; 职称 for 老师; 职位 for 管理员 */
    private String major;

    /** 联系电话 */
    private String phone;

    /** 电子邮箱 */
    private String email;

    /** 头像Base64编码*/
    private  String avatar;

    /** 电子钱包余额 */
    private BigDecimal balance;

    public User() {
        this.balance = BigDecimal.ZERO;
    }

    public User(String UID, String name, Role role) {
        this();
        this.UID = UID;
        this.name = name;
        this.role = role;
    }

    public String getUID() {
        return UID;
    }

    public void setUID(String UID) {
        this.UID = UID;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getCollege() {
        return college;
    }

    public void setCollege(String college) {
        this.college = college;
    }

    public String getMajor() {
        return major;
    }

    public void setMajor(String major) {
        this.major = major;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAvatar() {return avatar;};

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    @Override
    public String toString() {
        return "User{" +
                "UID='" + UID + '\'' +
                ", name='" + name + '\'' +
                ", gender='" + gender + '\'' +
                ", role=" + role +
                ", college='" + college + '\'' +
                ", major='" + major + '\'' +
                ", phone='" + phone + '\'' +
                ", email='" + email + '\'' +
                ", balance=" + balance +
                '}';
    }
}
