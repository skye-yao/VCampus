package handler;

import entity.AiConversation;
import entity.AiMessage;
import exception.BusinessException;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.BankService;
import service.ai.AiService;
import session.SessionManager;
import session.UserSession;

import java.util.List;

/**
 * AI 助手请求处理器
 */
public class AiHandler {

    private final AiService aiService;

    public AiHandler(BankService bankService) {
        this.aiService = new AiService(bankService);
    }

    public Message handle(Message request) {
        Message response = new Message(MessageType.RESPONSE, "ai", request.getAction());
        response.setUID(request.getUID());

        UserSession session = SessionManager.getInstance().getSession(request.getToken());
        if (session == null) {
            response.setCode(MessageCode.UNAUTHORIZED);
            response.setMessage("登录会话已失效，请重新登录");
            return response;
        }

        String userId = session.getUsername();
        String action = request.getAction() == null ? "" : request.getAction().trim().toUpperCase();

        try {
            switch (action) {
                case "AI_CONVERSATION_CREATE", "CONVERSATION_CREATE", "CREATE_CONVERSATION" -> {
                    String title = string(request, "title");
                    AiConversation conv = aiService.createConversation(userId, title);
                    response.putData("conversation", conv);
                    response.setMessage("会话创建成功");
                }
                case "AI_CONVERSATION_LIST", "CONVERSATION_LIST", "LIST_CONVERSATIONS" -> {
                    List<AiConversation> list = aiService.listConversations(userId);
                    response.putData("conversations", list);
                    response.setMessage("查询成功");
                }
                case "AI_CONVERSATION_DELETE", "CONVERSATION_DELETE", "DELETE_CONVERSATION" -> {
                    String conversationId = string(request, "conversationId");
                    boolean success = aiService.deleteConversation(userId, conversationId);
                    response.putData("success", success);
                    response.setMessage(success ? "会话删除成功" : "会话删除失败");
                }
                case "AI_MESSAGE_LIST", "MESSAGE_LIST", "LIST_MESSAGES" -> {
                    String conversationId = string(request, "conversationId");
                    List<AiMessage> messages = aiService.listMessages(conversationId);
                    response.putData("messages", messages);
                    response.setMessage("查询成功");
                }
                case "AI_RAG_CHAT", "CHAT", "RAG_CHAT" -> {
                    String conversationId = string(request, "conversationId");
                    String query = string(request, "query");
                    if (query == null || query.isBlank()) {
                        query = string(request, "content");
                    }
                    AiMessage reply = aiService.chat(userId, conversationId, query);
                    response.putData("reply", reply);
                    String convId = (reply != null && reply.getConversationId() != null)
                            ? reply.getConversationId() : conversationId;
                    response.putData("conversationId", convId);
                    String updatedTitle = aiService.getConversationTitle(convId);
                    if (updatedTitle != null) {
                        response.putData("conversationTitle", updatedTitle);
                    }
                    response.setMessage("回答生成完成");
                }
                case "AI_FEEDBACK_SUBMIT", "FEEDBACK" -> {
                    // 反馈提交桩
                    response.setMessage("感谢您的评价与反馈！");
                }
                default -> throw new BusinessException("暂不支持的 AI 操作: " + request.getAction());
            }

            response.setCode(MessageCode.SUCCESS);

        } catch (BusinessException e) {
            if (e.getMessage() != null && e.getMessage().contains("余额不足")) {
                response.setCode(MessageCode.AI_BALANCE_INSUFFICIENT);
            } else {
                response.setCode(MessageCode.BAD_REQUEST);
            }
            response.setMessage(e.getMessage());
        } catch (DatabaseException e) {
            response.setCode(MessageCode.ERROR);
            response.setMessage("数据库异常: " + e.getMessage());
        } catch (Exception e) {
            response.setCode(MessageCode.ERROR);
            response.setMessage("服务端内部错误: " + e.getMessage());
        }

        return response;
    }

    private String string(Message request, String key) {
        Object val = request.getData(key);
        return val == null ? null : String.valueOf(val).trim();
    }
}
