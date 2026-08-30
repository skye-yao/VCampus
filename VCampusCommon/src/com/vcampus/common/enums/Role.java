package com.vcampus.common.enums;

/**
 * 用户角色枚举
 * 
 * <p><b>使用规范：</b>
 * <ul>
 *   <li>所有枚举类统一放在此包下</li>
 *   <li>枚举命名使用大驼峰（PascalCase），如：BookStatus、OrderStatus</li>
 *   <li>枚举值使用全大写+下划线（UPPER_SNAKE_CASE），如：ON_SALE、IN_STOCK</li>
 *   <li>每个枚举必须包含 code（状态码）和 description（中文描述）两个字段</li>
 *   <li>必须提供 getCode() 和 getDescription() 方法</li>
 *   <li>必须提供 fromCode() 静态方法，用于根据 code 反查枚举</li>
 * </ul>
 * 
 * <p><b>使用示例：</b>
 * <pre>
 * // 赋值
 * user.setRole(Role.ADMIN);
 * 
 * // 判断
 * if (user.getRole() == Role.STUDENT) {
 *     // 学生逻辑
 * }
 * 
 * // 根据code反查
 * Role role = Role.fromCode(1);  // 返回 Role.TEACHER
 * </pre>
 * 
 * @author VirtualCampus Team
 * @version 1.0
 */
public enum Role {
    
    /** 管理员：拥有系统所有管理权限 */
    ADMIN(0, "管理员"),
    
    /** 教师：拥有教学相关权限 */
    TEACHER(1, "教师"),
    
    /** 学生：拥有学习相关权限 */
    STUDENT(2, "学生");
    
    private final int code;
    private final String description;
    
    Role(int code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 根据状态码反查枚举
     * 
     * @param code 状态码
     * @return 对应的枚举值，找不到返回 null
     */
    public static Role fromCode(int code) {
        for (Role role : Role.values()) {
            if (role.getCode() == code) {
                return role;
            }
        }
        return null;
    }
}