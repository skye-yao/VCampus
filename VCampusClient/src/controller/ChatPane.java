package controller;

import com.google.gson.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import service.ChatClientService;
import session.ClientSession;
import java.util.*;
import java.util.function.Consumer;

/** Embedded main-content module; all network work is asynchronous. */
public final class ChatPane implements AutoCloseable {
    private final ChatClientService api;
    private final String token=ClientSession.getInstance().getToken();
    private final String me=ClientSession.getInstance().getUsername();
    private final BorderPane root=new BorderPane();
    private final HBox conversationHeader=new HBox(12);
    private final Label subtitle=new Label("通过好友申请或创建群聊，即可开始文字聊天");
    private final ChatAvatars avatars;
    private final ListView<JsonObject> friends=new ListView<>();
    private final ListView<JsonObject> groups=new ListView<>();
    private final Tab contactsTab=new Tab(), groupsTab=new Tab(), applicationsTab=new Tab();
    private final VBox requests=new VBox(10), results=new VBox(10), bubbles=new VBox(10);
    private final Label title=new Label("选择好友或群聊开始聊天"), status=new Label(""), count=new Label();
    private final TextArea input=new TextArea();
    private final Button send=new Button("发送"), older=new Button("加载更早消息");
    private final Button createGroupBtn=new Button("➕ 发起群聊");
    private final Button groupSettingsBtn=new Button("⚙ 群设置");
    private final ScrollPane scroll=new ScrollPane(bubbles);
    private final Timeline timer;
    private String peer;
    private Long activeGroupId;
    private JsonObject activeGroup;
    private long oldest=Long.MAX_VALUE;
    private long newest;
    private boolean refreshing, historyBusy, sending, closed, selecting;
    private String retryKey, retryText, retryPeer;
    private final Set<Long> displayed=new HashSet<>();
    private final List<JsonObject> friendListCache=new ArrayList<>();

    public ChatPane() {
        this(new ChatClientService());
    }
    ChatPane(ChatClientService api) {
        this.api=api;
        avatars=new ChatAvatars(api,this::valid);
        friends.setCellFactory(v->new ListCell<>(){
            protected void updateItem(JsonObject u,boolean empty){
                super.updateItem(u,empty);setText(null);setGraphic(null);
                if(!empty&&u!=null){
                    HBox row=person(u,42);
                    int unread=u.has("unread")?u.get("unread").getAsInt():0;
                    if(unread>0){
                        Label badge=new Label(String.valueOf(unread));
                        badge.getStyleClass().add("chat-badge");
                        row.getChildren().add(badge);
                    }
                    setGraphic(row);
                }
            }
        });
        friends.setPlaceholder(new Label("暂无好友，先添加一位校园好友吧"));
        friends.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{
            if(selecting) return;
            if(b!=null && !Objects.equals(peer,b.get("uid").getAsString())){
                selecting=true;
                try{
                    groups.getSelectionModel().clearSelection();
                    activeGroupId=null;
                    activeGroup=null;
                    select(b);
                }finally{selecting=false;}
            }
        });

        groups.setCellFactory(v->new ListCell<>(){
            protected void updateItem(JsonObject g,boolean empty){
                super.updateItem(g,empty);setText(null);setGraphic(null);
                if(!empty&&g!=null){
                    String gName=g.get("name").getAsString();
                    int countVal=g.has("memberCount")?g.get("memberCount").getAsInt():0;
                    int unread=g.has("unread")?g.get("unread").getAsInt():0;
                    boolean isOwner="OWNER".equals(g.has("myRole")?g.get("myRole").getAsString():"");

                    Label nameLabel=new Label(gName);nameLabel.getStyleClass().add("chat-person-name");
                    Label countLabel=new Label(countVal+"人");countLabel.getStyleClass().add("chat-muted");
                    HBox infoSub=new HBox(6,countLabel);
                    if(isOwner){
                        Label tag=new Label("群主");tag.getStyleClass().add("chat-tag-owner");
                        infoSub.getChildren().add(tag);
                    }
                    VBox info=new VBox(4,nameLabel,infoSub);HBox.setHgrow(info,Priority.ALWAYS);
                    HBox row=new HBox(10,avatars.createGroup(gName,42),info);row.setAlignment(Pos.CENTER_LEFT);
                    if(unread>0){
                        Label badge=new Label(String.valueOf(unread));badge.getStyleClass().add("chat-badge");
                        row.getChildren().add(badge);
                    }
                    setGraphic(row);
                }
            }
        });
        groups.setPlaceholder(new Label("暂无群聊，点击上方按钮发起群聊"));
        groups.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{
            if(selecting) return;
            if(b!=null && (activeGroupId==null || activeGroupId!=b.get("groupId").getAsLong())){
                selecting=true;
                try{
                    friends.getSelectionModel().clearSelection();
                    peer=null;
                    selectGroup(b);
                }finally{selecting=false;}
            }
        });

        createGroupBtn.setMaxWidth(Double.MAX_VALUE);
        createGroupBtn.getStyleClass().add("chat-primary-btn");
        createGroupBtn.setOnAction(e->openCreateGroupDialog());

        VBox groupTabBox=new VBox(8,createGroupBtn,groups);
        groupTabBox.setPadding(new Insets(8));
        VBox.setVgrow(groups,Priority.ALWAYS);

        TabPane tabs=new TabPane();
        ScrollPane requestScroll=new ScrollPane(requests);requestScroll.setFitToWidth(true);
        requests.setMaxWidth(Double.MAX_VALUE);
        contactsTab.setContent(friends);
        groupsTab.setContent(groupTabBox);
        applicationsTab.setContent(requestScroll);
        updateTabBadge(contactsTab,"好友",0);updateTabBadge(groupsTab,"群聊",0);updateTabBadge(applicationsTab,"好友申请",0);
        TextField query=new TextField();query.setPromptText("姓名或一卡通号");Button search=new Button("查找");
        HBox searchBar=new HBox(6,query,search);HBox.setHgrow(query,Priority.ALWAYS);
        ScrollPane resultScroll=new ScrollPane(results);resultScroll.setFitToWidth(true);
        results.setMaxWidth(Double.MAX_VALUE);
        VBox find=new VBox(12,searchBar,resultScroll);find.setPadding(new Insets(10));VBox.setVgrow(resultScroll,Priority.ALWAYS);
        Runnable lookup=()->{
            if(query.getText().isBlank())return;
            search.setDisable(true);
            call("SEARCH",Map.of("query",query.getText().strip()),r->{
                results.getChildren().clear();
                for(var e:r.getAsJsonArray("users")){
                    var u=e.getAsJsonObject();
                    Button add=new Button();
                    configureFriendRequestButton(add,u);
                    VBox card=new VBox(10,person(u,38),add);
                    card.setMaxWidth(Double.MAX_VALUE);
                    card.getStyleClass().add("chat-person-card");
                    results.getChildren().add(card);
                }
                if(results.getChildren().isEmpty())results.getChildren().add(new Label("未找到用户"));
            },()->search.setDisable(false));
        };
        search.setOnAction(e->lookup.run());query.setOnAction(e->lookup.run());
        tabs.getTabs().addAll(contactsTab,groupsTab,applicationsTab,new Tab("添加好友",find));
        tabs.getTabs().forEach(t->t.setClosable(false));
        tabs.setPrefWidth(300);tabs.setMinWidth(270);
        for(Tab tab:tabs.getTabs())if(tab.getContent() instanceof ScrollPane pane)pane.setFitToWidth(true);
        title.setStyle("-fx-font-size:19px;-fx-font-weight:bold;");
        scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);bubbles.setPadding(new Insets(15));
        input.setPromptText("输入文字消息（最多 2000 字）；Enter 发送，Ctrl+Enter 换行");input.setPrefRowCount(3);input.setWrapText(true);
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,e->{
            if(e.getCode()==javafx.scene.input.KeyCode.ENTER){
                e.consume();
                if(e.isControlDown())input.replaceSelection("\n");
                else send();
            }
        });
        send.setOnAction(e->send());send.setDisable(true);input.setDisable(true);
        send.getStyleClass().add("chat-send");
        older.setDisable(true);older.setOnAction(e->history(true));
        HBox actions=new HBox(10,status,new Region(),send);HBox.setHgrow(actions.getChildren().get(1),Priority.ALWAYS);status.setWrapText(true);status.setMaxWidth(390);
        subtitle.getStyleClass().add("chat-muted");
        conversationHeader.setAlignment(Pos.CENTER_LEFT);conversationHeader.getStyleClass().add("chat-conversation-header");conversationHeader.getChildren().add(new VBox(5,title,subtitle));
        VBox composer=new VBox(8,input,actions);composer.getStyleClass().add("chat-composer");
        VBox chat=new VBox(10,conversationHeader,older,scroll,composer);VBox.setMargin(older,new Insets(0,20,0,20));VBox.setVgrow(scroll,Priority.ALWAYS);chat.setMinWidth(330);chat.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);
        SplitPane split=new SplitPane(tabs,chat);split.setDividerPositions(.30);split.getStyleClass().add("chat-card");split.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);
        Label heading=new Label("校园聊天");heading.getStyleClass().add("chat-page-title");
        count.getStyleClass().add("chat-muted");VBox top=new VBox(6,heading,count);top.setPadding(new Insets(0,0,18,0));
        root.setCenter(split);root.setTop(top);root.setPadding(new Insets(24,30,30,30));root.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);
        root.getStyleClass().add("chat-root");
        var stylesheet=ChatPane.class.getResource("/resources/css/chat.css");
        if(stylesheet!=null)root.getStylesheets().add(stylesheet.toExternalForm());
        root.setStyle("-fx-background-color:#f5f8f5;-fx-font-family:'Microsoft YaHei';-fx-font-size:13px;");
        timer=new Timeline(new KeyFrame(Duration.seconds(3),e->refresh()));timer.setCycleCount(Timeline.INDEFINITE);
        root.sceneProperty().addListener((o,a,b)->{if(a!=null&&b==null)close();});
    }
    public BorderPane getView(){return root;}
    public void start(){timer.play();refresh();}
    private boolean valid(){return !closed&&Objects.equals(token,ClientSession.getInstance().getToken());}
    private HBox person(JsonObject u,double size){
        Label label=new Label(u.get("name").getAsString());label.getStyleClass().add("chat-person-name");
        Label detail=new Label(u.get("uid").getAsString());detail.getStyleClass().add("chat-muted");
        VBox info=new VBox(5,label,detail);HBox.setHgrow(info,Priority.ALWAYS);info.setMinWidth(0);
        HBox row=new HBox(10,avatars.create(u.get("uid").getAsString(),u.get("name").getAsString(),size),info);row.setAlignment(Pos.CENTER_LEFT);row.setMaxWidth(Double.MAX_VALUE);return row;
    }
    private void configureFriendRequestButton(Button button,JsonObject user){
        String state=user.has("relationStatus")&&!user.get("relationStatus").isJsonNull()
                ?user.get("relationStatus").getAsString():"";
        String requester=user.has("requester")&&!user.get("requester").isJsonNull()
                ?user.get("requester").getAsString():"";
        switch(state){
            case "ACCEPTED"->{button.setText("已是好友");button.setDisable(true);}
            case "PENDING"->{button.setText(me.equals(requester)?"已申请":"对方已申请");button.setDisable(true);}
            default->{button.setText("申请好友");button.setDisable(false);button.setOnAction(ev->{
                button.setDisable(true);
                call("REQUEST",Map.of("peer",user.get("uid").getAsString()),r->{
                    button.setText("已申请");status.setText("好友申请已发送");refresh();
                },()->{if(!"已申请".equals(button.getText()))button.setDisable(false);});
            });}
        }
    }
    private void updateTabBadge(Tab tab,String text,int value){
        Label titleLabel=new Label(text);
        HBox graphic=new HBox(5,titleLabel);graphic.setAlignment(Pos.CENTER);
        if(value>0){Label badge=new Label(value>99?"99+":String.valueOf(value));badge.getStyleClass().add("chat-count-badge");graphic.getChildren().add(badge);}
        tab.setText("");tab.setGraphic(graphic);
    }
    private void call(String action,Map<String,Object> data,Consumer<JsonObject> done,Runnable finish){
        if(!valid()){close();return;}
        api.call(action,data).whenComplete((r,error)->Platform.runLater(()->{
            if(!valid())return;
            try{if(error==null){done.accept(r);}else{Throwable t=error;while(t.getCause()!=null)t=t.getCause();status.setText(t.getMessage()==null?"连接失败，请重试":t.getMessage());}}
            finally{finish.run();}
        }));
    }
    private void mutation(Button button,String action,String uid){
        button.setDisable(true);call(action,Map.of("peer",uid),r->{status.setText("REQUEST".equals(action)?"好友申请已发送":"申请已处理");refresh();},()->button.setDisable(false));
    }
    private void refresh(){
        if(!valid()){close();return;}if(refreshing)return;refreshing=true;
        call("CONTACTS",Map.of(),r->{
            var items=new ArrayList<JsonObject>();
            if(r.has("friends")&&r.get("friends").isJsonArray()){
                for(var e:r.getAsJsonArray("friends"))items.add(e.getAsJsonObject());
            }
            friendListCache.clear();
            friendListCache.addAll(items);
            friends.getItems().setAll(items);
            if(peer!=null){
                for(var u:items)if(u.get("uid").getAsString().equals(peer))friends.getSelectionModel().select(u);
            }
            requests.getChildren().clear();requests.setPadding(new Insets(10));int incoming=0;
            if(r.has("requests")&&r.get("requests").isJsonArray()){
                for(var e:r.getAsJsonArray("requests")){
                    var u=e.getAsJsonObject();VBox row=new VBox(10,person(u,38));row.getStyleClass().add("chat-person-card");
                    if(me.equals(u.get("requester").getAsString()))row.getChildren().add(new Label("已发送，等待对方同意"));
                    else {incoming++;Button yes=new Button("同意"),no=new Button("拒绝");yes.setOnAction(ev->mutation(yes,"ACCEPT",u.get("uid").getAsString()));no.setOnAction(ev->mutation(no,"REJECT",u.get("uid").getAsString()));row.getChildren().add(new HBox(8,yes,no));}
                    requests.getChildren().add(row);
                }
            }
            if(requests.getChildren().isEmpty())requests.getChildren().add(new Label("暂无好友申请"));
            final int pendingCount=incoming;
            int friendUnread=items.stream().mapToInt(u->u.has("unread")?u.get("unread").getAsInt():0).sum();
            updateTabBadge(contactsTab,"好友",friendUnread);
            updateTabBadge(applicationsTab,"好友申请",pendingCount);

            call("GROUP_LIST",Map.of(),gr->{
                var groupItems=new ArrayList<JsonObject>();
                if(gr!=null&&gr.has("groups")&&gr.get("groups").isJsonArray()){
                    for(var e:gr.getAsJsonArray("groups"))groupItems.add(e.getAsJsonObject());
                }
                groups.getItems().setAll(groupItems);
                int groupUnread=groupItems.stream().mapToInt(g->g.has("unread")?g.get("unread").getAsInt():0).sum();
                updateTabBadge(groupsTab,"群聊",groupUnread);
                if(activeGroupId!=null){
                    for(var g:groupItems){
                        if(g.get("groupId").getAsLong()==activeGroupId){
                            activeGroup=g;
                            title.setText(g.get("name").getAsString());
                            int memberCount=g.has("memberCount")?g.get("memberCount").getAsInt():0;
                            boolean isOwner=g.has("ownerUid")&&me.equals(g.get("ownerUid").getAsString());
                            subtitle.setText("群号："+activeGroupId+"  ·  "+memberCount+" 人"+(isOwner?"  ·  [我是群主]":"  ·  [普通成员]"));
                            groups.getSelectionModel().select(g);
                            break;
                        }
                    }
                }
                count.setText(items.size()+" 位好友  ·  "+groupItems.size()+" 个群聊  ·  "+pendingCount+" 条待处理申请");
                history(false);
            },()->{});
        },()->refreshing=false);
    }
    private void select(JsonObject u){
        peer=u.get("uid").getAsString();
        activeGroupId=null;
        activeGroup=null;
        title.setText(u.get("name").getAsString());
        subtitle.setText("一卡通号："+peer+"  ·  好友");
        conversationHeader.getChildren().setAll(avatars.create(peer,title.getText(),44),new VBox(5,title,subtitle));
        bubbles.getChildren().clear();displayed.clear();oldest=Long.MAX_VALUE;newest=0;input.clear();status.setText("");
        send.setDisable(sending);input.setDisable(false);older.setDisable(false);history(false);
    }
    private void selectGroup(JsonObject g){
        peer=null;
        activeGroupId=g.get("groupId").getAsLong();
        activeGroup=g;
        String gName=g.get("name").getAsString();
        title.setText(gName);
        int memberCount=g.has("memberCount")?g.get("memberCount").getAsInt():0;
        boolean isOwner=g.has("ownerUid")&&me.equals(g.get("ownerUid").getAsString());
        subtitle.setText("群号："+activeGroupId+"  ·  "+memberCount+" 人"+(isOwner?"  ·  [我是群主]":"  ·  [普通成员]"));

        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        groupSettingsBtn.setOnAction(e->openGroupSettings(activeGroupId));
        conversationHeader.getChildren().setAll(avatars.createGroup(gName,44),new VBox(5,title,subtitle),spacer,groupSettingsBtn);
        bubbles.getChildren().clear();displayed.clear();oldest=Long.MAX_VALUE;newest=0;input.clear();status.setText("");
        send.setDisable(sending);input.setDisable(false);older.setDisable(false);history(false);
    }
    private void renderMessages(JsonArray messages, boolean previous, boolean initial, boolean isGroup){
        var nodes=new ArrayList<javafx.scene.Node>();
        for(var e:messages){
            var m=e.getAsJsonObject();
            long id=m.get("id").getAsLong();
            oldest=Math.min(oldest,id);
            newest=Math.max(newest,id);
            if(!displayed.add(id))continue;
            String senderUid=m.get("sender").getAsString();
            boolean mine=me.equals(senderUid);
            Label text=new Label(m.get("content").getAsString());
            text.setWrapText(true);text.setMaxWidth(380);text.setPadding(new Insets(10,14,10,14));
            text.setStyle("-fx-background-radius:12;-fx-background-color:"+(mine?"#d7edd6":"white")+";");

            String rawTime=m.get("time").getAsString();
            String time;
            try{
                time=java.time.LocalDateTime.parse(rawTime.replace(' ','T')).atOffset(java.time.ZoneOffset.UTC).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            }catch(Exception ex){time=rawTime;}

            String senderDisplayName;
            if(mine){
                senderDisplayName="我";
            } else if(isGroup){
                String sName=m.has("senderName")&&!m.get("senderName").isJsonNull()?m.get("senderName").getAsString():senderUid;
                senderDisplayName=sName+"（"+senderUid+"）";
            } else {
                senderDisplayName=title.getText();
            }

            Label stamp=new Label(senderDisplayName+"  ·  "+time);
            stamp.getStyleClass().add("chat-muted");
            VBox block=new VBox(5,stamp,text);
            block.setAlignment(mine?Pos.CENTER_RIGHT:Pos.CENTER_LEFT);

            var avatar=avatars.create(senderUid,senderDisplayName,34);
            HBox row=mine?new HBox(10,block,avatar):new HBox(10,avatar,block);
            row.setAlignment(mine?Pos.TOP_RIGHT:Pos.TOP_LEFT);
            nodes.add(row);
        }
        if(previous)bubbles.getChildren().addAll(0,nodes);else bubbles.getChildren().addAll(nodes);
        if(previous||initial)older.setDisable(messages.size()<50);
        if(!previous&&!nodes.isEmpty())Platform.runLater(()->scroll.setVvalue(1));
    }
    private void history(boolean previous){
        if((peer==null&&activeGroupId==null)||historyBusy||!valid())return;
        historyBusy=true;
        if(peer!=null){
            String selected=peer;
            boolean initial=newest==0;
            Map<String,Object> data=new HashMap<>();data.put("peer",selected);
            if(previous)data.put("before",oldest);else if(!initial)data.put("after",newest);
            call("HISTORY",data,r->{
                if(!selected.equals(peer))return;
                if(r.has("messages")&&r.get("messages").isJsonArray()){
                    renderMessages(r.getAsJsonArray("messages"),previous,initial,false);
                }
                if(!previous&&newest>0&&root.getScene()!=null&&root.getScene().getWindow()!=null&&root.getScene().getWindow().isFocused())
                    call("READ",Map.of("peer",selected,"through",newest),x->{},()->{});
            },()->{historyBusy=false;if(!selected.equals(peer))history(false);});
        } else if(activeGroupId!=null){
            long selectedGroup=activeGroupId;
            boolean initial=newest==0;
            Map<String,Object> data=new HashMap<>();data.put("groupId",selectedGroup);
            if(previous)data.put("before",oldest);else if(!initial)data.put("after",newest);
            call("GROUP_HISTORY",data,r->{
                if(activeGroupId==null||activeGroupId!=selectedGroup)return;
                if(r.has("messages")&&r.get("messages").isJsonArray()){
                    renderMessages(r.getAsJsonArray("messages"),previous,initial,true);
                }
                if(!previous&&newest>0&&root.getScene()!=null&&root.getScene().getWindow()!=null&&root.getScene().getWindow().isFocused())
                    call("GROUP_READ",Map.of("groupId",selectedGroup,"through",newest),x->{},()->{});
            },()->{historyBusy=false;if(activeGroupId!=null&&activeGroupId!=selectedGroup)history(false);});
        }
    }
    private void send(){
        if((peer==null&&activeGroupId==null)||sending||input.getText().isBlank())return;
        String content=input.getText().strip();
        if(content.length()>2000){status.setText("消息不能超过 2000 字");return;}
        if(peer!=null){
            String selected=peer;
            if(!content.equals(retryText)||!selected.equals(retryPeer)){retryKey=UUID.randomUUID().toString();retryText=content;retryPeer=selected;}
            sending=true;send.setDisable(true);status.setText("发送中…");
            call("SEND",Map.of("peer",selected,"content",content,"clientId",retryKey),r->{
                retryKey=null;retryText=null;retryPeer=null;
                if(selected.equals(peer)&&input.getText().strip().equals(content))input.clear();
                status.setText("已发送");history(false);
            },()->{sending=false;send.setDisable(peer==null);});
        } else if(activeGroupId!=null){
            long selectedGroup=activeGroupId;
            String groupKey="g_"+selectedGroup;
            if(!content.equals(retryText)||!groupKey.equals(retryPeer)){retryKey=UUID.randomUUID().toString();retryText=content;retryPeer=groupKey;}
            sending=true;send.setDisable(true);status.setText("发送中…");
            call("GROUP_SEND",Map.of("groupId",selectedGroup,"content",content,"clientId",retryKey),r->{
                retryKey=null;retryText=null;retryPeer=null;
                if(activeGroupId!=null&&activeGroupId==selectedGroup&&input.getText().strip().equals(content))input.clear();
                status.setText("已发送");history(false);
            },()->{sending=false;send.setDisable(activeGroupId==null);});
        }
    }

    private void openCreateGroupDialog(){
        Stage stage=new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if(root.getScene()!=null&&root.getScene().getWindow()!=null) stage.initOwner(root.getScene().getWindow());
        stage.setTitle("创建群聊");

        VBox layout=new VBox(14);
        layout.setPadding(new Insets(20));
        layout.setStyle("-fx-background-color:#f5f8f5;-fx-font-family:'Microsoft YaHei';");

        Label heading=new Label("创建群聊");
        heading.setStyle("-fx-font-size:18px;-fx-font-weight:bold;-fx-text-fill:#26352c;");

        Label nameLabel=new Label("群聊名称：");
        nameLabel.setStyle("-fx-font-weight:bold;");
        TextField nameField=new TextField();
        nameField.setPromptText("例如：大作业小组、宿舍讨论群");
        nameField.setPrefWidth(340);

        Label selectLabel=new Label("选择好友（被邀请方无需同意直接进入）：");
        selectLabel.setStyle("-fx-font-weight:bold;");

        VBox friendsCheckList=new VBox(8);
        friendsCheckList.setPadding(new Insets(8));
        Map<String,CheckBox> checkBoxes=new LinkedHashMap<>();
        if(friendListCache.isEmpty()){
            friendsCheckList.getChildren().add(new Label("暂无可邀请的好友，请先添加好友"));
        } else {
            for(JsonObject f:friendListCache){
                String fUid=f.get("uid").getAsString();
                String fName=f.get("name").getAsString();
                CheckBox cb=new CheckBox(fName+"（"+fUid+"）");
                checkBoxes.put(fUid,cb);
                friendsCheckList.getChildren().add(cb);
            }
        }
        ScrollPane friendsScroll=new ScrollPane(friendsCheckList);
        friendsScroll.setFitToWidth(true);
        friendsScroll.setPrefHeight(180);
        friendsScroll.setStyle("-fx-background-color:white;-fx-border-color:#e0e7e1;-fx-border-radius:6;");

        Label msgLabel=new Label("");
        msgLabel.setStyle("-fx-text-fill:#d9534f;");

        Button submitBtn=new Button("立即创建");
        submitBtn.getStyleClass().add("chat-primary-btn");
        Button cancelBtn=new Button("取消");
        cancelBtn.setOnAction(e->stage.close());

        submitBtn.setOnAction(e->{
            String gName=nameField.getText().strip();
            if(gName.isBlank()){
                msgLabel.setText("请输入群聊名称");
                return;
            }
            List<String> selectedUids=new ArrayList<>();
            for(var entry:checkBoxes.entrySet()){
                if(entry.getValue().isSelected()){
                    selectedUids.add(entry.getKey());
                }
            }
            submitBtn.setDisable(true);
            msgLabel.setStyle("-fx-text-fill:#83928b;");
            msgLabel.setText("正在创建群聊…");

            call("GROUP_CREATE",Map.of("name",gName,"members",selectedUids),res->{
                stage.close();
                status.setText("群聊【"+gName+"】创建成功！");
                refresh();
                if(res.has("groupId")){
                    long newGid=res.get("groupId").getAsLong();
                    Platform.runLater(()->{
                        activeGroupId=newGid;
                        refresh();
                    });
                }
            },()->submitBtn.setDisable(false));
        });

        HBox btnBar=new HBox(12,submitBtn,cancelBtn);
        btnBar.setAlignment(Pos.CENTER_RIGHT);

        layout.getChildren().addAll(heading,nameLabel,nameField,selectLabel,friendsScroll,msgLabel,btnBar);

        Scene scene=new Scene(layout,400,440);
        var stylesheet=ChatPane.class.getResource("/resources/css/chat.css");
        if(stylesheet!=null)scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        stage.showAndWait();
    }

    private void openGroupSettings(long groupId){
        Stage stage=new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if(root.getScene()!=null&&root.getScene().getWindow()!=null) stage.initOwner(root.getScene().getWindow());
        stage.setTitle("群聊设置");

        VBox rootBox=new VBox(16);
        rootBox.setPadding(new Insets(20));
        rootBox.setStyle("-fx-background-color:#f5f8f5;-fx-font-family:'Microsoft YaHei';");

        Label loadingLabel=new Label("正在加载群信息…");
        rootBox.getChildren().add(loadingLabel);

        Scene scene=new Scene(rootBox,480,580);
        var stylesheet=ChatPane.class.getResource("/resources/css/chat.css");
        if(stylesheet!=null)scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        stage.show();

        Runnable loadData=new Runnable(){
            @Override public void run(){
                call("GROUP_INFO",Map.of("groupId",groupId),res->{
                    var groupObj=res.getAsJsonObject("group");
                    var membersArr=res.getAsJsonArray("members");
                    String currentName=groupObj.get("name").getAsString();
                    String ownerUid=groupObj.get("ownerUid").getAsString();
                    boolean isOwner=me.equals(ownerUid);

                    rootBox.getChildren().clear();

                    Label heading=new Label("群聊设置 - "+currentName);
                    heading.setStyle("-fx-font-size:18px;-fx-font-weight:bold;-fx-text-fill:#26352c;");

                    // 1. Group Name
                    Label nameLbl=new Label("群聊名称：");
                    nameLbl.setStyle("-fx-font-weight:bold;");
                    TextField nameField=new TextField(currentName);
                    HBox.setHgrow(nameField,Priority.ALWAYS);
                    HBox nameBox=new HBox(8,nameField);
                    if(isOwner){
                        Button saveNameBtn=new Button("修改群名");
                        saveNameBtn.setOnAction(e->{
                            String newName=nameField.getText().strip();
                            if(newName.isBlank()){return;}
                            saveNameBtn.setDisable(true);
                            call("GROUP_RENAME",Map.of("groupId",groupId,"name",newName),r->{
                                title.setText(newName);
                                stage.setTitle("群聊设置 - "+newName);
                                heading.setText("群聊设置 - "+newName);
                                status.setText("群名已修改为："+newName);
                                refresh();
                            },()->saveNameBtn.setDisable(false));
                        });
                        nameBox.getChildren().add(saveNameBtn);
                    } else {
                        nameField.setEditable(false);
                        Label tip=new Label("(仅群主可修改)");tip.getStyleClass().add("chat-muted");
                        nameBox.getChildren().add(tip);
                    }

                    // 2. Group Members
                    Label membersLbl=new Label("群成员 ("+membersArr.size()+"人)：");
                    membersLbl.setStyle("-fx-font-weight:bold;");
                    VBox membersList=new VBox(8);
                    membersList.setPadding(new Insets(6));
                    Set<String> memberUidSet=new HashSet<>();

                    for(var mElem:membersArr){
                        var m=mElem.getAsJsonObject();
                        String mUid=m.get("uid").getAsString();
                        String mName=m.get("name").getAsString();
                        String mRole=m.get("role").getAsString();
                        memberUidSet.add(mUid);

                        HBox mRow=new HBox(10);
                        mRow.setAlignment(Pos.CENTER_LEFT);
                        mRow.setPadding(new Insets(6,10,6,10));
                        mRow.setStyle("-fx-background-color:white;-fx-background-radius:6;-fx-border-color:#e2e9e3;-fx-border-radius:6;");

                        var avatar=avatars.create(mUid,mName,32);
                        Label nameL=new Label(mName);nameL.setStyle("-fx-font-weight:bold;");
                        Label uidL=new Label("("+mUid+")");uidL.getStyleClass().add("chat-muted");
                        HBox namePart=new HBox(6,nameL,uidL);
                        namePart.setAlignment(Pos.CENTER_LEFT);
                        HBox.setHgrow(namePart,Priority.ALWAYS);

                        Label roleTag=new Label("OWNER".equals(mRole)?"群主":"成员");
                        roleTag.getStyleClass().add("OWNER".equals(mRole)?"chat-tag-owner":"chat-tag-member");

                        mRow.getChildren().addAll(avatar,namePart,roleTag);

                        // Kick button (owner only, cannot kick self)
                        if(isOwner&&!me.equals(mUid)){
                            Button kickBtn=new Button("移出群聊");
                            kickBtn.getStyleClass().add("chat-danger-btn");
                            kickBtn.setStyle("-fx-padding:4 8;-fx-font-size:11px;");
                            kickBtn.setOnAction(e->{
                                Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,"确定将 "+mName+"（"+mUid+"）移出群聊吗？");
                                confirm.setTitle("移出群聊确认");
                                confirm.setHeaderText(null);
                                confirm.initOwner(stage);
                                confirm.showAndWait().ifPresent(btnType->{
                                    if(btnType==ButtonType.OK){
                                        kickBtn.setDisable(true);
                                        call("GROUP_KICK",Map.of("groupId",groupId,"memberUid",mUid),r->{
                                            status.setText("已将 "+mName+" 移出群聊");
                                            refresh();
                                            run(); // reload settings
                                        },()->kickBtn.setDisable(false));
                                    }
                                });
                            });
                            mRow.getChildren().add(kickBtn);
                        }
                        membersList.getChildren().add(mRow);
                    }

                    ScrollPane membersScroll=new ScrollPane(membersList);
                    membersScroll.setFitToWidth(true);
                    membersScroll.setPrefHeight(160);
                    membersScroll.setStyle("-fx-background-color:transparent;");

                    // 3. Invite Friends (Add Members)
                    Label inviteLbl=new Label("邀请好友加入群聊（无需同意直接加入）：");
                    inviteLbl.setStyle("-fx-font-weight:bold;");
                    VBox inviteList=new VBox(6);
                    inviteList.setPadding(new Insets(6));
                    Map<String,CheckBox> inviteBoxes=new LinkedHashMap<>();
                    int canInviteCount=0;
                    for(JsonObject f:friendListCache){
                        String fUid=f.get("uid").getAsString();
                        if(!memberUidSet.contains(fUid)){
                            canInviteCount++;
                            CheckBox cb=new CheckBox(f.get("name").getAsString()+"（"+fUid+"）");
                            inviteBoxes.put(fUid,cb);
                            inviteList.getChildren().add(cb);
                        }
                    }
                    HBox inviteActionBox=new HBox(10);
                    inviteActionBox.setAlignment(Pos.CENTER_LEFT);
                    if(canInviteCount>0){
                        Button inviteBtn=new Button("➕ 邀请选中的好友进群");
                        inviteBtn.getStyleClass().add("chat-primary-btn");
                        inviteBtn.setOnAction(e->{
                            List<String> toAdd=new ArrayList<>();
                            for(var entry:inviteBoxes.entrySet()){
                                if(entry.getValue().isSelected()){
                                    toAdd.add(entry.getKey());
                                }
                            }
                            if(toAdd.isEmpty()){return;}
                            inviteBtn.setDisable(true);
                            call("GROUP_ADD_MEMBERS",Map.of("groupId",groupId,"members",toAdd),r->{
                                status.setText("已邀请好友加入群聊");
                                refresh();
                                run(); // reload settings
                            },()->inviteBtn.setDisable(false));
                        });
                        inviteActionBox.getChildren().add(inviteBtn);
                    } else {
                        inviteList.getChildren().add(new Label("您的所有好友均已在群聊中"));
                    }

                    ScrollPane inviteScroll=new ScrollPane(inviteList);
                    inviteScroll.setFitToWidth(true);
                    inviteScroll.setPrefHeight(100);
                    inviteScroll.setStyle("-fx-background-color:transparent;");

                    // 4. Danger actions (Dissolve or Leave)
                    HBox bottomActions=new HBox(12);
                    bottomActions.setAlignment(Pos.CENTER_RIGHT);
                    Button closeBtn=new Button("关闭");
                    closeBtn.setOnAction(e->stage.close());

                    if(isOwner){
                        Button dissolveBtn=new Button("解散群聊");
                        dissolveBtn.getStyleClass().add("chat-danger-btn");
                        dissolveBtn.setOnAction(e->{
                            Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,"确定解散群聊【"+currentName+"】吗？\n解散后该群聊和所有聊天记录将被永久删除，不可恢复。");
                            confirm.setTitle("解散群聊确认");
                            confirm.setHeaderText(null);
                            confirm.initOwner(stage);
                            confirm.showAndWait().ifPresent(btnType->{
                                if(btnType==ButtonType.OK){
                                    dissolveBtn.setDisable(true);
                                    call("GROUP_DISSOLVE",Map.of("groupId",groupId),r->{
                                        stage.close();
                                        activeGroupId=null;
                                        activeGroup=null;
                                        title.setText("选择好友或群聊开始聊天");
                                        subtitle.setText("群聊已解散");
                                        conversationHeader.getChildren().setAll(new VBox(5,title,subtitle));
                                        bubbles.getChildren().clear();
                                        input.setDisable(true);send.setDisable(true);older.setDisable(true);
                                        status.setText("群聊【"+currentName+"】已解散");
                                        refresh();
                                    },()->dissolveBtn.setDisable(false));
                                }
                            });
                        });
                        bottomActions.getChildren().addAll(dissolveBtn,new Region(),closeBtn);
                        HBox.setHgrow(bottomActions.getChildren().get(1),Priority.ALWAYS);
                    } else {
                        Button leaveBtn=new Button("退出群聊");
                        leaveBtn.getStyleClass().add("chat-danger-btn");
                        leaveBtn.setOnAction(e->{
                            Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,"确定退出群聊【"+currentName+"】吗？");
                            confirm.setTitle("退出群聊确认");
                            confirm.setHeaderText(null);
                            confirm.initOwner(stage);
                            confirm.showAndWait().ifPresent(btnType->{
                                if(btnType==ButtonType.OK){
                                    leaveBtn.setDisable(true);
                                    call("GROUP_LEAVE",Map.of("groupId",groupId),r->{
                                        stage.close();
                                        activeGroupId=null;
                                        activeGroup=null;
                                        title.setText("选择好友或群聊开始聊天");
                                        subtitle.setText("已退出群聊");
                                        conversationHeader.getChildren().setAll(new VBox(5,title,subtitle));
                                        bubbles.getChildren().clear();
                                        input.setDisable(true);send.setDisable(true);older.setDisable(true);
                                        status.setText("已退出群聊【"+currentName+"】");
                                        refresh();
                                    },()->leaveBtn.setDisable(false));
                                }
                            });
                        });
                        bottomActions.getChildren().addAll(leaveBtn,new Region(),closeBtn);
                        HBox.setHgrow(bottomActions.getChildren().get(1),Priority.ALWAYS);
                    }

                    VBox content=new VBox(12,
                        heading,
                        nameLbl,nameBox,
                        membersLbl,membersScroll,
                        inviteLbl,inviteScroll,inviteActionBox,
                        new Separator(),
                        bottomActions
                    );
                    ScrollPane mainScroll=new ScrollPane(content);
                    mainScroll.setFitToWidth(true);
                    mainScroll.setStyle("-fx-background-color:transparent;");
                    VBox.setVgrow(mainScroll,Priority.ALWAYS);
                    rootBox.getChildren().setAll(mainScroll);
                },()->{});
            }
        };
        loadData.run();
    }

    @Override public void close(){if(closed)return;closed=true;timer.stop();avatars.close();}
}
