package controller;

import com.google.gson.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;
import service.ChatClientService;
import session.ClientSession;
import java.util.*;
import java.util.function.Consumer;

/** A modeless chat panel; all network work is asynchronous. */
public final class ChatWindow implements AutoCloseable {
    private final ChatClientService api;
    private final String token=ClientSession.getInstance().getToken();
    private final String me=ClientSession.getInstance().getUsername();
    private final Stage stage=new Stage();
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

    public ChatWindow(Window owner) {
        this(owner,new ChatClientService());
    }
    ChatWindow(Window owner,ChatClientService api) {
        this.api=api;
        stage.initOwner(owner);stage.setTitle("校园聊天");stage.setMinWidth(760);stage.setMinHeight(540);
        friends.setCellFactory(v->new ListCell<>(){
            protected void updateItem(JsonObject u,boolean empty){super.updateItem(u,empty);setText(empty||u==null?null:name(u)+(u.get("unread").getAsInt()>0?"  ● "+u.get("unread").getAsInt():""));}
        });
        friends.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null && !Objects.equals(peer,b.get("uid").getAsString()))select(b);});
        TabPane tabs=new TabPane();
        Tab contacts=new Tab("好友",friends), applications=new Tab("好友申请",new ScrollPane(requests));
        TextField query=new TextField();query.setPromptText("姓名或一卡通号");Button search=new Button("查找");
        HBox searchBar=new HBox(6,query,search);HBox.setHgrow(query,Priority.ALWAYS);
        VBox find=new VBox(12,searchBar,new ScrollPane(results));find.setPadding(new Insets(10));
        Runnable lookup=()->{if(query.getText().isBlank())return;search.setDisable(true);call("SEARCH",Map.of("query",query.getText().strip()),r->{results.getChildren().clear();for(var e:r.getAsJsonArray("users")){var u=e.getAsJsonObject();Button add=new Button("申请好友");add.setOnAction(ev->mutation(add,"REQUEST",u.get("uid").getAsString()));results.getChildren().add(new VBox(5,new Label(name(u)),add));}if(results.getChildren().isEmpty())results.getChildren().add(new Label("未找到用户"));},()->search.setDisable(false));};
        search.setOnAction(e->lookup.run());query.setOnAction(e->lookup.run());
        tabs.getTabs().addAll(contacts,applications,new Tab("添加好友",find));tabs.getTabs().forEach(t->t.setClosable(false));tabs.setPrefWidth(285);
        title.setStyle("-fx-font-size:19px;-fx-font-weight:bold;");
        scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);bubbles.setPadding(new Insets(15));
        input.setPromptText("输入文字消息（最多 2000 字）；Ctrl+Enter 发送");input.setPrefRowCount(3);input.setWrapText(true);
        input.setOnKeyPressed(e->{if(e.isControlDown()&&e.getCode()==javafx.scene.input.KeyCode.ENTER){send();e.consume();}});
        send.setOnAction(e->send());send.setDisable(true);input.setDisable(true);
        send.getStyleClass().add("chat-send");
        older.setDisable(true);older.setOnAction(e->history(true));
        HBox actions=new HBox(10,status,new Region(),send);HBox.setHgrow(actions.getChildren().get(1),Priority.ALWAYS);status.setWrapText(true);status.setMaxWidth(390);
        VBox chat=new VBox(12,title,older,scroll,input,actions);chat.setPadding(new Insets(18));VBox.setVgrow(scroll,Priority.ALWAYS);
        SplitPane split=new SplitPane(tabs,chat);split.setDividerPositions(.32);
        BorderPane root=new BorderPane(split);root.setTop(count);BorderPane.setMargin(count,new Insets(8,14,8,14));
        root.getStyleClass().add("chat-root");
        var stylesheet=ChatWindow.class.getResource("/resources/css/chat.css");
        if(stylesheet!=null)root.getStylesheets().add(stylesheet.toExternalForm());
        root.setStyle("-fx-background-color:#f5f8f5;-fx-font-family:'Microsoft YaHei';-fx-font-size:13px;");
        stage.setScene(new Scene(root,940,660));stage.setOnHidden(e->close());
        stage.focusedProperty().addListener((o,a,b)->{if(b)history(false);});
        timer=new Timeline(new KeyFrame(Duration.seconds(3),e->refresh()));timer.setCycleCount(Timeline.INDEFINITE);
    }
    public void show(){stage.show();stage.toFront();timer.play();refresh();}
    public boolean isShowing(){return stage.isShowing()&&!closed;}
    private boolean valid(){return !closed&&Objects.equals(token,ClientSession.getInstance().getToken());}
    private static String name(JsonObject u){return u.get("name").getAsString()+"（"+u.get("uid").getAsString()+"）";}
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
            for(var e:r.getAsJsonArray("requests")){var u=e.getAsJsonObject();VBox row=new VBox(6,new Label(name(u)));
                if(me.equals(u.get("requester").getAsString()))row.getChildren().add(new Label("已发送，等待对方同意"));
                else {incoming++;Button yes=new Button("同意"),no=new Button("拒绝");yes.setOnAction(ev->mutation(yes,"ACCEPT",u.get("uid").getAsString()));no.setOnAction(ev->mutation(no,"REJECT",u.get("uid").getAsString()));row.getChildren().add(new HBox(8,yes,no));}
                requests.getChildren().add(row);
            }
            if(requests.getChildren().isEmpty())requests.getChildren().add(new Label("暂无好友申请"));
            count.setText("校园聊天  ·  "+items.size()+" 位好友  ·  "+incoming+" 条待处理申请");history(false);
        },()->refreshing=false);
    }
    private void select(JsonObject u){peer=u.get("uid").getAsString();title.setText(name(u));bubbles.getChildren().clear();displayed.clear();oldest=Long.MAX_VALUE;newest=0;input.clear();status.setText("");send.setDisable(sending);input.setDisable(false);older.setDisable(false);history(false);}
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
                Label stamp=new Label(time);stamp.setStyle("-fx-text-fill:#849088;-fx-font-size:11px;");VBox block=new VBox(4,stamp,text);block.setAlignment(mine?Pos.CENTER_RIGHT:Pos.CENTER_LEFT);nodes.add(block);
            }
            if(previous)bubbles.getChildren().addAll(0,nodes);else bubbles.getChildren().addAll(nodes);
            if(previous||initial)older.setDisable(messages.size()<50);
            if(!previous&&!nodes.isEmpty())Platform.runLater(()->scroll.setVvalue(1));
            if(!previous&&newest>0&&stage.isFocused())call("READ",Map.of("peer",selected,"through",newest),x->{},()->{});
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
    @Override public void close(){if(closed)return;closed=true;timer.stop();stage.hide();}
}
