/**
 * 请求处理层（Handler）
 *
 * <p>本包存放所有请求处理类，负责接收 Message 请求，调用 Service 层处理业务，并返回响应 Message。
 *
 * <p><b>负责人：</b>各模块负责人自行实现
 *
 * <p><b>命名规范：</b>
 * <ul>
 *   <li>类名 = 模块名 + Handler，如：UserHandler、BookHandler</li>
 *   <li>一个 Handler 对应一个功能模块</li>
 * </ul>
 *
 * <p><b>编码规范：</b>
 * <ul>
 *   <li>接收 Message 请求，解析参数</li>
 *   <li>调用 Service 层处理业务</li>
 *   <li>将处理结果封装为 Message 返回</li>
 *   <li>统一处理异常，转换为对应的 MessageCode</li>
 * </ul>
 *
 * <p><b>参考示例：</b>{@link com.vcampus.server.handler.UserHandler}
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
package com.vcampus.server.handler;