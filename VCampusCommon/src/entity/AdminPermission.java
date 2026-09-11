package entity;

import java.io.Serializable;

/**
 * 管理员权限实体类
 * 
 * 对应数据表 tbl_admin_permission，记录管理员在各业务子系统的管理操作权限。
 */
public class AdminPermission implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 管理员一卡通号/账号 UID */
    private String uid;

    /** 管理员姓名（通常关联 tbl_user 获取，仅用于前端展示） */
    private String name;

    /** 学籍管理权限 */
    private boolean academicPerm;

    /** 图书馆管理权限 */
    private boolean libraryPerm;

    /** 选课管理权限 */
    private boolean coursePerm;

    /** 商店管理权限 */
    private boolean shopPerm;

    /** 银行管理权限 */
    private boolean bankPerm;

    public AdminPermission() {
    }

    public AdminPermission(String uid, String name, boolean academicPerm, boolean libraryPerm, boolean coursePerm, boolean shopPerm, boolean bankPerm) {
        this.uid = uid;
        this.name = name;
        this.academicPerm = academicPerm;
        this.libraryPerm = libraryPerm;
        this.coursePerm = coursePerm;
        this.shopPerm = shopPerm;
        this.bankPerm = bankPerm;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isAcademicPerm() {
        return academicPerm;
    }

    public void setAcademicPerm(boolean academicPerm) {
        this.academicPerm = academicPerm;
    }

    public boolean isLibraryPerm() {
        return libraryPerm;
    }

    public void setLibraryPerm(boolean libraryPerm) {
        this.libraryPerm = libraryPerm;
    }

    public boolean isCoursePerm() {
        return coursePerm;
    }

    public void setCoursePerm(boolean coursePerm) {
        this.coursePerm = coursePerm;
    }

    public boolean isShopPerm() {
        return shopPerm;
    }

    public void setShopPerm(boolean shopPerm) {
        this.shopPerm = shopPerm;
    }

    public boolean isBankPerm() {
        return bankPerm;
    }

    public void setBankPerm(boolean bankPerm) {
        this.bankPerm = bankPerm;
    }

    /** 兼容旧接口 */
    public boolean isFinancePerm() {
        return shopPerm && bankPerm;
    }

    public void setFinancePerm(boolean financePerm) {
        this.shopPerm = financePerm;
        this.bankPerm = financePerm;
    }

    @Override
    public String toString() {
        return "AdminPermission{" +
                "uid='" + uid + '\'' +
                ", name='" + name + '\'' +
                ", academicPerm=" + academicPerm +
                ", libraryPerm=" + libraryPerm +
                ", coursePerm=" + coursePerm +
                ", shopPerm=" + shopPerm +
                ", bankPerm=" + bankPerm +
                '}';
    }
}
