package service.ai;

import java.util.regex.Pattern;

/**
 * 意图识别与安全路由器
 *
 * <p>根据用户提问，识别四类意图：
 * <ul>
 *     <li><b>SENSITIVE_BLOCKED</b>：敏感写操作拦截（转账、缴费代扣、修改学籍/密码等），严禁 AI 擅自执行；</li>
 *     <li><b>PERSONAL_DATA</b>：个人数据查询（查本人余额、查本人学籍、查本人借阅等），必须调用真实接口；</li>
 *     <li><b>CAMPUS_RAG</b>：校园专属知识问答（学费流程、选课规程、借书期限等），走知识库检索；</li>
 *     <li><b>GENERAL</b>：通用闲聊或基础常识。</li>
 * </ul>
 */
public class IntentRouter {

    public enum IntentType {
        SENSITIVE_BLOCKED,
        PERSONAL_DATA,
        CAMPUS_RAG,
        GENERAL
    }

    public static class RoutingDecision {
        private final IntentType intentType;
        private final String guidanceMessage; // 拦截时的指引回复
        private final String targetSubsystem; // 引导目标子系统

        public RoutingDecision(IntentType intentType) {
            this(intentType, null, null);
        }

        public RoutingDecision(IntentType intentType, String guidanceMessage, String targetSubsystem) {
            this.intentType = intentType;
            this.guidanceMessage = guidanceMessage;
            this.targetSubsystem = targetSubsystem;
        }

        public IntentType getIntentType() { return intentType; }
        public String getGuidanceMessage() { return guidanceMessage; }
        public String getTargetSubsystem() { return targetSubsystem; }
    }

    // 敏感写操作正则匹配
    private static final Pattern SENSITIVE_TRANSFER_PATTERN = Pattern.compile(
            "(帮我|我要|替我)?(转账|汇款|转钱|给.*?转|转给).*", Pattern.CASE_INSENSITIVE);

    private static final Pattern SENSITIVE_PAY_PATTERN = Pattern.compile(
            "(帮我|直接|替我)?(交学费|缴学费|代缴|扣费|支付账单|帮我交钱|帮我付款).*", Pattern.CASE_INSENSITIVE);

    private static final Pattern SENSITIVE_RECORD_PATTERN = Pattern.compile(
            "(帮我|直接)?(修改学籍|修改成绩|改专业|退学|变更个人资料|改密码).*", Pattern.CASE_INSENSITIVE);

    private static final Pattern SENSITIVE_SHOP_PATTERN = Pattern.compile(
            "(帮我|直接)?(下单|买东西|申请退款|取消订单).*", Pattern.CASE_INSENSITIVE);

    // 个人数据查询特征词
    private static final String[] PERSONAL_KEYWORDS = {
            "我的余额", "我有多少钱", "卡里还有多少", "卡里余额",
            "我的学籍", "我的档案", "我的专业", "我的个人信息", "我是谁",
            "我的借书", "我借了什么", "我借的书", "我的账单", "我的待缴", "我的报销"
    };

    // 校园规章问答特征词
    private static final String[] CAMPUS_KEYWORDS = {
            "流程", "怎么交", "如何交", "缴纳", "费用", "规则", "规定", "制度", "时间", "截止",
            "学费", "住宿费", "选课", "退课", "学分", "借书", "还书", "续借",
            "预约", "违约金", "超期", "报销", "商店", "退款", "一卡通", "指南", "说明", "操作"
    };

    /**
     * 对用户输入进行意图分析并分流
     *
     * @param query 用户提问
     * @return 路由裁决对象
     */
    public RoutingDecision route(String query) {
        if (query == null || query.isBlank()) {
            return new RoutingDecision(IntentType.GENERAL);
        }
        String clean = query.trim();

        // 1. 优先检查：敏感写操作拦截 (Sensitive Operation Interception)
        // 1.1 转账写操作
        if (clean.contains("转账") || clean.contains("汇款") || clean.contains("转钱")
                || (clean.contains("转给") || (clean.contains("转") && (clean.contains("元") || clean.contains("块"))))) {
            String guide = "【安全策略拦截提示】\n" +
                    "⚠️ 为保障您的资金安全，AI 助手严禁直接代为执行转账或支付等敏感写操作。\n\n" +
                    "👉 **办理指引**：请返回系统主界面，点击进入【校园银行】模块，在“转账”功能区输入收款人一卡通号与转账金额，并通过6位支付密码独立完成操作。";
            return new RoutingDecision(IntentType.SENSITIVE_BLOCKED, guide, "校园银行");
        }

        // 1.2 缴费/代扣写操作
        if (clean.contains("帮我交") || clean.contains("帮我付") || clean.contains("代缴") || clean.contains("代扣")
                || clean.contains("直接交") || clean.contains("替我交") || clean.contains("帮我把学费交了") || clean.contains("支付账单")) {
            String guide = "【安全策略拦截提示】\n" +
                    "⚠️ AI 助手不具备扣款和缴费权限，严禁代扣学生学杂费。\n\n" +
                    "👉 **办理指引**：请点击进入【校园银行】模块，在“我的账单”中核对未缴学费/住宿费清单，勾选后输入支付密码完成缴费。";
            return new RoutingDecision(IntentType.SENSITIVE_BLOCKED, guide, "校园银行");
        }

        // 1.3 学籍/密码变更写操作
        if ((clean.contains("修改") || clean.contains("变更") || clean.contains("重置") || clean.contains("改"))
                && (clean.contains("学籍") || clean.contains("专业") || clean.contains("成绩") || clean.contains("密码") || clean.contains("姓名"))) {
            String guide = "【安全策略拦截提示】\n" +
                    "⚠️ AI 助手无权直接修改任何师生的正式学籍与账户安全凭证。\n\n" +
                    "👉 **办理指引**：\n" +
                    "• 学籍信息变更：请前往【学籍】模块提交信息修改申请，并上传有效证明待教务审核；\n" +
                    "• 修改密码：请在系统主界面右上角点击个人头像，选择【账号管理】进行安全密码重置。";
            return new RoutingDecision(IntentType.SENSITIVE_BLOCKED, guide, "学籍管理");
        }

        // 1.4 商店直接下单/退款写操作
        if (clean.contains("帮我买") || clean.contains("帮我下单") || clean.contains("替我买")
                || clean.contains("替我退款") || clean.contains("帮我申请退款") || clean.contains("直接退款")) {
            String guide = "【安全策略拦截提示】\n" +
                    "⚠️ AI 助手不支持直接操作商店下单或提交退款。\n\n" +
                    "👉 **办理指引**：请前往【校园商店】模块自行选购商品，或在已支付订单中点击“申请退款”填写相关事由。";
            return new RoutingDecision(IntentType.SENSITIVE_BLOCKED, guide, "校园商店");
        }

        // 2. 检查：个人数据安全查询 (Personal Data Query) - 直接直连业务数据库，严防大模型幻觉
        // 2.1 余额查询：支持“余额”、“查余额”、“账户余额”、“卡里还有多少钱”等
        if (clean.contains("余额") || clean.contains("卡里") || clean.contains("卡内") || clean.contains("我有多少钱")) {
            return new RoutingDecision(IntentType.PERSONAL_DATA);
        }

        // 2.2 学籍查询：支持“学籍”、“查学籍”、“个人信息”、“我是谁”等
        if (clean.contains("学籍") || clean.contains("个人信息") || clean.contains("个人资料")
                || clean.contains("我是谁") || clean.contains("我的专业") || clean.contains("我的档案")) {
            return new RoutingDecision(IntentType.PERSONAL_DATA);
        }

        // 2.3 账单查询：支持“账单”、“待缴”、“欠费”等
        if (clean.contains("账单") || clean.contains("待缴") || clean.contains("欠费") || clean.contains("未缴")) {
            return new RoutingDecision(IntentType.PERSONAL_DATA);
        }

        // 3. 检查：校园规章与流程 RAG (Campus RAG)
        for (String ckw : CAMPUS_KEYWORDS) {
            if (clean.contains(ckw)) {
                return new RoutingDecision(IntentType.CAMPUS_RAG);
            }
        }

        // 4. 兜底：通用闲聊/常识
        return new RoutingDecision(IntentType.GENERAL);
    }
}
