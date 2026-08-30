/**
 * 客户端界面控制层。
 *
 * <p>
 * 负责连接客户端界面与底层业务通信。
 * Controller 接收界面操作，根据用户操作构造
 * Message 请求，并通过 SocketClient 发送至服务器。
 * </p>
 *
 * <p>
 * 后续各业务模块可在此包中创建对应的 Controller，
 * 例如：
 * </p>
 *
 * <ul>
 *     <li>UserController - 用户管理</li>
 *     <li>StudentController - 学籍管理</li>
 *     <li>CourseController - 选课系统</li>
 *     <li>LibraryController - 图书馆</li>
 *     <li>ShopController - 商店</li>
 *     <li>BankController - 校园银行</li>
 *     <li>AIController - AI 助手</li>
 * </ul>
 *
 * <p>
 * Controller 不直接访问数据库，
 * 不负责具体业务逻辑，主要负责界面操作、
 * 请求构造以及服务器响应结果的处理。
 * </p>
 */
package com.vcampus.client.controller;