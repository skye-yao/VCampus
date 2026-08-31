/**
 * 数据库实体统一定义包
 *
 * <p>本包存放所有与数据库表对应的实体类。
 *
 * <p><b>使用规范：</b>
 * <ul>
 *   <li>所有实体类必须实现 {@link java.io.Serializable} 接口</li>
 *   <li>必须定义 serialVersionUID</li>
 *   <li>字段命名采用小驼峰（camelCase），与数据库字段对应</li>
 *   <li>每个实体类需包含完整的 Getter/Setter</li>
 *   <li>重写 toString() 方法，便于日志调试</li>
 * </ul>
 *
 * <p><b>各模块对应实体清单：</b>
 * <ul>
 *   <li><b>用户模块：</b>{@link entity.User}（示例已提供）</li>
 *   <li><b>学籍模块：</b>{@link entity.Student}</li>
 *   <li><b>选课模块：</b>{@link entity.Course}、{@link entity.Enrollment}</li>
 *   <li><b>图书馆模块：</b>{@link entity.Book}、{@link entity.BorrowRecord}</li>
 *   <li><b>银行模块：</b>{@link entity.Account}、{@link entity.Transaction}</li>
 *   <li><b>商店模块：</b>{@link entity.Product}、{@link entity.Order}、{@link entity.OrderItem}</li>
 *   <li><b>AI助手模块：</b>（如有需要，自行添加）</li>
 * </ul>
 *
 * <p><b>实体类编写模板：</b>
 * <pre>
 * package entity;
 *
 * import java.io.Serializable;
 * import java.time.LocalDateTime;
 *
 * public class Xxx implements Serializable {
 *     private static final long serialVersionUID = 1L;
 *
 *     // 字段定义（使用包装类型，如 Integer、Long）
 *     private Integer id;
 *     private String name;
 *     private LocalDateTime createdAt;
 *     private LocalDateTime updatedAt;
 *
 *     // 无参构造方法
 *     public Xxx() {}
 *
 *     // Getter / Setter（Eclipse 自动生成：右键 → Source → Generate Getters and Setters）
 *     // toString()（Eclipse 自动生成：右键 → Source → Generate toString()）
 * }
 * </pre>
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
package entity;