/**
 * 枚举统一定义包
 * 
 * <p>本包用于存放系统所有枚举类，各模块负责人按需添加。
 * 
 * <p><b>命名规范：</b>
 * <ul>
 *   <li>枚举类名：大驼峰，如 {@code BookStatus}、{@code OrderStatus}</li>
 *   <li>枚举值：全大写+下划线，如 {@code ON_SALE}、{@code BORROWED}</li>
 * </ul>
 * 
 * <p><b>枚举结构要求：</b>
 * <pre>
 * public enum XxxStatus {
 *     OPTION_1(0, "选项一"),
 *     OPTION_2(1, "选项二");
 *     
 *     private final int code;
 *     private final String description;
 *     
 *     // 构造方法、getter、fromCode() 静态方法
 * }
 * </pre>
 * 
 * <p><b>已定义枚举清单：</b>
 * <ul>
 *   <li>{@link enums.Role} - 用户角色</li>
 *   <li>其他模块负责人自行添加...</li>
 * </ul>
 * 
 * @author VirtualCampus Team
 */
package enums;