/**
 * 视图对象（VO）定义包
 *
 * <p><b>这个包是干什么的？</b>
 * <br>放「要发给客户端的数据」。
 * <br>客户端看到的所有信息，都通过 VO 传递。
 *
 * <p><b>和 entity 有什么区别？</b>
 * <br>entity 是数据库表的映射，包含所有字段（包括密码、盐值等敏感信息）。
 * <br>VO 是发给客户端的，只包含客户端需要的字段（敏感信息必须去掉）。
 *
 * <p><b>什么时候用 VO？</b>
 * <br>服务端处理完业务后，把结果封装成 VO 返回给客户端。
 * <br>客户端接收 VO 后直接显示，不需要再处理。
 *
 * <p><b>谁来建 VO？</b>
 * <br>各功能模块负责人自行创建自己模块的 VO。
 *
 * <p><b>命名规范：</b>
 * <ul>
 *   <li>类名 = 实体名 + VO，如：User → UserVO</li>
 *   <li>文件放在 vo 包下</li>
 * </ul>
 *
 * <p><b>示例：UserVO（参考模板）</b>
 * <pre>
 * public class UserVO implements Serializable {
 *     private String UID;      // 一卡通号
 *     private String name;     // 姓名
 *     private String role;     // 角色
 *     // 没有 password（敏感信息不发给客户端）
 *     // 没有 balance（余额由银行模块单独返回）
 * }
 * </pre>
 *
 * <p><b>各模块负责人请自行创建以下 VO：</b>
 * <ul>
 *   <li>用户模块 → UserVO（已提供示例）</li>
 *   <li>学籍模块 → StudentVO</li>
 *   <li>选课模块 → CourseVO、EnrollmentVO</li>
 *   <li>图书馆模块 → BookVO、BorrowRecordVO</li>
 *   <li>银行模块 → AccountVO、TransactionVO</li>
 *   <li>商店模块 → ProductVO、OrderVO</li>
 *   <li>AI助手模块 → 自行决定是否需要</li>
 * </ul>
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
package vo;