package controller;

import com.google.gson.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
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
    private final Label subtitle=new Label("通过好友申请后，即可开始文字聊天");
    private final ChatAvatars avatars;
    private final ListView<JsonObject> friends=new ListView<>();
    private final VBox requests=new VBox(10), results=new VBox(10), bubbles=new VBox(10);
    private final Label title=new Label("选择好友开始聊天"), status=new Label(""), count=new Label();
    private final TextArea input=new TextArea();
    private final Button send=new Button("发送"), older=new Button("加载更早消息");
    private final ScrollPane scroll=new ScrollPane(bubbles);
    private final Timeline timer;
    private String peer;
    private long oldest=Long.MAX_VALUE;
    private long newest;
    private boolean refreshing, historyBusy, sending, closed;
    private String retryKey, retryText, retryPeer;
    private final Set<Long> displayed=new HashSet<>();

    public ChatPane() {
        this(new ChatClientService());
    }
    ChatPane(ChatClientService api) {
        this.api=api;
        avatars=new ChatAvatars(api,this::valid);
        friends.setCellFactory(v->new ListCell<>(){
            protected void updateItem(JsonObject u,boolean empty){super.updateItem(u,empty);setText(null);setGraphic(null);if(!empty&&u!=null){HBox row=person(u,42);int unread=u.get("unread").getAsInt();if(unread>0){Label badge=new Label(String.valueOf(unread));badge.getStyleClass().add("chat-badge");row.getChildren().add(badge);}setGraphic(row);}}
        });
        friends.setPlaceholder(new Label("暂无好友，先添加一位校园好友吧"));
        friends.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null && !Objects.equals(peer,b.get("uid").getAsString()))select(b);});
        TabPane tabs=new TabPane();
        Tab contacts=new Tab("好友",friends), applications=new Tab("好友申请",new ScrollPane(requests));
        TextField query=new TextField();query.setPromptText("姓名或一卡通号");Button search=new Button("查找");
        HBox searchBar=new HBox(6,query,search);HBox.setHgrow(query,Priority.ALWAYS);
        VBox find=new VBox(12,searchBar,new ScrollPane(results));find.setPadding(new Insets(10));
        Runnable lookup=()->{if(query.getText().isBlank())return;search.setDisable(true);call("SEARCH",Map.of("query",query.getText().strip()),r->{results.getChildren().clear();for(var e:r.getAsJsonArray("users")){var u=e.getAsJsonObject();Button add=new Button("申请好友");add.setOnAction(ev->mutation(add,"REQUEST",u.get("uid").getAsString()));VBox card=new VBox(10,person(u,38),add);card.getStyleClass().add("chat-person-card");results.getChildren().add(card);}if(results.getChildren().isEmpty())results.getChildren().add(new Label("未找到用户"));},()->search.setDisable(false));};
        search.setOnAction(e->lookup.run());query.setOnAction(e->lookup.run());
        tabs.getTabs().addAll(contacts,applications,new Tab("添加好友",find));tabs.getTabs().forEach(t->t.setClosable(false));tabs.setPrefWidth(300);tabs.setMinWidth(270);
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
        VBox chat=new VBox(10,conversationHeader,older,scroll,composer);VBox.setMargin(older,new Insets(0,20,0,20));VBox.setVgrow(scroll,Priority.ALWAYS);chat.setMinWidth(330);
        SplitPane split=new SplitPane(tabs,chat);split.setDividerPositions(.30);split.getStyleClass().add("chat-card");
        Label heading=new Label("校园聊天");heading.getStyleClass().add("chat-page-title");
        count.getStyleClass().add("chat-muted");VBox top=new VBox(6,heading,count);top.setPadding(new Insets(0,0,18,0));
        root.setCenter(split);root.setTop(top);root.setPadding(new Insets(24,30,30,30));
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
    private static String name(JsonObject u){return u.get("name").getAsString()+"（"+u.get("uid").getAsString()+"）";}
    private HBox person(JsonObject u,double size){
        Label label=new Label(u.get("name").getAsString());label.getStyleClass().add("chat-person-name");
        Label detail=new Label(u.get("uid").getAsString());detail.getStyleClass().add("chat-muted");
        VBox info=new VBox(5,label,detail);HBox.setHgrow(info,Priority.ALWAYS);info.setMinWidth(0);
        HBox row=new HBox(10,avatars.create(u.get("uid").getAsString(),u.get("name").getAsString(),size),info);row.setAlignment(Pos.CENTER_LEFT);row.setMaxWidth(Double.MAX_VALUE);return row;
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
            var items=new ArrayList<JsonObject>();for(var e:r.getAsJsonArray("friends"))items.add(e.getAsJsonObject());
            friends.getItems().setAll(items);for(var u:items)if(u.get("uid").getAsString().equals(peer))friends.getSelectionModel().select(u);
            requests.getChildren().clear();requests.setPadding(new Insets(10));int incoming=0;
            for(var e:r.getAsJsonArray("requests")){var u=e.getAsJsonObject();VBox row=new VBox(10,person(u,38));row.getStyleClass().add("chat-person-card");
                if(me.equals(u.get("requester").getAsString()))row.getChildren().add(new Label("已发送，等待对方同意"));
                else {incoming++;Button yes=new Button("同意"),no=new Button("拒绝");yes.setOnAction(ev->mutation(yes,"ACCEPT",u.get("uid").getAsString()));no.setOnAction(ev->mutation(no,"REJECT",u.get("uid").getAsString()));row.getChildren().add(new HBox(8,yes,no));}
                requests.getChildren().add(row);
            }
            if(requests.getChildren().isEmpty())requests.getChildren().add(new Label("暂无好友申请"));
            count.setText(items.size()+" 位好友  ·  "+incoming+" 条待处理申请");history(false);
        },()->refreshing=false);
    }
    private void select(JsonObject u){peer=u.get("uid").getAsString();title.setText(u.get("name").getAsString());subtitle.setText("一卡通号："+peer+"  ·  好友");conversationHeader.getChildren().setAll(avatars.create(peer,title.getText(),44),new VBox(5,title,subtitle));bubbles.getChildren().clear();displayed.clear();oldest=Long.MAX_VALUE;newest=0;input.clear();status.setText("");send.setDisable(sending);input.setDisable(false);older.setDisable(false);history(false);}
    private void history(boolean previous){
        if(peer==null||historyBusy||!valid())return;historyBusy=true;String selected=peer;
        boolean initial=newest==0;
        Map<String,Object> data=new HashMap<>();data.put("peer",selected);if(previous)data.put("before",oldest);else if(!initial)data.put("after",newest);
        call("HISTORY",data,r->{
            if(!selected.equals(peer))return;
            JsonArray messages=r.getAsJsonArray("messages");var nodes=new ArrayList<javafx.scene.Node>();long through=0;
            for(var e:messages){var m=e.getAsJsonObject();long id=m.get("id").getAsLong();oldest=Math.min(oldest,id);newest=Math.max(newest,id);through=Math.max(through,id);if(!displayed.add(id))continue;
                boolean mine=me.equals(m.get("sender").getAsString());Label text=new Label(m.get("content").getAsString());text.setWrapText(true);text.setMaxWidth(380);text.setPadding(new Insets(10,14,10,14));text.setStyle("-fx-background-radius:12;-fx-background-color:"+(mine?"#d7edd6":"white")+";");
                String time=java.time.LocalDateTime.parse(m.get("time").getAsString().replace(' ','T')).atOffset(java.time.ZoneOffset.UTC).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                String senderName=mine?"我":title.getText();
                Label stamp=new Label(senderName+"  ·  "+time);stamp.getStyleClass().add("chat-muted");VBox block=new VBox(5,stamp,text);block.setAlignment(mine?Pos.CENTER_RIGHT:Pos.CENTER_LEFT);
                var avatar=avatars.create(mine?me:selected,senderName,34);HBox row=mine?new HBox(10,block,avatar):new HBox(10,avatar,block);row.setAlignment(mine?Pos.TOP_RIGHT:Pos.TOP_LEFT);nodes.add(row);
            }
            if(previous)bubbles.getChildren().addAll(0,nodes);else bubbles.getChildren().addAll(nodes);
            if(previous||initial)older.setDisable(messages.size()<50);
            if(!previous&&!nodes.isEmpty())Platform.runLater(()->scroll.setVvalue(1));
            if(!previous&&newest>0&&root.getScene()!=null&&root.getScene().getWindow()!=null&&root.getScene().getWindow().isFocused())call("READ",Map.of("peer",selected,"through",newest),x->{},()->{});
        },()->{historyBusy=false;if(!selected.equals(peer))history(false);});
    }
    private void send(){
        if(peer==null||sending||input.getText().isBlank())return;
        String content=input.getText().strip(),selected=peer;if(content.length()>2000){status.setText("消息不能超过 2000 字");return;}
        if(!content.equals(retryText)||!selected.equals(retryPeer)){retryKey=UUID.randomUUID().toString();retryText=content;retryPeer=selected;}
        sending=true;send.setDisable(true);status.setText("发送中…");
        call("SEND",Map.of("peer",selected,"content",content,"clientId",retryKey),r->{
            retryKey=null;retryText=null;retryPeer=null;if(selected.equals(peer)&&input.getText().strip().equals(content))input.clear();status.setText("已发送");history(false);
        },()->{sending=false;send.setDisable(peer==null);});
    }
    @Override public void close(){if(closed)return;closed=true;timer.stop();avatars.close();}
}
