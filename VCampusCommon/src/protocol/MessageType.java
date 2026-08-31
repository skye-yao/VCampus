package protocol;

/**
 * 消息类型枚举
 * 定义客户端与服务端通信的三种基本类型
 */

public enum MessageType {

REQUEST,   // 请求
RESPONSE,  // 响应
PUSH,       // 推送	

//用户管理	
USER_LOGIN,//"用户登录",
USER_REGISTER,//"用户注册",
USER_LOGOUT,//"用户登出",
USER_INFO_QUERY,//"查询用户信息",
USER_INFO_UPDATE,//"修改用户信息",
USER_PASSWORD_CHANGE,//"修改密码",
USER_PASSWORD_RESET,// "重置密码"
	
//学生学籍管理
STUDENT_OVERVIEW_QUERY,     // 学生查询本人学籍概览
STUDENT_LIST,               // 管理员查询学生列表
STUDENT_QUERY,              // 管理员查询指定学生完整信息   

STUDENT_CHANGE_SUBMIT,      // 学生提交一份信息修改申请
STUDENT_CHANGE_LIST,        // 学生查询自己的历史修改申请
STUDENT_CHANGE_CANCEL,      // 学生撤销自己的待审核申请

STUDENT_REVIEW_LIST,        // 管理员查询待审核申请列表
STUDENT_REVIEW_QUERY,       // 管理员查询某一申请详情
STUDENT_REVIEW,             // 管理员审核通过/驳

STUDENT_ADMIN_UPDATE,       // 管理员直接修改正式学籍
STUDENT_AWARD_ADD,          // 新增奖励
STUDENT_AWARD_UPDATE,       // 修改奖励
STUDENT_AWARD_DELETE,       // 删除奖励

STUDENT_AID_ADD,            // 新增资助
STUDENT_AID_UPDATE,         // 修改资助
STUDENT_AID_DELETE,          // 删除资助

//选课


// 图书馆
LIBRARY_BOOK_SEARCH,              //"图书检索"
LIBRARY_BOOK_DETAIL_QUERY,        //"查询图书详情"
LIBRARY_BOOK_RESERVE,             //"图书预约"
LIBRARY_MY_LIBRARY_QUERY,         //"查询我的图书馆信息"
LIBRARY_BORROW_HISTORY_QUERY,     //"查询借阅历史"
LIBRARY_CURRENT_BORROW_QUERY,     //"查询当前借阅信息"
LIBRARY_RESERVATION_QUERY,        //"查询预约信息"
LIBRARY_BOOK_REVIEW_QUERY,        //"查询图书评价"
LIBRARY_BOOK_REVIEW_ADD,          //"发表图书评价"
LIBRARY_BOOK_LOSS_REPORT,         //"图书挂失"
LIBRARY_BOOK_LOSS_CANCEL,         //"解除图书挂失"
LIBRARY_FINE_QUERY,               //"查询违章罚金"
LIBRARY_FINE_PAY,                 //"违章缴费"
LIBRARY_BOOK_ADD,                 //"图书上架"
LIBRARY_BOOK_REMOVE,              //"图书下架"
LIBRARY_BOOK_STATUS_QUERY,         //"查询图书状态"

// 商店
SHOP_PRODUCT_LIST,                  // 查询商品列表
SHOP_PRODUCT_DETAIL,                // 查询商品详细信息
SHOP_CART_LIST,                     // 查询当前用户购物车
SHOP_CART_ADD,                      // 将商品加入购物车
SHOP_CART_UPDATE,                   // 修改购物车商品数量
SHOP_CART_REMOVE,                   // 删除购物车中的指定商品

SHOP_ORDER_CREATE,                  // 创建订单并保存订单明细
SHOP_ORDER_PAY,                     // 支付商店订单
SHOP_ORDER_CANCEL,                  // 取消待支付订单
SHOP_ORDER_LIST,                    // 查询当前用户的订单列表
SHOP_ORDER_DETAIL,                  // 查询指定订单及其明细

SHOP_REFUND_APPLY,                  // 用户提交订单退款申请

SHOP_PRODUCT_CREATE,                // 管理员新增商品
SHOP_PRODUCT_UPDATE,                // 管理员修改商品信息
SHOP_PRODUCT_STATUS_CHANGE,         // 管理员修改商品上下架状态
SHOP_PRODUCT_STOCK_UPDATE,          // 管理员调整商品库存数量

SHOP_ORDER_PROCESS,                 // 管理员处理已支付订单
SHOP_REFUND_REVIEW,                 // 管理员审核退款申请
SHOP_SALES_SUMMARY,                 // 管理员查询商店销售统计
SHOP_OPERATION_LOG_QUERY,           // 管理员查询商店操作日志


// 校园银行模块
BANK_ACCOUNT_QUERY,                 // 查询当前用户的虚拟银行账户
BANK_ACCOUNT_CREATE,                // 管理员为指定用户创建虚拟账户
BANK_ACCOUNT_INITIALIZE,            // 管理员设置账户初始余额
BANK_ACCOUNT_STATUS_CHANGE,         // 管理员冻结或解冻虚拟账户

BANK_PASSWORD_STATUS_QUERY,         // 查询用户是否已设置支付密码
BANK_PASSWORD_SET,                  // 用户首次设置支付密码
BANK_PASSWORD_CHANGE,               // 用户修改支付密码
BANK_PASSWORD_RESET,                // 管理员重置支付密码状态

BANK_TRANSACTION_LIST,              // 查询账户交易流水列表
BANK_TRANSACTION_DETAIL,            // 查询指定交易流水详情
BANK_TRANSFER,                      // 师生用户之间进行虚拟转账

FINANCE_BILL_CREATE,                // 管理员发布校园缴费账单
FINANCE_BILL_MY_LIST,               // 用户查询自己的校园账单
FINANCE_BILL_ALL_LIST,              // 管理员查询全部校园账单
FINANCE_BILL_PAY,                   // 用户缴纳校园费用

FINANCE_REIMBURSEMENT_APPLY,        // 用户提交校园报销申请
FINANCE_REIMBURSEMENT_MY_LIST,      // 用户查询自己的报销申请
FINANCE_REIMBURSEMENT_DETAIL,       // 查询指定报销申请详情
FINANCE_REIMBURSEMENT_REVIEW,       // 管理员审核报销申请

FINANCE_GRANT_CREATE,               // 管理员发放奖学金、助学金或补助
FINANCE_REPORT_QUERY,               // 管理员查询校园财务统计
FINANCE_TRANSACTION_REVERSE,        // 管理员对异常交易进行冲正


//AI
AI_CONVERSATION_CREATE,             // 创建新的AI对话
AI_CONVERSATION_LIST,               // 查询当前用户的历史对话
AI_CONVERSATION_DELETE,             // 删除指定的历史对话

AI_MESSAGE_LIST,                    // 查询指定对话的历史消息
AI_RAG_CHAT,                        // 提交校园知识库RAG问答请求
AI_CHAT_CANCEL,                     // 取消正在处理的AI问答
AI_CHAT_CHUNK,                      // 返回流式回答的部分内容
AI_CHAT_DONE,                       // 返回完整回答及知识来源

AI_FEEDBACK_SUBMIT,                 // 提交AI回答评价或反馈

AI_DOCUMENT_IMPORT,                 // 管理员导入知识库文档
AI_DOCUMENT_LIST,                   // 管理员查询知识库文档列表
AI_DOCUMENT_UPDATE,                 // 管理员修改知识库文档信息
AI_DOCUMENT_DISABLE,                // 管理员停用知识库文档

AI_INDEX_BUILD,                     // 为新文档建立向量索引
AI_INDEX_REBUILD,                   // 重新生成文档向量索引
AI_INDEX_STATUS,                    // 查询向量索引构建状态
AI_RETRIEVAL_TEST;                  // 测试知识库检索结果
}
