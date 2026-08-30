/**
 * 数据访问层（DAO）
 *
 * <p>本包存放所有数据库操作类，每个实体对应一个 DAO。
 *
 * <p><b>负责人：</b>各模块负责人自行实现
 *
 * <p><b>命名规范：</b>
 * <ul>
 *   <li>类名 = 实体名 + DAO，如：UserDAO、BookDAO</li>
 *   <li>表名统一用 tblXxx 格式，如：tblUser、tblBook</li>
 * </ul>
 *
 * <p><b>编码规范：</b>
 * <ul>
 *   <li>必须使用 PreparedStatement，防止 SQL 注入</li>
 *   <li>使用 DBUtil.getConnection() 获取连接</li>
 *   <li>使用 try-with-resources 自动释放资源</li>
 *   <li>异常统一抛出 DatabaseException</li>
 * </ul>
 *
 * <p><b>参考示例：</b>{@link com.vcampus.server.dao.UserDAO}
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
package com.vcampus.server.dao;