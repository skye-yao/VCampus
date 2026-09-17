package controller;

import entity.SystemNotification;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import service.NotificationClientService;

import java.util.*;
import java.util.function.Consumer;

public class NotificationCenterDialog {

    private final NotificationClientService service = new NotificationClientService();
    private final Consumer<String> onNavigate;
    private final Runnable onDataChanged;

    private Stage stage;
    private VBox listContainer;
    private Label statusLabel;
    private String currentFilter = "ALL"; // ALL, UNREAD, BANK, CHAT, REVIEW
    private List<SystemNotification> allNotifications = new ArrayList<>();

    public NotificationCenterDialog(Consumer<String> onNavigate, Runnable onDataChanged) {
        this.onNavigate = onNavigate;
        this.onDataChanged = onDataChanged;
    }

    public void show(Window owner) {
        stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("校园消息中心");

        VBox root = new VBox(14);
        root.setPadding(new Insets(18, 20, 18, 20));
        root.setStyle("-fx-background-color: #f8fafc;");

        // 1. 顶部标头
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        Label titleIcon = new Label("🔔");
        titleIcon.setStyle("-fx-font-size: 24px;");

        VBox titleBox = new VBox(2);
        Label titleLabel = new Label("校园消息中心");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        Label subtitleLabel = new Label("集中查看您的转账提醒、群聊动态与业务审核结果");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
        titleBox.getChildren().addAll(titleLabel, subtitleLabel);

        Region spacerTop = new Region();
        HBox.setHgrow(spacerTop, Priority.ALWAYS);

        Button markAllBtn = new Button("全部已读");
        markAllBtn.setStyle("-fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-font-size: 12px; -fx-cursor: hand; -fx-background-radius: 6px; -fx-padding: 6 12;");
        markAllBtn.setOnAction(e -> markAllRead());

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-font-size: 12px; -fx-cursor: hand; -fx-background-radius: 6px; -fx-padding: 6 12;");
        refreshBtn.setOnAction(e -> loadData());

        header.getChildren().addAll(titleIcon, titleBox, spacerTop, markAllBtn, refreshBtn);

        // 2. 分类过滤标签栏
        HBox filterBar = new HBox(8);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(4, 0, 4, 0));

        ToggleGroup filterGroup = new ToggleGroup();
        filterBar.getChildren().addAll(
                createFilterButton("全部", "ALL", filterGroup, true),
                createFilterButton("未读", "UNREAD", filterGroup, false),
                createFilterButton("财务转账", "BANK", filterGroup, false),
                createFilterButton("群聊/聊天", "CHAT", filterGroup, false),
                createFilterButton("业务审核", "REVIEW", filterGroup, false)
        );

        // 3. 消息列表滚动区域
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: #e2e8f0; -fx-border-radius: 8px;");
        scrollPane.setPrefHeight(380);

        listContainer = new VBox(10);
        listContainer.setPadding(new Insets(10));
        listContainer.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 8px;");
        scrollPane.setContent(listContainer);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // 4. 底部栏
        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("正在加载通知...");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        Region spacerBottom = new Region();
        HBox.setHgrow(spacerBottom, Priority.ALWAYS);

        Button closeBtn = new Button("关闭");
        closeBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-size: 12px; -fx-cursor: hand; -fx-background-radius: 6px; -fx-padding: 6 18;");
        closeBtn.setOnAction(e -> stage.close());

        footer.getChildren().addAll(statusLabel, spacerBottom, closeBtn);

        root.getChildren().addAll(header, filterBar, scrollPane, footer);

        Scene scene = new Scene(root, 680, 520);
        try {
            var cssUrl = getClass().getResource("/resources/css/style.css");
            if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());
        } catch (Exception ignored) {}

        stage.setScene(scene);
        stage.setMinWidth(620);
        stage.setMinHeight(460);
        stage.show();

        loadData();
    }

    private ToggleButton createFilterButton(String text, String filterKey, ToggleGroup group, boolean selected) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.setSelected(selected);
        btn.setUserData(filterKey);
        btn.setStyle("-fx-background-radius: 16px; -fx-padding: 4 12; -fx-font-size: 12px; -fx-cursor: hand;");
        btn.selectedProperty().addListener((obs, oldV, isSelected) -> {
            if (isSelected) {
                currentFilter = filterKey;
                renderFilteredList();
            }
        });
        return btn;
    }

    private void loadData() {
        listContainer.getChildren().clear();
        Label loadingLabel = new Label("正在拉取最新消息通知...");
        loadingLabel.setStyle("-fx-text-fill: #94a3b8; -fx-padding: 20;");
        listContainer.getChildren().add(loadingLabel);

        service.list(false, 100, 0).thenAccept(list -> Platform.runLater(() -> {
            allNotifications = list != null ? list : new ArrayList<>();
            renderFilteredList();
            if (onDataChanged != null) onDataChanged.run();
        })).exceptionally(err -> {
            Platform.runLater(() -> {
                listContainer.getChildren().clear();
                Label errLabel = new Label("加载失败: " + err.getMessage());
                errLabel.setStyle("-fx-text-fill: #ef4444; -fx-padding: 20;");
                listContainer.getChildren().add(errLabel);
                statusLabel.setText("数据加载出错");
            });
            return null;
        });
    }

    private void renderFilteredList() {
        listContainer.getChildren().clear();

        List<SystemNotification> filtered = allNotifications.stream().filter(n -> {
            if ("UNREAD".equals(currentFilter)) return !n.isRead();
            if ("BANK".equals(currentFilter)) return "BANK".equalsIgnoreCase(n.getCategory());
            if ("CHAT".equals(currentFilter)) return "CHAT".equalsIgnoreCase(n.getCategory());
            if ("REVIEW".equals(currentFilter)) return "REVIEW".equalsIgnoreCase(n.getCategory());
            return true;
        }).toList();

        long unreadCount = allNotifications.stream().filter(n -> !n.isRead()).count();
        statusLabel.setText("共 " + allNotifications.size() + " 条通知，" + unreadCount + " 条未读");

        if (filtered.isEmpty()) {
            VBox emptyBox = new VBox(10);
            emptyBox.setAlignment(Pos.CENTER);
            emptyBox.setPadding(new Insets(40, 0, 40, 0));
            Label emptyIcon = new Label("📭");
            emptyIcon.setStyle("-fx-font-size: 32px;");
            Label emptyText = new Label("暂无相关消息通知");
            emptyText.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14px;");
            emptyBox.getChildren().addAll(emptyIcon, emptyText);
            listContainer.getChildren().add(emptyBox);
            return;
        }

        for (SystemNotification n : filtered) {
            listContainer.getChildren().add(createNotificationCard(n));
        }
    }

    private VBox createNotificationCard(SystemNotification n) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(10, 14, 10, 14));
        boolean unread = !n.isRead();
        card.setStyle(unread
                ? "-fx-background-color: #f0fdf4; -fx-border-color: #bbf7d0; -fx-border-radius: 8px; -fx-background-radius: 8px;"
                : "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        // 头部：徽章 + 标题 + 未读红点 + 时间
        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label badge = new Label(badgeText(n.getCategory()));
        badge.getStyleClass().addAll("notice-badge", badgeStyleClass(n.getCategory()));

        Label title = new Label(n.getTitle());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #1e293b;");

        if (unread) {
            Label unreadDot = new Label("● 未读");
            unreadDot.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 11px; -fx-font-weight: bold;");
            topRow.getChildren().addAll(badge, title, unreadDot);
        } else {
            topRow.getChildren().addAll(badge, title);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label timeLabel = new Label(n.getCreatedAt() != null ? n.getCreatedAt() : "");
        timeLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px;");

        topRow.getChildren().addAll(spacer, timeLabel);

        // 内容
        Label content = new Label(n.getContent());
        content.setWrapText(true);
        content.setStyle("-fx-text-fill: #334155; -fx-font-size: 13px;");

        // 底部动作栏
        HBox actionRow = new HBox(10);
        actionRow.setAlignment(Pos.CENTER_RIGHT);

        if (unread) {
            Hyperlink readLink = new Hyperlink("标为已读");
            readLink.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px; -fx-underline: false;");
            readLink.setOnAction(e -> markSingleRead(n.getId()));
            actionRow.getChildren().add(readLink);
        }

        Hyperlink delLink = new Hyperlink("删除");
        delLink.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px; -fx-underline: false;");
        delLink.setOnAction(e -> deleteSingle(n.getId()));
        actionRow.getChildren().add(delLink);

        if (n.getLinkAction() != null && !n.getLinkAction().isBlank()) {
            Button jumpBtn = new Button("前往处理 >");
            jumpBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-size: 11px; -fx-cursor: hand; -fx-background-radius: 4px; -fx-padding: 3 10;");
            jumpBtn.setOnAction(e -> {
                if (unread) {
                    service.markRead(n.getId()).thenRun(() -> {});
                }
                stage.close();
                if (onNavigate != null) {
                    onNavigate.accept(n.getLinkAction());
                }
            });
            actionRow.getChildren().add(jumpBtn);
        }

        card.getChildren().addAll(topRow, content, actionRow);
        return card;
    }

    private void markSingleRead(Long id) {
        if (id == null) return;
        service.markRead(id).thenAccept(ok -> Platform.runLater(() -> {
            for (SystemNotification n : allNotifications) {
                if (Objects.equals(n.getId(), id)) {
                    n.setIsRead(true);
                    break;
                }
            }
            renderFilteredList();
            if (onDataChanged != null) onDataChanged.run();
        }));
    }

    private void markAllRead() {
        service.markAllRead().thenAccept(count -> Platform.runLater(() -> {
            for (SystemNotification n : allNotifications) {
                n.setIsRead(true);
            }
            renderFilteredList();
            if (onDataChanged != null) onDataChanged.run();
        }));
    }

    private void deleteSingle(Long id) {
        if (id == null) return;
        service.delete(id).thenAccept(ok -> Platform.runLater(() -> {
            allNotifications.removeIf(n -> Objects.equals(n.getId(), id));
            renderFilteredList();
            if (onDataChanged != null) onDataChanged.run();
        }));
    }

    static String badgeText(String category) {
        if (category == null) return "系统";
        return switch (category.toUpperCase()) {
            case "BANK" -> "转账";
            case "CHAT" -> "群聊";
            case "REVIEW" -> "审核";
            case "LIBRARY" -> "图书";
            case "SHOP" -> "商店";
            default -> "系统";
        };
    }

    static String badgeStyleClass(String category) {
        if (category == null) return "notice-badge-info";
        return switch (category.toUpperCase()) {
            case "BANK" -> "notice-badge-bank";
            case "CHAT" -> "notice-badge-chat";
            case "REVIEW" -> "notice-badge-info";
            case "LIBRARY" -> "notice-badge-info";
            case "SHOP" -> "notice-badge-success";
            default -> "notice-badge-info";
        };
    }
}
