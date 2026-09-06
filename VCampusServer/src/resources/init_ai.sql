-- ==================== 虚拟校园 AI 助手模块 ====================
USE `virtual_campus`;

-- 1. AI 对话会话表
CREATE TABLE IF NOT EXISTS `tbl_ai_conversation` (
    `conversation_id` VARCHAR(64) NOT NULL COMMENT '会话唯一ID(UUID)',
    `user_id` VARCHAR(32) NOT NULL COMMENT '所属用户一卡通号',
    `title` VARCHAR(200) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`conversation_id`),
    INDEX `idx_ai_conv_user_time` (`user_id`, `updated_at`),
    CONSTRAINT `fk_ai_conv_user` FOREIGN KEY (`user_id`) REFERENCES `tbl_user` (`UID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话会话表';

-- 2. AI 对话消息表（增加 Token 消耗与计费金额字段支持账单溯源）
CREATE TABLE IF NOT EXISTS `tbl_ai_message` (
    `message_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息主键',
    `conversation_id` VARCHAR(64) NOT NULL COMMENT '关联会话ID',
    `sender_type` VARCHAR(20) NOT NULL COMMENT '发送者角色: USER / AI / SYSTEM',
    `content` MEDIUMTEXT NOT NULL COMMENT '消息文本内容',
    `intent_type` VARCHAR(30) DEFAULT 'GENERAL' COMMENT '意图类型: GENERAL / CAMPUS_RAG / PERSONAL_DATA / SENSITIVE_BLOCKED',
    `prompt_tokens` INT NOT NULL DEFAULT 0 COMMENT '输入提示词Token用量',
    `completion_tokens` INT NOT NULL DEFAULT 0 COMMENT '输出回答Token用量',
    `cost_amount` DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT '消费虚拟货币金额',
    `transaction_no` VARCHAR(50) DEFAULT NULL COMMENT '关联银行交易流水号',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`message_id`),
    INDEX `idx_ai_msg_conv_time` (`conversation_id`, `created_at`),
    CONSTRAINT `fk_ai_msg_conv` FOREIGN KEY (`conversation_id`) REFERENCES `tbl_ai_conversation` (`conversation_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话消息表';

-- 3. 知识库文档表
CREATE TABLE IF NOT EXISTS `tbl_knowledge_document` (
    `doc_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文档主键',
    `title` VARCHAR(200) NOT NULL COMMENT '文档标题',
    `category` VARCHAR(50) NOT NULL DEFAULT '校园知识' COMMENT '分类: 银行财务/学籍管理/图书服务/校园生活/选课规程',
    `content` LONGTEXT NOT NULL COMMENT '文档原始内容全文',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DISABLED',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '导入时间',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`doc_id`),
    INDEX `idx_knowledge_category_status` (`category`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档表';

-- 4. 知识库分块切片表 (Chunk)
CREATE TABLE IF NOT EXISTS `tbl_knowledge_chunk` (
    `chunk_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分块主键',
    `doc_id` BIGINT NOT NULL COMMENT '关联文档主键',
    `chunk_index` INT NOT NULL COMMENT '切片顺序索引(从0开始)',
    `content` TEXT NOT NULL COMMENT '切片文本内容',
    `token_count` INT NOT NULL DEFAULT 0 COMMENT '分块字数/Token估算',
    `embedding` MEDIUMTEXT DEFAULT NULL COMMENT '嵌入向量JSON数据',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '分块生成时间',
    PRIMARY KEY (`chunk_id`),
    INDEX `idx_chunk_doc_index` (`doc_id`, `chunk_index`),
    CONSTRAINT `fk_chunk_doc` FOREIGN KEY (`doc_id`) REFERENCES `tbl_knowledge_document` (`doc_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库分块表';

-- 5. AI 回答引用来源表 (Citation)
CREATE TABLE IF NOT EXISTS `tbl_ai_citation` (
    `citation_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '引用记录主键',
    `message_id` BIGINT NOT NULL COMMENT '关联的消息ID',
    `chunk_id` BIGINT DEFAULT NULL COMMENT '关联的知识分块ID',
    `doc_title` VARCHAR(200) NOT NULL COMMENT '来源文档标题',
    `similarity_score` DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '检索相关度打分',
    `excerpt` TEXT DEFAULT NULL COMMENT '引文摘要片段',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    PRIMARY KEY (`citation_id`),
    INDEX `idx_citation_message` (`message_id`),
    CONSTRAINT `fk_citation_msg` FOREIGN KEY (`message_id`) REFERENCES `tbl_ai_message` (`message_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI回答引用来源表';

-- ==================== 预置校园知识库精选语料 ====================
INSERT INTO `tbl_knowledge_document` (`doc_id`, `title`, `category`, `content`, `status`)
VALUES
(1, '校园银行与费用缴纳操作规程', '银行财务',
'【校园银行与缴费指南】\n1. 校园银行提供虚拟货币资金管理、转账、账单缴费和财务报销服务。\n2. 缴费流程：师生登录虚拟校园系统后，在主页点击“银行”进入校园银行模块；在“我的账单”中可查看未缴费用（包含学年学费、住宿费等）；勾选账单并核对金额后，输入6位数字支付密码即可完成缴费扣款。\n3. 账户安全：支付密码连续输错3次将锁定支付功能，需联系管理员重置；转账时需输入收款人一卡通号，确认转账金额后完成实时转账。\n4. 财务报销：教职工可在“财务报销”提交报销申请，填写事项、金额及事由，由管理员审核通过后资金将自动发放至申请人一卡通账户。',
'ACTIVE'),

(2, '学生学籍管理与信息变更细则', '学籍管理',
'【学籍管理规程】\n1. 学籍查询：学生可在“学籍”模块查看本人基本信息、院系、专业、学号、学籍状态以及所获荣誉奖励与资助记录。\n2. 信息修改申请：若个人姓名、政治面貌或联系方式等发生变更，需在系统中提交修改申请并上传佐证材料，由院系管理员或校教务处管理员审核通过后方可生效。\n3. 转专业与休复学：转专业申请通常在每学年春季学期第10-12周开放，学生在学籍系统中提交意向申请；因病休学或复学需提交医院证明并由教务处统一审批。\n4. 违纪与申诉：对学籍处理决定有异议的，可在收到通知起5个工作日内向学生申诉处理委员会提起书面申诉。',
'ACTIVE'),

(3, '图书馆借阅规则与图书预约说明', '图书服务',
'【图书馆借阅规范】\n1. 借阅权限：学生用户最多可同时借阅10本图书，借期为30天；教师用户最多可借阅20本图书，借期为60天。\n2. 续借规则：在图书未超期且无他人预约的情况下，可在线续借1次，续借期限为30天。\n3. 超期违约金：超期未还图书将按每天每本0.10元收取超期违约金，逾期未缴费将暂停借阅与预约权限。\n4. 图书预约：当所需图书处于“借出”状态时，可在系统中点击“预约”，图书归还后系统将保留3天，并发送通知提醒读者前往总服务台借取。\n5. 图书挂失：若图书不慎遗失，应及时在图书馆系统办理挂失并按规定进行赔偿。',
'ACTIVE'),

(4, '本科生选课制度与流程指引', '选课规程',
'【选课流程与制度】\n1. 选课轮次：每学期选课分为三轮。第一轮为预选（抽签制，不分先后）；第二轮为正选（先到先得，即选即中）；第三轮为退补选（开学前两周开放）。\n2. 学分限制：学生每学期选修课程总学分原则上不低于15学分，最高不超过32学分。\n3. 选课退选：退选截止时间为开学第二周周日24:00，逾期不得退选，未退选且未参加考核者成绩记为0分或旷考。\n4. 重修与补考：必修课不及格者可在开学初参加补考或在后续学期申请跟班重修；选修课不及格可申请重修或改选其他同类型课程。',
'ACTIVE'),

(5, '校园商店购物与售后退款指引', '校园生活',
'【校园商店操作指引】\n1. 选购与下单：在商店首页浏览商品，将心仪商品加入购物车后前往结算；下单成功后生成待支付订单。\n2. 支付时效：待支付订单有效期为30分钟，超时未支付订单将自动取消并释放占用的商品库存。\n3. 订单支付：商店支持使用校园银行虚拟账户进行在线结账，扣款成功后订单状态变更为“已支付”。\n4. 申请退款：针对“已支付”状态的订单，用户可提交整单退款申请并填写真实退款理由，经商店管理员审核通过后，资金将在第一时间原路退回至用户校园银行账户中。',
'ACTIVE')
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`);

-- 分块切片示例数据 (对应文档1-5的前置切片，供检索系统冷启动)
INSERT INTO `tbl_knowledge_chunk` (`chunk_id`, `doc_id`, `chunk_index`, `content`, `token_count`)
VALUES
(1, 1, 0, '【校园银行与缴费指南】校园银行提供虚拟货币资金管理、转账、账单缴费和财务报销服务。缴费流程：师生登录虚拟校园系统后，在主页点击“银行”进入校园银行模块；在“我的账单”中可查看未缴费用（包含学年学费、住宿费等）；勾选账单并核对金额后，输入6位数字支付密码即可完成缴费扣款。', 160),
(2, 1, 1, '【校园银行安全与转账】账户安全：支付密码连续输错3次将锁定支付功能，需联系管理员重置；转账时需输入收款人一卡通号，确认转账金额后完成实时转账。财务报销：教职工可在“财务报销”提交报销申请，填写事项、金额及事由，由管理员审核通过后资金将自动发放至申请人一卡通账户。', 155),
(3, 2, 0, '【学籍管理规程】学籍查询：学生可在“学籍”模块查看本人基本信息、院系、专业、学号、学籍状态以及所获荣誉奖励与资助记录。信息修改申请：若个人姓名、政治面貌或联系方式等发生变更，需在系统中提交修改申请并上传佐证材料，由院系管理员或校教务处管理员审核通过后方可生效。', 150),
(4, 2, 1, '【转专业与休学】转专业申请通常在每学年春季学期第10-12周开放，学生在学籍系统中提交意向申请；因病休学或复学需提交医院证明并由教务处统一审批。对学籍处理决定有异议的，可在收到通知起5个工作日内向学生申诉处理委员会提起书面申诉。', 130),
(5, 3, 0, '【图书馆借阅规范】借阅权限：学生用户最多可同时借阅10本图书，借期为30天；教师用户最多可借阅20本图书，借期为60天。续借规则：在图书未超期且无他人预约的情况下，可在线续借1次，续借期限为30天。超期未还图书将按每天每本0.10元收取超期违约金，逾期未缴费将暂停借阅与预约权限。', 160),
(6, 3, 1, '【图书预约与挂失】当所需图书处于“借出”状态时，可在系统中点击“预约”，图书归还后系统将保留3天，并发送通知提醒读者前往总服务台借取。若图书不慎遗失，应及时在图书馆系统办理挂失并按规定进行赔偿。', 115),
(7, 4, 0, '【选课流程与制度】选课轮次：每学期选课分为三轮。第一轮为预选（抽签制，不分先后）；第二轮为正选（先到先得，即选即中）；第三轮为退补选（开学前两周开放）。学分限制：学生每学期选修课程总学分原则上不低于15学分，最高不超过32学分。退选截止时间为开学第二周周日24:00。', 155),
(8, 5, 0, '【校园商店操作指引】选购与下单：在商店首页浏览商品加入购物车结算，待支付订单有效期为30分钟，超时未支付订单将自动取消并释放库存。商店支持使用校园银行虚拟账户结账。针对已支付订单可提交退款申请，经管理员审核后资金原路退回校园银行账户。', 150)
ON DUPLICATE KEY UPDATE `content`=VALUES(`content`);
