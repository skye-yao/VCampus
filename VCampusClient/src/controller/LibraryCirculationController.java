package controller;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import service.LibraryClientService;
import session.ClientSession;
import util.AlertUtil;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** 管理员核对线下借书人后办理，收款人始终绑定所选缴费记录。 */
public class LibraryCirculationController {
    private final LibraryClientService service=LibraryClientService.getInstance();
    private final Function<String,CompletableFuture<List<Map<String,String>>>> records;
    private boolean closed;

    public LibraryCirculationController() { this(LibraryClientService.getInstance()::getAdminRecords); }
    LibraryCirculationController(Function<String,CompletableFuture<List<Map<String,String>>>> records) { this.records=records; }

    public void show(int selectedTab) {
        Dialog<Void> dialog=new Dialog<>();
        dialog.setTitle("图书馆 · 借还与缴费管理");
        dialog.setResizable(true);
        style(dialog.getDialogPane());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setContent(createContent(selectedTab));
        dialog.setOnHidden(event->closed=true);
        dialog.showAndWait();
    }

    BorderPane createContent(int selectedTab) {
        Label title=label("借还与缴费管理","library-title");
        Label subtitle=label("CAMPUS LIBRARY · 预约取书 / 借阅归还 / 费用退还","library-subtitle");
        VBox heading=new VBox(6,title,subtitle);
        Label badge=label("管理员工作台","circulation-header-tag");
        Region space=new Region();HBox.setHgrow(space,Priority.ALWAYS);
        HBox header=new HBox(16,heading,space,badge);header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("library-header");
        TabPane tabs=new TabPane();tabs.getStyleClass().add("library-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(panel("预约待借","checkout","确认借书"),panel("借阅未还","returns","确认还书"),panel("罚款与退款","fine","办理退款"));
        tabs.getSelectionModel().select(selectedTab);
        BorderPane root=new BorderPane(tabs);root.setTop(header);
        root.setPrefSize(1080,650);root.setMinSize(760,520);
        return root;
    }

    private Tab panel(String title,String kind,String actionText) {
        boolean fine=kind.equals("fine"),checkout=kind.equals("checkout");
        TableView<Map<String,String>> table=new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getStyleClass().add("circulation-table");
        var rows=FXCollections.<Map<String,String>>observableArrayList();
        var filtered=new FilteredList<>(rows);table.setItems(filtered);
        String[] columns=fine?new String[]{"账号","姓名","实付","已退款","可退金额","状态"}:
                checkout?new String[]{"账号","姓名","书名","取书截止时间","状态"}:
                new String[]{"账号","姓名","书名","应还时间","状态"};
        for(String key:columns) {
            TableColumn<Map<String,String>,String> column=new TableColumn<>(key);
            column.setMinWidth(key.equals("书名")?160:key.contains("时间")?150:85);
            column.setPrefWidth(key.equals("书名")?260:key.contains("时间")?180:110);
            column.setCellValueFactory(c->new ReadOnlyObjectWrapper<>(c.getValue().get(key)));
            if(key.equals("状态")) column.setCellFactory(c->new TableCell<>() {
                @Override protected void updateItem(String value,boolean empty) {
                    super.updateItem(value,empty);setText(null);setGraphic(null);
                    if(!empty&&value!=null) {
                        Label badge=label(value,"circulation-status");
                        if(value.contains("逾期")||value.contains("未缴"))badge.getStyleClass().add("circulation-status-warning");
                        setGraphic(badge);
                    }
                }
            });
            table.getColumns().add(column);
        }
        Label count=label("—","circulation-metric-value");
        Label secondary=label("—","circulation-metric-value");
        HBox metrics=new HBox(12,metric("当前记录",count),metric(fine?"可退余额合计":"待办理",secondary),
                metric(fine?"退款方式":checkout?"取书时限":"借阅期限",label(fine?"原路退回":checkout?"12 小时":"14 天","circulation-metric-value")));
        for(var node:metrics.getChildren())HBox.setHgrow(node,Priority.ALWAYS);
        Label note=label(checkout?"预约后请在12小时内取书，超时自动取消。请核对到场同学及图书后办理借出。":
                fine?"选择已实付账单办理退款，款项退回账单所属用户的校园银行账户。":
                "收到实体图书后确认归还。允许先还书后缴费，找回的挂失图书也可在这里归还。","circulation-note");
        note.setWrapText(true);
        TextField filter=new TextField();filter.setPromptText("搜索账号、姓名、书名或状态");
        Button refresh=new Button("刷新列表");refresh.getStyleClass().add("btn-secondary");
        Button action=new Button(actionText);action.getStyleClass().add("btn-primary");action.setDisable(true);
        Label selection=label("请先选择一条记录","panel-title-small");
        Label detail=label("选中后，这里会显示办理对象和完整记录信息。","circulation-detail");detail.setWrapText(true);
        VBox selectionText=new VBox(5,selection,detail);HBox.setHgrow(selectionText,Priority.ALWAYS);
        HBox selectedCard=new HBox(18,selectionText,action);selectedCard.setAlignment(Pos.CENTER_LEFT);
        selectedCard.getStyleClass().add("circulation-selection");
        boolean[] busy={false},loading={false};long[] version={0};
        Runnable updateAction=()->{
            Map<String,String> row=table.getSelectionModel().getSelectedItem();
            action.setDisable(busy[0]||loading[0]||row==null||(fine&&money(row,"可退金额").signum()<=0));
        };
        table.getSelectionModel().selectedItemProperty().addListener((obs,old,row)->{
            selection.setText(row==null?"请先选择一条记录":row.get("姓名")+" · "+row.get("账号")+"  /  记录 #"+row.get("记录编号"));
            detail.setText(row==null?"选中后，这里会显示办理对象和完整记录信息。":fine?
                    "逾期费 ¥"+row.get("逾期费")+"   赔偿价 ¥"+row.get("赔偿价")+"   可退 ¥"+row.get("可退金额")+"\n"+row.get("原因"):
                    "《"+row.get("书名")+"》 · 图书编号 "+row.get("图书编号")+"\n"+
                    (checkout?"预约："+row.get("预约时间")+"   截止取书："+row.get("取书截止时间"):
                            "借出："+row.get("借阅时间")+"   应还："+row.get("应还时间")));
            updateAction.run();
        });
        Runnable updateCounts=()->{
            count.setText(filtered.size()+" 条");
            secondary.setText(fine?"¥ "+filtered.stream().map(row->money(row,"可退金额")).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString():filtered.size()+" 条");
        };
        filtered.addListener((ListChangeListener<Map<String,String>>)change->updateCounts.run());
        filter.textProperty().addListener((obs,old,text)->filtered.setPredicate(row->row.values().stream().anyMatch(v->v!=null&&v.contains(text.trim()))));
        Runnable load=()->{
            long current=++version[0];loading[0]=true;refresh.setDisable(true);updateAction.run();
            table.setPlaceholder(label("正在加载…","circulation-empty"));
            records.apply(kind).whenComplete((data,error)->Platform.runLater(()->{
                if(closed||current!=version[0])return;
                loading[0]=false;refresh.setDisable(false);
                if(error!=null){table.setPlaceholder(label("加载失败，请刷新重试","circulation-empty"));error(error);}
                else{rows.setAll(data);table.setPlaceholder(label("暂无匹配记录","circulation-empty"));updateCounts.run();}
                updateAction.run();
            }));
        };
        action.setOnAction(event->{
            if(busy[0]||loading[0])return;
            Map<String,String> row=table.getSelectionModel().getSelectedItem();if(row==null)return;
            int id=Integer.parseInt(row.get("记录编号"));
            CompletableFuture<Void> operation;
            if(fine){operation=refund(row,id);if(operation==null)return;}
            else {
                Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,
                        "学生："+row.get("姓名")+"（"+row.get("账号")+"）\n图书："+row.get("书名")+"\n确认已现场核对并办理？",ButtonType.OK,ButtonType.CANCEL);
                confirm.setTitle(actionText);confirm.setHeaderText(actionText);style(confirm.getDialogPane());
                confirm.initOwner(table.getScene().getWindow());
                if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
                operation=checkout?service.lendBook(id):service.returnBook(id);
            }
            busy[0]=true;updateAction.run();
            operation.whenComplete((v,error)->Platform.runLater(()->{
                if(closed)return;busy[0]=false;
                if(error!=null)error(error);else AlertUtil.showInfo("办理成功",actionText+"成功，相关记录已同步");
                load.run();
            }));
        });
        refresh.setOnAction(e->load.run());
        HBox tools=new HBox(10,filter,refresh);HBox.setHgrow(filter,Priority.ALWAYS);
        VBox root=new VBox(12,metrics,note,tools,table,selectedCard);root.getStyleClass().add("circulation-panel");VBox.setVgrow(table,Priority.ALWAYS);
        Tab tab=new Tab(title,root);tab.setOnSelectionChanged(e->{if(tab.isSelected())load.run();});
        return tab;
    }

    DialogPane refundPane(Map<String,String> row) {
        DialogPane pane=new DialogPane();style(pane);
        pane.getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        ((Button)pane.lookupButton(ButtonType.OK)).setText("确认退款");pane.lookupButton(ButtonType.OK).getStyleClass().add("btn-primary");
        TextField target=new TextField(row.get("账号"));target.setId("refundTarget");target.setEditable(false);target.getStyleClass().add("circulation-readonly");
        TextField amount=new TextField();amount.setId("refundAmount");amount.setPromptText("最多 "+row.get("可退金额")+" 元");
        TextField admin=new TextField(ClientSession.getInstance().getUsername());admin.setId("refundAdmin");
        PasswordField password=new PasswordField();password.setId("refundPassword");password.setPromptText("输入当前管理员的登录密码");
        Label validation=label("","circulation-validation");validation.setId("refundValidation");validation.setWrapText(true);
        VBox fields=new VBox(9,label("退款给 "+row.get("姓名"),"panel-title"),label("账单 #"+row.get("记录编号")+" · 可退金额 ¥"+row.get("可退金额"),"circulation-note"),
                new Separator(),label("收款用户账号 · 与原账单绑定","circulation-field-label"),target,
                label("退款金额（元）","circulation-field-label"),amount,new Separator(),
                label("当前管理员用户名","circulation-field-label"),admin,label("管理员登录密码","circulation-field-label"),password,validation);
        fields.getStyleClass().add("circulation-refund-form");fields.setPrefWidth(430);pane.setContent(fields);
        return pane;
    }

    private CompletableFuture<Void> refund(Map<String,String> row,int id) {
        BigDecimal remaining=money(row,"可退金额");
        if(remaining.signum()<=0){AlertUtil.showWarning("无法退款","该记录没有可退的银行实付金额");return null;}
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("图书馆退款 · 账单 #"+id);dialog.setDialogPane(refundPane(row));
        TextField amount=(TextField)dialog.getDialogPane().lookup("#refundAmount");
        TextField admin=(TextField)dialog.getDialogPane().lookup("#refundAdmin");
        PasswordField password=(PasswordField)dialog.getDialogPane().lookup("#refundPassword");
        Label validation=(Label)dialog.getDialogPane().lookup("#refundValidation");
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            try{
                BigDecimal value=new BigDecimal(amount.getText().trim());
                if(value.signum()<=0||value.scale()>2||value.compareTo(remaining)>0)throw new NumberFormatException();
                if(admin.getText().isBlank()||password.getText().isBlank()){validation.setText("请填写管理员用户名和登录密码。");event.consume();}
            }catch(NumberFormatException e){validation.setText("请输入不超过可退金额的正数，最多两位小数。");event.consume();}
        });
        try{
            if(dialog.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return null;
            // 直接使用所选账单的账号，不能由表单输入改变收款人。
            return service.refundFine(id,row.get("账号"),admin.getText().trim(),password.getText(),new BigDecimal(amount.getText().trim()),UUID.randomUUID().toString());
        }finally{password.clear();}
    }
    private static Label label(String text,String style){Label label=new Label(text);label.getStyleClass().add(style);return label;}
    private static VBox metric(String title,Label value){VBox card=new VBox(6,label(title,"circulation-metric-caption"),value);card.getStyleClass().add("circulation-metric");card.setMaxWidth(Double.MAX_VALUE);return card;}
    private static BigDecimal money(Map<String,String> row,String key){try{return new BigDecimal(row.get(key));}catch(Exception e){return BigDecimal.ZERO;}}
    static void style(DialogPane pane){pane.getStyleClass().addAll("library-root","circulation-dialog");pane.getStylesheets().add(LibraryCirculationController.class.getResource("/resources/css/library.css").toExternalForm());}
    private void error(Throwable error){while(error.getCause()!=null)error=error.getCause();AlertUtil.showError("操作失败",error.getMessage());}
}
