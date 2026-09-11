package entity;

import java.io.Serializable;

/** 管理员发起收费时可选择的师生用户。 */
public class FinanceChargeTarget implements Serializable {
    private static final long serialVersionUID = 1L;

    private String userId;
    private String name;
    private int role;
    private String college;
    private String major;
    private transient boolean selected;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getRole() { return role; }
    public void setRole(int role) { this.role = role; }
    public String getRoleName() { return role == 1 ? "教师" : role == 2 ? "学生" : "其他"; }
    public String getCollege() { return college; }
    public void setCollege(String college) { this.college = college; }
    public String getMajor() { return major; }
    public void setMajor(String major) { this.major = major; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
}
