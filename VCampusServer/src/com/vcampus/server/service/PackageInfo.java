/**
 * 业务逻辑层（Service）
 *
 * <p>本包存放所有业务逻辑类，负责处理具体的业务规则和流程。
 *
 * <p><b>负责人：</b>各模块负责人自行实现
 *
 * <p><b>命名规范：</b>
 * <ul>
 *   <li>类名 = 模块名 + Service，如：UserService、BookService</li>
 *   <li>一个 Service 对应一个功能模块</li>
 * </ul>
 *
 * <p><b>编码规范：</b>
 * <ul>
 *   <li>调用 DAO 层进行数据库操作</li>
 *   <li>处理业务逻辑（校验、计算、状态转换等）</li>
 *   <li>事务控制在 Service 层管理（后续可扩展）</li>
 *   <li>业务异常抛出 {@link com.vcampus.server.exception.BusinessException}</li>
 * </ul>
 *
 * <p><b>参考示例：</b>{@link com.vcampus.server.service.UserService}
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
package com.vcampus.server.service;