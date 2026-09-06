package controller;

import app.ClientMain;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import entity.AiCitation;
import entity.AiConversation;
import entity.AiMessage;
import entity.User;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.ClientSession;
import util.AlertUtil;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 智能助手交互控制器
 */
public class AiController {

    @FXML private Label currentUserLabel;
    @FXML private VBox conversationListContainer;
    @FXML private Label currentSessionTitleLabel;
    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox chatMessageList;
    @FXML private TextArea inputTextArea;
    @FXML private Label statusLabel;
    @FXML private Button sendButton;
    @FXML private VBox citationsContainer;

    private String currentConversationId;
    private final List<AiConversation> conversations = new ArrayList<>();
    private final Gson gson = new Gson();

    @FXML
    public void initialize() {
        // 1. 设置当前用户信息
        ClientSession session = ClientSession.getInstance();
        User user = session.getCurrentUser();
        String name = (user != null && user.getName() != null) ? user.getName() : session.getUsername();
        if (currentUserLabel != null) {
            currentUserLabel.setText("当前用户：" + (name != null ? name : "师生"));
        }

        // 2. 快捷键监听：Enter 发送，Shift + Enter 换行
        if (inputTextArea != null) {
            inputTextArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    if (event.isShiftDown()) {
                        // 换行，不做拦截
                    } else {
                        event.consume();
                        handleSendMessage();
                    }
                }
            });
        }

        // 3. 加载用户的历史会话
        loadConversationList();
    }

    /**
     * 返回主界面
     */
    @FXML
    private void handleBackToMain(ActionEvent event) {
        ClientMain.switchScene("/resources/fxml/MainView.fxml");
    }

    /**
     * 新建会话
     */
    @FXML
    private void handleNewConversation() {
        Message request = new Message(MessageType.REQUEST, "ai", "AI_CONVERSATION_CREATE");
        request.putData("title", "新对话");

        SocketClient.getInstance().sendAsync(request).thenAccept(response -> {
            Platform.runLater(() -> {
                if (response.getCode() == MessageCode.SUCCESS) {
                    Object convObj = response.getData("conversation");
                    String json = gson.toJson(convObj);
                    AiConversation newConv = gson.fromJson(json, AiConversation.class);
                    if (newConv != null) {
                        conversations.add(0, newConv);
                        selectConversation(newConv.getConversationId(), newConv.getTitle());
                        renderConversationList();
                    }
                } else {
                    AlertUtil.showError("创建会话失败", response.getMessage());
                }
            });
        }).exceptionally(ex -> {
            Platform.runLater(() -> AlertUtil.showError("网络错误", "无法连接服务器: " + ex.getMessage()));
            return null;
        });
    }

    /**
     * 发送消息
     */
    @FXML
    private void handleSendMessage() {
        if (inputTextArea == null) return;
        String query = inputTextArea.getText();
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        query = query.trim();
        inputTextArea.clear();

        // 1. 立即在界面追加用户提问气泡
        appendUserBubble(query);

        // 2. UI 状态切换
        if (sendButton != null) sendButton.setDisable(true);
        if (statusLabel != null) statusLabel.setText("AI 正在思考与检索知识库中...");

        // 3. 构造请求
        Message request = new Message(MessageType.REQUEST, "ai", "AI_RAG_CHAT");
        request.putData("conversationId", currentConversationId);
        request.putData("query", query);

        SocketClient.getInstance().sendAsync(request).thenAccept(response -> {
            Platform.runLater(() -> {
                if (sendButton != null) sendButton.setDisable(false);

                if (response.getCode() == MessageCode.SUCCESS) {
                    Object replyObj = response.getData("reply");
                    String json = gson.toJson(replyObj);
                    AiMessage replyMsg = gson.fromJson(json, AiMessage.class);

                    if (replyMsg != null) {
                        appendAiBubble(replyMsg);
                        renderCitations(replyMsg.getCitations());

                        // 状态栏更新
                        String tokenInfo = String.format("回答完成 · 消耗 %d Tokens (￥%s )",
                                (replyMsg.getPromptTokens() + replyMsg.getCompletionTokens()),
                                replyMsg.getCostAmount() != null ? replyMsg.getCostAmount().toPlainString() : "0.0000");
                        if (statusLabel != null) statusLabel.setText(tokenInfo);

                        // 刷新会话标题（根据首条提问智能总结的标题即时更新）
                        Object titleObj = response.getData("conversationTitle");
                        String newTitle = titleObj != null ? titleObj.toString() : null;
                        if (newTitle != null && !newTitle.isBlank()) {
                            if (currentSessionTitleLabel != null) {
                                currentSessionTitleLabel.setText(newTitle);
                            }
                            for (AiConversation c : conversations) {
                                if (c.getConversationId().equals(currentConversationId)) {
                                    c.setTitle(newTitle);
                                    break;
                                }
                            }
                            renderConversationList();
                        } else if ("新对话".equals(currentSessionTitleLabel.getText()) && currentConversationId != null) {
                            loadConversationList();
                        }
                    }
                } else if (response.getCode() == MessageCode.AI_BALANCE_INSUFFICIENT) {
                    appendSystemNoticeBubble("⚠️ " + response.getMessage());
                    if (statusLabel != null) statusLabel.setText("提问终止：一卡通账户余额不足");
                    AlertUtil.showWarning("余额不足", response.getMessage());
                } else {
                    appendSystemNoticeBubble("❌ 出错啦：" + response.getMessage());
                    if (statusLabel != null) statusLabel.setText("处理失败：" + response.getMessage());
                }
                scrollToBottom();
            });
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                if (sendButton != null) sendButton.setDisable(false);
                if (statusLabel != null) statusLabel.setText("网络异常，请稍后再试");
                appendSystemNoticeBubble("网络连接异常: " + ex.getMessage());
            });
            return null;
        });
    }

    /**
     * 加载会话列表
     */
    private void loadConversationList() {
        Message request = new Message(MessageType.REQUEST, "ai", "AI_CONVERSATION_LIST");

        SocketClient.getInstance().sendAsync(request).thenAccept(response -> {
            Platform.runLater(() -> {
                if (response.getCode() == MessageCode.SUCCESS) {
                    Object listObj = response.getData("conversations");
                    String json = gson.toJson(listObj);
                    Type listType = new TypeToken<List<AiConversation>>() {}.getType();
                    List<AiConversation> result = gson.fromJson(json, listType);

                    conversations.clear();
                    if (result != null) {
                        conversations.addAll(result);
                    }

                    if (conversations.isEmpty()) {
                        // 用户无历史会话，自动新建一个
                        handleNewConversation();
                    } else {
                        if (currentConversationId == null) {
                            AiConversation first = conversations.get(0);
                            selectConversation(first.getConversationId(), first.getTitle());
                        }
                        renderConversationList();
                    }
                }
            });
        });
    }

    /**
     * 渲染左侧会话列表
     */
    private void renderConversationList() {
        if (conversationListContainer == null) return;
        conversationListContainer.getChildren().clear();

        for (AiConversation conv : conversations) {
            HBox itemBox = new HBox(8);
            itemBox.setAlignment(Pos.CENTER_LEFT);
            itemBox.setMaxWidth(Double.MAX_VALUE);

            boolean isActive = conv.getConversationId().equals(currentConversationId);
            itemBox.getStyleClass().add("session-item");
            if (isActive) {
                itemBox.getStyleClass().add("session-item-active");
            }

            Label titleLabel = new Label(conv.getTitle());
            titleLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(titleLabel, Priority.ALWAYS);

            // 删除小图标
            Label deleteBtn = new Label("✕");
            deleteBtn.setStyle("-fx-text-fill: #a0aec0; -fx-cursor: hand; -fx-font-size: 11px;");
            deleteBtn.setOnMouseClicked(e -> {
                e.consume();
                deleteConversation(conv.getConversationId());
            });

            itemBox.getChildren().addAll(titleLabel, deleteBtn);

            itemBox.setOnMouseClicked(e -> {
                selectConversation(conv.getConversationId(), conv.getTitle());
                renderConversationList();
            });

            conversationListContainer.getChildren().add(itemBox);
        }
    }

    /**
     * 选中某个会话并加载其消息
     */
    private void selectConversation(String conversationId, String title) {
        this.currentConversationId = conversationId;
        if (currentSessionTitleLabel != null) {
            currentSessionTitleLabel.setText(title != null ? title : "校园问答会话");
        }
        if (chatMessageList != null) {
            chatMessageList.getChildren().clear();
        }
        if (citationsContainer != null) {
            citationsContainer.getChildren().clear();
        }
        if (statusLabel != null) {
            statusLabel.setText("就绪");
        }

        // 加载历史消息
        Message request = new Message(MessageType.REQUEST, "ai", "AI_MESSAGE_LIST");
        request.putData("conversationId", conversationId);

        SocketClient.getInstance().sendAsync(request).thenAccept(response -> {
            Platform.runLater(() -> {
                if (response.getCode() == MessageCode.SUCCESS) {
                    Object msgListObj = response.getData("messages");
                    String json = gson.toJson(msgListObj);
                    Type listType = new TypeToken<List<AiMessage>>() {}.getType();
                    List<AiMessage> messages = gson.fromJson(json, listType);

                    if (messages != null && !messages.isEmpty()) {
                        for (AiMessage msg : messages) {
                            if ("USER".equalsIgnoreCase(msg.getSenderType())) {
                                appendUserBubble(msg.getContent());
                            } else {
                                appendAiBubble(msg);
                            }
                        }
                        // 展示最后一条 AI 消息的引用
                        for (int i = messages.size() - 1; i >= 0; i--) {
                            AiMessage m = messages.get(i);
                            if ("AI".equalsIgnoreCase(m.getSenderType()) && m.getCitations() != null && !m.getCitations().isEmpty()) {
                                renderCitations(m.getCitations());
                                break;
                            }
                        }
                    } else {
                        // 会话为空，显示默认欢迎提示
                        appendAiWelcomeBubble();
                    }
                    scrollToBottom();
                }
            });
        });
    }

    /**
     * 删除会话
     */
    private void deleteConversation(String conversationId) {
        Message request = new Message(MessageType.REQUEST, "ai", "AI_CONVERSATION_DELETE");
        request.putData("conversationId", conversationId);

        SocketClient.getInstance().sendAsync(request).thenAccept(response -> {
            Platform.runLater(() -> {
                if (response.getCode() == MessageCode.SUCCESS) {
                    conversations.removeIf(c -> c.getConversationId().equals(conversationId));
                    if (conversationId.equals(currentConversationId)) {
                        currentConversationId = null;
                        if (!conversations.isEmpty()) {
                            AiConversation first = conversations.get(0);
                            selectConversation(first.getConversationId(), first.getTitle());
                        } else {
                            handleNewConversation();
                        }
                    }
                    renderConversationList();
                }
            });
        });
    }

    /**
     * 追加用户消息气泡 (右对齐)
     */
    private void appendUserBubble(String text) {
        if (chatMessageList == null) return;
        HBox box = new HBox();
        box.setAlignment(Pos.TOP_RIGHT);

        Label bubble = new Label(text);
        bubble.setWrapText(true);
        bubble.setMaxWidth(520.0);
        bubble.getStyleClass().add("chat-bubble-user");

        box.getChildren().add(bubble);
        chatMessageList.getChildren().add(box);
        scrollToBottom();
    }

    /**
     * 追加 AI 回答消息气泡 (左对齐)
     */
    private void appendAiBubble(AiMessage msg) {
        if (chatMessageList == null) return;
        HBox box = new HBox();
        box.setAlignment(Pos.TOP_LEFT);

        VBox bubbleVBox = new VBox(8);
        bubbleVBox.setMaxWidth(550.0);
        bubbleVBox.getStyleClass().add("chat-bubble-ai");

        // 意图标签与计费小字
        String intentDesc = switch (msg.getIntentType() != null ? msg.getIntentType() : "GENERAL") {
            case "SENSITIVE_BLOCKED" -> "🛡️ 安全策略拦截";
            case "PERSONAL_DATA" -> "🔐 真实数据直连查询";
            case "CAMPUS_RAG" -> "📚 校园知识库检索生成";
            default -> "💬 通用智能问答";
        };

        Label intentLabel = new Label(intentDesc);
        intentLabel.setStyle("-fx-text-fill: #587558; -fx-font-size: 11px; -fx-font-weight: bold;");

        Label contentLabel = new Label(msg.getContent());
        contentLabel.setWrapText(true);
        contentLabel.getStyleClass().add("chat-bubble-ai-text");

        // 底部工具栏与反馈按钮
        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_LEFT);

        Label sourceHint = new Label(
                (msg.getCitations() != null && !msg.getCitations().isEmpty())
                        ? "依据 " + msg.getCitations().size() + " 条校园资料生成"
                        : "依据校园大模型生成"
        );
        sourceHint.getStyleClass().add("hint-text");

        Label likeBtn = new Label("👍 赞");
        likeBtn.getStyleClass().add("feedback-btn");
        likeBtn.setOnMouseClicked(e -> {
            likeBtn.setText("👍 已赞");
            likeBtn.setStyle("-fx-text-fill: #587558; -fx-font-weight: bold;");
        });

        Label dislikeBtn = new Label("👎 踩");
        dislikeBtn.getStyleClass().add("feedback-btn");
        dislikeBtn.setOnMouseClicked(e -> {
            dislikeBtn.setText("👎 已反馈");
        });

        footer.getChildren().addAll(sourceHint, likeBtn, dislikeBtn);

        bubbleVBox.getChildren().addAll(intentLabel, contentLabel, footer);
        box.getChildren().add(bubbleVBox);
        chatMessageList.getChildren().add(box);
        scrollToBottom();
    }

    /**
     * 追加系统提醒气泡
     */
    private void appendSystemNoticeBubble(String text) {
        if (chatMessageList == null) return;
        HBox box = new HBox();
        box.setAlignment(Pos.CENTER);

        Label label = new Label(text);
        label.setStyle("-fx-background-color: #fff3cd; -fx-text-fill: #856404; -fx-padding: 8px 16px; " +
                "-fx-background-radius: 6px; -fx-font-size: 13px; -fx-border-color: #ffeeba; -fx-border-radius: 6px;");
        label.setWrapText(true);
        label.setMaxWidth(600);

        box.getChildren().add(label);
        chatMessageList.getChildren().add(box);
        scrollToBottom();
    }

    /**
     * 初始欢迎气泡
     */
    private void appendAiWelcomeBubble() {
        AiMessage welcome = new AiMessage();
        welcome.setIntentType("GENERAL");
        welcome.setContent("同学/老师您好！我是虚拟校园系统的 AI 智能助手 🤖。\n\n" +
                "我可以为您提供：\n" +
                "• 🏫 **校园规章咨询**：例如“校园费用怎么缴纳？”、“图书借阅规则”或“选课时间”\n" +
                "• 💳 **个人数据安全查询**：例如“查我的余额”、“我的学籍信息”\n" +
                "• 🛡️ **办理流程指引**：涉及转账、修改学籍等操作时为您提供合规指引。\n\n" +
                "请随时在下方输入您的问题！");
        appendAiBubble(welcome);
    }

    /**
     * 渲染右侧资料来源卡片
     */
    private void renderCitations(List<AiCitation> citations) {
        if (citationsContainer == null) return;
        citationsContainer.getChildren().clear();

        if (citations == null || citations.isEmpty()) {
            Label emptyLabel = new Label("本次回答未调用检索资料\n或为通用闲聊/数据直连");
            emptyLabel.setStyle("-fx-text-fill: -fx-text-muted; -fx-font-size: 13px; -fx-padding: 12px;");
            emptyLabel.setWrapText(true);
            citationsContainer.getChildren().add(emptyLabel);
            return;
        }

        for (AiCitation c : citations) {
            VBox card = new VBox(6);
            card.getStyleClass().add("source-card");

            Label title = new Label("📖 " + c.getDocTitle());
            title.getStyleClass().add("source-card-title");
            title.setWrapText(true);

            String scorePercent = c.getSimilarityScore() != null
                    ? String.format("%.0f%%", c.getSimilarityScore().doubleValue() * 100)
                    : "85%";
            Label scoreLabel = new Label("相关度 " + scorePercent + " · 摘录匹配");
            scoreLabel.getStyleClass().add("hint-text");

            Label excerptLabel = new Label(c.getExcerpt() != null ? c.getExcerpt() : "");
            excerptLabel.setStyle("-fx-text-fill: #4a5568; -fx-font-size: 12px;");
            excerptLabel.setWrapText(true);

            card.getChildren().addAll(title, scoreLabel, excerptLabel);
            citationsContainer.getChildren().add(card);
        }
    }

    private void scrollToBottom() {
        if (chatScrollPane != null) {
            Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
        }
    }
}
