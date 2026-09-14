package controller;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import service.LibraryClientService;
import session.ClientSession;
import util.AlertUtil;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 管理员线下核对借书人后办理，记录编号由所选行提供。 */
public class LibraryCirculationController {
    private final LibraryClientService service=LibraryClientService.getInstance();
    public void show(int selectedTab) {
        Dialog<Void> dialog=new Dialog<>();
        dialog.setTitle("图书馆 · 借还与缴费管理");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/resources/css/library.css").toExternalForm());
        TabPane tabs=new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(panel("预约待借","checkout","借书"),panel("借阅未还","returns","还书"),panel("罚款缴费 / 退款","fine","退款"));
        tabs.getSelectionModel().select(selectedTab);
        tabs.setPrefSize(1060,600);
        dialog.getDialogPane().setContent(tabs);
        dialog.showAndWait();
    }
    private Tab panel(String title,String kind,String actionText) {
        TableView<Map<String,String>> table=new TableView<>();
        var rows=FXCollections.<Map<String,String>>observableArrayList();
<<<<<<< Updated upstream
        var filtered=new FilteredList<>(rows);
        table.setItems(filtered);
        TextField filter=new TextField();filter.setPromptText("输入学生账号、姓名、书名筛选");
=======
        var filtered=new FilteredList<>(rows);table.setItems(filtered);
        String[] columns=fine?new String[]{"账号","姓名","册号","实付","已退款","可退金额","状态"}:
                checkout?new String[]{"账号","姓名","册号","书名","取书截止时间","状态"}:
                new String[]{"账号","姓名","册号","书名","应还时间","状态"};
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
                    "册号 "+row.get("册号")+"   逾期费 ¥"+row.get("逾期费")+"   赔偿价 ¥"+row.get("赔偿价")+"   可退 ¥"+row.get("可退金额")+"\n"+row.get("原因"):
                    "《"+row.get("书名")+"》 · 册号 "+row.get("册号")+"\n"+
                    (checkout?"预约："+row.get("预约时间")+"   截止取书："+row.get("取书截止时间"):
                            "借出："+row.get("借阅时间")+"   应还："+row.get("应还时间")));
            updateAction.run();
        });
        Runnable updateCounts=()->{
            count.setText(filtered.size()+" 条");
            secondary.setText(fine?"¥ "+filtered.stream().map(row->money(row,"可退金额")).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString():filtered.size()+" 条");
        };
        filtered.addListener((ListChangeListener<Map<String,String>>)change->updateCounts.run());
>>>>>>> Stashed changes
        filter.textProperty().addListener((obs,old,text)->filtered.setPredicate(row->row.values().stream().anyMatch(v->v!=null&&v.contains(text.trim()))));
        Button refresh=new Button("刷新");Button action=new Button(actionText);action.getStyleClass().add("btn-primary");
        Label note=new Label(kind.equals("checkout")?"请核对到场同学及图书后点击借书；借期从办理成功起计14天。":
                kind.equals("returns")?"请收到实体图书后点击还书。允许先还书、后缴费；挂失图书找回也可在这里办理。":
                "选择银行实付记录退款，核验当前管理员登录密码。退款原路进入该用户校园账户并记录银行流水。");
        note.setWrapText(true);
        long[] version={0};boolean[] busy={false};
        Runnable load=()->{
            long current=++version[0];refresh.setDisable(true);
            service.getAdminRecords(kind).whenComplete((data,error)->Platform.runLater(()->{
                if(current!=version[0]) return; refresh.setDisable(false);
                if(error!=null) { error(error);return; }
                table.getColumns().clear();rows.setAll(data);
                if(!data.isEmpty()) for(String key:data.get(0).keySet()) {
                    TableColumn<Map<String,String>,String> column=new TableColumn<>(key);
                    column.setPrefWidth(key.equals("原因")?290:key.contains("时间")?165:key.equals("书名")?240:105);
                    column.setCellValueFactory(c->new ReadOnlyObjectWrapper<>(c.getValue().get(key)));
                    table.getColumns().add(column);
                }
                table.setPlaceholder(new Label("暂无记录"));
            }));
        };
        action.setOnAction(event->{
            if(busy[0]) return;
            Map<String,String> row=table.getSelectionModel().getSelectedItem();
            if(row==null) { AlertUtil.showWarning("提示","请选择一条记录");return; }
            int id=Integer.parseInt(row.get("记录编号"));
            CompletableFuture<Void> operation;
            if(kind.equals("fine")) { operation=refund(row,id);if(operation==null)return; }
            else {
                Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,
<<<<<<< Updated upstream
                        "学生："+row.get("姓名")+"（"+row.get("账号")+"）\n图书："+row.get("书名")+"\n确认已现场核对并办理"+actionText+"？",ButtonType.OK,ButtonType.CANCEL);
=======
                        "读者："+row.get("姓名")+"（"+row.get("账号")+"）\n图书："+row.get("书名")+"\n册号："+row.get("册号")+"\n确认已现场核对该册并办理？",ButtonType.OK,ButtonType.CANCEL);
                confirm.setTitle(actionText);confirm.setHeaderText(actionText);style(confirm.getDialogPane());
                confirm.initOwner(table.getScene().getWindow());
>>>>>>> Stashed changes
                if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
                operation=kind.equals("checkout")?service.lendBook(id):service.returnBook(id);
            }
            busy[0]=true;action.setDisable(true);
            operation.whenComplete((v,error)->Platform.runLater(()->{
                busy[0]=false;action.setDisable(false);
                if(error!=null) error(error); else AlertUtil.showInfo("办理成功",actionText+"成功，相关记录已同步");
                load.run();
            }));
        });
        refresh.setOnAction(e->load.run());
        HBox tools=new HBox(10,filter,refresh,action);HBox.setHgrow(filter,Priority.ALWAYS);
        VBox root=new VBox(12,note,tools,table);root.setPadding(new Insets(16));VBox.setVgrow(table,Priority.ALWAYS);
        Tab tab=new Tab(title,root);tab.setOnSelectionChanged(e->{if(tab.isSelected())load.run();});
        return tab;
    }
    private CompletableFuture<Void> refund(Map<String,String> row,int id) {
        BigDecimal remaining=new BigDecimal(row.get("可退金额"));
        if(remaining.signum()<=0) {AlertUtil.showWarning("无法退款","该记录没有可退的银行实付金额");return null;}
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("图书馆退款 · 账单 #"+id);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        TextField target=new TextField(row.get("账号"));
        TextField amount=new TextField();amount.setPromptText("最多 "+remaining+" 元");
        TextField admin=new TextField(ClientSession.getInstance().getUsername());
        PasswordField password=new PasswordField();
        VBox fields=new VBox(8,new Label("收款用户账号"),target,new Label("退款金额（元）"),amount,
                new Label("当前管理员用户名"),admin,new Label("管理员登录密码"),password);
        fields.setPadding(new Insets(16));dialog.getDialogPane().setContent(fields);
        if(dialog.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return null;
        try {
            BigDecimal value=new BigDecimal(amount.getText().trim());
            if(value.signum()<=0||value.scale()>2||value.compareTo(remaining)>0)throw new NumberFormatException();
            return service.refundFine(id,target.getText().trim(),admin.getText().trim(),password.getText(),value,UUID.randomUUID().toString());
        } catch(NumberFormatException e) { AlertUtil.showWarning("金额无效","请输入不超过可退金额的正数，最多两位小数");return null; }
        finally {password.clear();}
    }
    private void error(Throwable error) {
        while(error.getCause()!=null)error=error.getCause();
        AlertUtil.showError("操作失败",error.getMessage());
    }
}
