package service.ai;

import dao.AiCitationDAO;
import dao.AiConversationDAO;
import dao.AiMessageDAO;
import dao.KnowledgeDAO;
import dao.KnowledgeDAO.ChunkItem;
import dao.UserDAO;
import entity.*;
import exception.BusinessException;
import exception.DatabaseException;
import service.BankService;
import util.DBUtil;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * AI 助手核心调度服务（基于 LangChain4j）
 *
 * <p>负责串联：
 * 1. 银行余额前置校验
 * 2. 意图识别路由
 * 3. 知识库检索
 * 4. LangChain4j 大模型调用
 * 5. Token 计费与银行扣款
 * 6. 对话与引用持久化
 */
public class AiService {

    private final AiConversationDAO conversationDAO = new AiConversationDAO();
    private final AiMessageDAO messageDAO = new AiMessageDAO();
    private final AiCitationDAO citationDAO = new AiCitationDAO();
    private final KnowledgeDAO knowledgeDAO = new KnowledgeDAO();
    private final UserDAO userDAO = new UserDAO();
    private final BankService bankService;

    private final IntentRouter intentRouter = new IntentRouter();
    private final SimpleVectorRetriever vectorRetriever = new SimpleVectorRetriever();

    /** LangChain4j 大模型实例 */
    private ChatLanguageModel chatModel;
    /** 是否配置了有效的 API Key */
    private boolean modelAvailable = false;

    /** Token 费率：每 1000 tokens 消费 0.05 虚拟校园币 */
    private static final BigDecimal TOKEN_RATE = new BigDecimal("0.00005");

    public AiService(BankService bankService) {
        this.bankService = bankService;
        initModel();
    }

    /**
     * 初始化 LangChain4j ChatModel（从 server.properties 读取配置）
     */
    private void initModel() {
        String apiUrl = "https://api.deepseek.com/v1";
        String apiKey = "";
        String modelName = "deepseek-chat";

        // 1. 优先读取系统环境变量（如 AI_API_KEY 或 DEEPSEEK_API_KEY）
        String envKey = System.getenv("AI_API_KEY");
        if (envKey == null || envKey.isBlank()) {
            envKey = System.getenv("DEEPSEEK_API_KEY");
        }
        if (envKey != null && !envKey.isBlank()) {
            apiKey = envKey.trim();
        }

        // 2. 其次读取 server.properties 配置文件作为备选/默认配置
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream("resources/server.properties");
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String url = props.getProperty("ai.api.url", "").trim();
                // LangChain4j 需要 base URL（不含 /chat/completions 后缀）
                if (!url.isEmpty()) {
                    apiUrl = url.replace("/chat/completions", "");
                }
                if (apiKey.isEmpty()) {
                    String key = props.getProperty("ai.api.key", "").trim();
                    if (!key.isEmpty()) apiKey = key;
                }
                String model = props.getProperty("ai.model", "").trim();
                if (!model.isEmpty()) modelName = model;
            }
        } catch (Exception e) {
            System.err.println("[AI] 读取配置文件警告: " + e.getMessage());
        }

        if (!apiKey.isEmpty()) {
            try {
                this.chatModel = OpenAiChatModel.builder()
                        .baseUrl(apiUrl)
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .temperature(0.3)
                        .build();
                this.modelAvailable = true;
                System.out.println("[AI] LangChain4j ChatModel 初始化成功 -> " + modelName);
            } catch (Exception e) {
                System.err.println("[AI] LangChain4j 初始化失败: " + e.getMessage());
                this.modelAvailable = false;
            }
        } else {
            System.out.println("[AI] 未配置 API Key，将使用离线模式");
            this.modelAvailable = false;
        }
    }

    // ==================== 会话管理 ====================

    public AiConversation createConversation(String userId, String title) {
        String convId = "conv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AiConversation conv = new AiConversation(convId, userId,
                (title != null && !title.isBlank()) ? title : "新对话");
        try (Connection conn = DBUtil.getConnection()) {
            conversationDAO.create(conn, conv);
            return conv;
        } catch (SQLException e) {
            throw new DatabaseException("创建 AI 会话失败", e);
        }
    }

    public List<AiConversation> listConversations(String userId) {
        try (Connection conn = DBUtil.getConnection()) {
            return conversationDAO.findByUserId(conn, userId);
        } catch (SQLException e) {
            throw new DatabaseException("查询历史会话失败", e);
        }
    }

    public boolean deleteConversation(String userId, String conversationId) {
        try (Connection conn = DBUtil.getConnection()) {
            return conversationDAO.delete(conn, conversationId, userId);
        } catch (SQLException e) {
            throw new DatabaseException("删除会话失败", e);
        }
    }

    public List<entity.AiMessage> listMessages(String conversationId) {
        try (Connection conn = DBUtil.getConnection()) {
            List<entity.AiMessage> messages = messageDAO.findByConversationId(conn, conversationId);
            for (entity.AiMessage msg : messages) {
                if ("AI".equalsIgnoreCase(msg.getSenderType())) {
                    List<AiCitation> citations = citationDAO.findByMessageId(conn, msg.getMessageId());
                    msg.setCitations(citations);
                }
            }
            return messages;
        } catch (SQLException e) {
            throw new DatabaseException("查询会话消息失败", e);
        }
    }

    public String getConversationTitle(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        try (Connection conn = DBUtil.getConnection()) {
            AiConversation conv = conversationDAO.findById(conn, conversationId);
            return conv != null ? conv.getTitle() : null;
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * 根据用户首条提问智能总结生成会话标题（类似主流 AI 对话产品）
     */
    private String generateConversationTitle(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) return "新对话";
        String clean = userQuery.trim().replaceAll("[\\r\\n]+", " ");

        // 校园彩蛋会话专属标题
        if ((clean.contains("东南大学") || clean.contains("东大"))
                && (clean.contains("南京大学") || clean.contains("南大"))
                && (clean.contains("更好") || clean.contains("好") || clean.contains("强"))) {
            return "夹带私货环节(彩蛋)";
        }

        // 1. 若大模型可用且问题有一定长度，调用大模型做精炼标题总结
        if (modelAvailable && chatModel != null && clean.length() > 5) {
            try {
                ChatResponse resp = chatModel.chat(
                        SystemMessage.from("你是一个会话标题生成器。请用4到8个汉字精炼概括用户的问题，作为对话会话的简短标题。绝对不要输出任何标点符号、书名号、引号或多余文字。"),
                        UserMessage.from(clean)
                );
                String title = resp.aiMessage().text().trim().replaceAll("[\\p{P}\\p{S}\\s]", "");
                if (!title.isBlank() && title.length() <= 12) {
                    return title;
                }
            } catch (Exception e) {
                System.err.println("[AI] 智能标题生成失败，降级本地规则: " + e.getMessage());
            }
        }

        // 2. 本地降级：去除常见口癖前缀后截取前 10 个字符
        clean = clean.replaceAll("^(请问一下|请问|我想问一下|想问下|帮我查一下|帮我|麻烦问下|咨询一下|请教一下)", "").trim();
        if (clean.length() > 10) {
            clean = clean.substring(0, 10) + "...";
        }
        return clean.isEmpty() ? "校园问答" : clean;
    }

    // ==================== 核心问答 ====================

    /**
     * 核心问答处理：意图路由 + RAG 知识检索 + LangChain4j 生成 + Token 扣费
     */
    public entity.AiMessage chat(String userId, String conversationId, String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            throw new BusinessException("问题内容不能为空");
        }

        // 1. 银行余额前置校验
        BankAccount bankAccount = bankService.getAccount(userId);
        if (bankAccount.getBalance().compareTo(new BigDecimal("0.01")) < 0) {
            throw new BusinessException("校园卡余额不足（当前 ￥" + bankAccount.getBalance()
                    + "），请先前往校园银行充值！");
        }

        Connection conn = null;
        try {
            conn = DBUtil.getConnection();

            // 确保会话存在，若为首条消息则生成智能标题
            AiConversation currentConv = (conversationId != null && !conversationId.isBlank())
                    ? conversationDAO.findById(conn, conversationId) : null;
            if (currentConv == null) {
                String title = generateConversationTitle(userQuery);
                AiConversation newConv = createConversation(userId, title);
                conversationId = newConv.getConversationId();
            } else if ("新对话".equals(currentConv.getTitle()) || currentConv.getTitle().isBlank()) {
                // 首次在“新对话”中发言：自动总结生成智能会话标题（类似 ChatGPT / Claude）
                String newTitle = generateConversationTitle(userQuery);
                conversationDAO.updateTitle(conn, conversationId, newTitle);
            }

            // 持久化用户提问
            entity.AiMessage userMsg = new entity.AiMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setSenderType("USER");
            userMsg.setContent(userQuery);
            userMsg.setIntentType("USER");
            messageDAO.insert(conn, userMsg);

            // 2. 意图识别
            IntentRouter.RoutingDecision decision = intentRouter.route(userQuery);
            IntentRouter.IntentType intent = decision.getIntentType();

            entity.AiMessage aiReply = new entity.AiMessage();
            aiReply.setConversationId(conversationId);
            aiReply.setSenderType("AI");
            aiReply.setIntentType(intent.name());

            List<AiCitation> citations = new ArrayList<>();
            String replyContent;
            int promptTokens = 0, completionTokens = 0;
            BigDecimal costAmount = BigDecimal.ZERO;
            // 校园专属隐藏彩蛋：提问包含“东南大学”、“南京大学”、“更好”这三个关键字时触发趣味回复
            boolean isEasterEgg = (userQuery.contains("东南大学") || userQuery.contains("东大"))
                    && (userQuery.contains("南京大学") || userQuery.contains("南大"))
                    && (userQuery.contains("更好") || userQuery.contains("好") || userQuery.contains("强"));

            if (isEasterEgg) {
                replyContent = "你说的对，但是《东南大学》是由中华人民共和国教育部自主研发的一款全新开放世界冒险游戏。" +
                        "游戏发生在一个被称作「九龙湖」的幻想世界，在这里，被车南之神选中的人将被授予「糖鼠之眼」，导引科研之力。" +
                        "你将扮演一位名为「车兵」的神秘角色，在自由（并非）的旅行（并非）中邂逅性格各异、能力独特的同伴们，" +
                        "和他们一起击败强敌（指南京大学），找回失散的亲人（指国立中央大学）——同时，逐步发掘「科比天大」的真相。";
                promptTokens = 0;
                completionTokens = 0;
                costAmount = BigDecimal.ZERO;
                aiReply.setIntentType("GENERAL");
            } else {
                switch (intent) {
                case SENSITIVE_BLOCKED:
                    // 分支 1：敏感操作拦截，零费用
                    replyContent = decision.getGuidanceMessage();
                    aiReply.setIntentType("SENSITIVE_BLOCKED");
                    break;

                case PERSONAL_DATA:
                    // 分支 2：个人数据查询，直连真实数据库，免费
                    replyContent = queryPersonalRealData(userId, userQuery);
                    aiReply.setIntentType("PERSONAL_DATA");
                    break;

                case CAMPUS_RAG:
                case GENERAL:
                default:
                    // 分支 3：统一智能问答通道 —— 先检索潜在相关资料，交由大模型自主研判是否采纳
                    List<ChunkItem> allChunks = knowledgeDAO.findAllActiveChunksWithDoc(conn);
                    // 阈值放宽到 0.08，保证候选资料更全面
                    List<SimpleVectorRetriever.MatchResult> matched =
                            vectorRetriever.search(userQuery, allChunks, 3, 0.08);

                    if (!matched.isEmpty()) {
                        StringBuilder ctx = new StringBuilder();
                        List<AiCitation> candidateCitations = new ArrayList<>();
                        for (int i = 0; i < matched.size(); i++) {
                            ChunkItem chunk = matched.get(i).getChunk();
                            ctx.append(String.format("[%d] 《%s》: %s\n\n",
                                    i + 1, chunk.getDocTitle(), chunk.getContent()));

                            AiCitation cit = new AiCitation();
                            cit.setChunkId(chunk.getChunkId());
                            cit.setDocTitle(chunk.getDocTitle());
                            cit.setSimilarityScore(matched.get(i).getScoreAsBigDecimal());
                            String excerpt = chunk.getContent().length() > 80
                                    ? chunk.getContent().substring(0, 80) + "..."
                                    : chunk.getContent();
                            cit.setExcerpt(excerpt);
                            candidateCitations.add(cit);
                        }

                        String systemPrompt = "你是一个专业且贴心的虚拟校园AI智能助手。\n" +
                                "系统根据师生的问题，在校园规章与知识库中检索到了以下【参考资料】：\n" +
                                "--------------------------------------------------\n" +
                                ctx +
                                "--------------------------------------------------\n" +
                                "【大模型自主研判与回答规则】：\n" +
                                "1. 请仔细研读上述参考资料，自主判断其内容是否与师生的问题直接相关且能有效解答：\n" +
                                "   - 【情况A：参考资料相关且采纳】：请务必优先基于参考资料如实、条理清晰地解答，并在回答文本的最末尾单独一行输出标记：[RAG_USED:true]\n" +
                                "   - 【情况B：参考资料不相关或无帮助】（如日常闲聊、问候、写代码、常识百科，或资料无法回答此问题）：请自主忽略参考资料，直接以友好助手的身份亲切作答，切勿生搬硬套不相干的校园规章，并在回答文本的最末尾单独一行输出标记：[RAG_USED:false]\n" +
                                "2. 标记 [RAG_USED:true] 或 [RAG_USED:false] 必须单独放在回答的最后一行，不要添加其他符号。";

                        String[] llmResult = callLLM(systemPrompt, userQuery);
                        String rawContent = llmResult[0];
                        promptTokens = Integer.parseInt(llmResult[1]);
                        completionTokens = Integer.parseInt(llmResult[2]);
                        costAmount = calcCost(promptTokens + completionTokens);

                        boolean ragUsed = false;
                        if (rawContent.contains("[RAG_USED:true]")) {
                            ragUsed = true;
                            replyContent = rawContent.replace("[RAG_USED:true]", "").trim();
                        } else if (rawContent.contains("[RAG_USED:false]")) {
                            ragUsed = false;
                            replyContent = rawContent.replace("[RAG_USED:false]", "").trim();
                        } else {
                            // 兜底：若大模型未按格式输出标记，依据最高匹配分数判定
                            ragUsed = (matched.get(0).getScore() >= 0.22);
                            replyContent = rawContent.trim();
                        }

                        if (ragUsed) {
                            // 真的调用了知识库
                            aiReply.setIntentType("CAMPUS_RAG");
                            citations.addAll(candidateCitations);
                        } else {
                            // 大模型自主判定参考资料无关，按通用问答处理
                            aiReply.setIntentType("GENERAL");
                            citations.clear();
                        }
                    } else {
                        // 知识库完全无匹配，纯通用智能问答
                        String systemPrompt = "你是一个友好、专业的虚拟校园AI助手。请以亲切、耐心的语气回答师生的问题。";
                        String[] genResult = callLLM(systemPrompt, userQuery);
                        replyContent = genResult[0].replace("[RAG_USED:true]", "").replace("[RAG_USED:false]", "").trim();
                        promptTokens = Integer.parseInt(genResult[1]);
                        completionTokens = Integer.parseInt(genResult[2]);
                        costAmount = calcCost(promptTokens + completionTokens);
                        aiReply.setIntentType("GENERAL");
                        citations.clear();
                    }
                    break;
                }
            }

            String transactionNo = null;

            // 3. Token 扣费
            if (costAmount.compareTo(BigDecimal.ZERO) > 0) {
                String requestId = "AI-" + System.currentTimeMillis();
                String remark = "AI问答Token扣费 [P:" + promptTokens + ",C:" + completionTokens + "]";
                try {
                    transactionNo = bankService.deductAiFee(userId, costAmount, requestId, remark);
                } catch (BusinessException e) {
                    System.err.println("[AI] 扣费失败: " + e.getMessage());
                }
            }

            // 4. 持久化 AI 回复与引用
            aiReply.setContent(replyContent);
            aiReply.setPromptTokens(promptTokens);
            aiReply.setCompletionTokens(completionTokens);
            aiReply.setCostAmount(costAmount);
            aiReply.setTransactionNo(transactionNo);

            Long aiMsgId = messageDAO.insert(conn, aiReply);
            aiReply.setMessageId(aiMsgId);

            for (AiCitation cit : citations) {
                cit.setMessageId(aiMsgId);
                citationDAO.insert(conn, cit);
            }
            aiReply.setCitations(citations);

            conversationDAO.touch(conn, conversationId);
            return aiReply;

        } catch (SQLException e) {
            throw new DatabaseException("AI 问答处理异常", e);
        } finally {
            DBUtil.close(conn, null, null);
        }
    }

    /**
     * 调用大模型（LangChain4j / 离线降级）
     * @return [回复内容, promptTokens, completionTokens]
     */
    private String[] callLLM(String systemPrompt, String userMessage) {
        if (modelAvailable && chatModel != null) {
            try {
                ChatResponse response = chatModel.chat(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(userMessage)
                );
                String content = response.aiMessage().text();
                TokenUsage usage = response.tokenUsage();
                int pt = (usage != null && usage.inputTokenCount() != null) ? usage.inputTokenCount() : 0;
                int ct = (usage != null && usage.outputTokenCount() != null) ? usage.outputTokenCount() : 0;
                return new String[]{content, String.valueOf(pt), String.valueOf(ct)};
            } catch (Exception e) {
                System.err.println("[AI] LangChain4j 调用异常，降级离线: " + e.getMessage());
            }
        }
        // 离线降级
        String fallback = offlineReply(systemPrompt, userMessage);
        int pt = systemPrompt.length() + userMessage.length();
        return new String[]{fallback, String.valueOf(pt), String.valueOf(fallback.length())};
    }

    /** 计算 Token 费用 */
    private BigDecimal calcCost(int totalTokens) {
        BigDecimal cost = BigDecimal.valueOf(totalTokens).multiply(TOKEN_RATE)
                .setScale(4, RoundingMode.HALF_UP);
        if (cost.compareTo(new BigDecimal("0.0001")) < 0 && totalTokens > 0) {
            cost = new BigDecimal("0.0001");
        }
        return cost;
    }

    /**
     * 离线模式：无 API Key 时根据 RAG 上下文生成简单回答
     */
    private String offlineReply(String systemPrompt, String userMessage) {
        if (systemPrompt != null && systemPrompt.contains("【参考资料】")) {
            if (userMessage.contains("缴费") || userMessage.contains("学费") || userMessage.contains("选课")
                    || userMessage.contains("借书") || userMessage.contains("图书") || userMessage.contains("学籍")
                    || userMessage.contains("退款") || userMessage.contains("卡") || userMessage.contains("规则")
                    || userMessage.contains("流程") || userMessage.contains("费用") || userMessage.contains("充值")
                    || userMessage.contains("多少钱") || userMessage.contains("预约")) {
                int idx = systemPrompt.indexOf("【参考资料】");
                int endIdx = systemPrompt.indexOf("--------------------------------------------------");
                String ctx;
                if (endIdx > idx) {
                    ctx = systemPrompt.substring(idx, endIdx);
                } else {
                    ctx = systemPrompt.substring(idx);
                }
                ctx = ctx.replace("【参考资料】", "").replaceAll("\\[\\d+]", "").trim();
                if (ctx.length() > 260) ctx = ctx.substring(0, 260) + "...";
                return "根据校园官方知识库规程，为您解答如下：\n\n" + ctx + "\n\n[RAG_USED:true]";
            }
        }
        if (userMessage.contains("你好") || userMessage.contains("您好")) {
            return "你好！我是虚拟校园 AI 智能助手，很高兴为你服务！你可以向我咨询任何校园生活、规章、借书或选课相关的问题。\n\n[RAG_USED:false]";
        }
        return "我已收到您的问题。作为校园智能助手，建议您提问校园相关业务（例如'校园费用怎么缴纳'、'图书借阅规则'等）。\n\n[RAG_USED:false]";
    }

    /**
     * 个人数据查询（直连真实 DAO，不经过大模型）
     */
    private String queryPersonalRealData(String userId, String query) {
        StringBuilder sb = new StringBuilder();

        if (query.contains("余额") || query.contains("多少钱") || query.contains("卡里")) {
            BankAccount account = bankService.getAccount(userId);
            sb.append("【个人账户信息】\n");
            sb.append("• 一卡通号：").append(userId).append("\n");
            sb.append("• 当前余额：￥").append(account.getBalance()).append("\n");
            sb.append("• 账户状态：").append(account.getStatus().getDescription()).append("\n\n");
            sb.append("📌 以上数据直接来自校园银行数据库。");
        } else if (query.contains("学籍") || query.contains("信息") || query.contains("专业")) {
            try {
                User user = userDAO.findByUID(userId);
                if (user != null) {
                    sb.append("【学籍信息】\n");
                    sb.append("• 一卡通号：").append(user.getUID()).append("\n");
                    sb.append("• 姓名：").append(user.getName()).append("\n");
                    sb.append("• 角色：").append(user.getRole().getDescription()).append("\n");
                    sb.append("• 学院：").append(user.getCollege()).append("\n");
                    sb.append("• 专业：").append(user.getMajor()).append("\n\n");
                    sb.append("📌 以上数据直接来自学籍数据库。");
                } else {
                    sb.append("未查询到该学籍记录。");
                }
            } catch (SQLException e) {
                sb.append("查询学籍时出错：").append(e.getMessage());
            }
        } else if (query.contains("账单")) {
            try {
                List<FinanceBill> bills = bankService.listBills(userId, false);
                sb.append("【校园账单】\n");
                if (bills.isEmpty()) {
                    sb.append("您当前没有待缴账单。\n");
                } else {
                    for (FinanceBill b : bills) {
                        String status = "UNPAID".equals(b.getStatus()) ? "待缴费" : "已缴费";
                        sb.append(String.format("• %s | ￥%s | %s\n", b.getTitle(), b.getAmount(), status));
                    }
                }
                sb.append("\n📌 如需缴费请前往【校园银行】->【我的账单】。");
            } catch (Exception e) {
                sb.append("查询账单失败: ").append(e.getMessage());
            }
        } else {
            sb.append("支持查询：【余额】、【学籍】、【账单】。请直接提问如'查我的余额'。");
        }

        return sb.toString();
    }
}
