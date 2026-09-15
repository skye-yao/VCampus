package util.control;

import com.google.gson.Gson;
import entity.*;
import enums.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import protocol.*;
import service.IStudentClientService;
import util.StudentBatchValidation;
import vo.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.*;

/** Two-step form. Uncertain attempts retain their immutable payload and operation ID. */
public final class StudentBatchDialog extends Dialog<StudentBatchResult> {
    private record Pending(StudentBatchRequest request,List<Student> recipients) {}
    // Retain unresolved operations across page changes/logout within this application process.
    private static final Map<String,Pending> PENDING=new HashMap<>();
    private final String user;
    private final boolean award;
    private final IStudentClientService service;
    private final ObservableList<Student> recipients=FXCollections.observableArrayList();
    private final TableView<Student> table=new TableView<>(recipients);
    private final GridPane form=new GridPane();
    private final TextField name=new TextField(),level=new TextField(),provider=new TextField(),amount=new TextField("0.00");
    private final ComboBox<String> awardName=new ComboBox<>(FXCollections.observableArrayList("国家奖学金","国家励志奖学金","校一等奖学金","校二等奖学金","校三等奖学金","校长奖学金"));
    private final ComboBox<String> type=new ComboBox<>(),status=new ComboBox<>(FXCollections.observableArrayList("待发放","已发放","已取消"));
    private final DatePicker date=new DatePicker();
    private final TextArea description=new TextArea(),errors=new TextArea();
    private final Label summary=new Label(),preview=new Label(),message=new Label();
    private final Button remove=new Button("移除选中学生");
    private final ButtonType nextType=new ButtonType("核对名单",ButtonBar.ButtonData.OTHER);
    private final ButtonType saveType=new ButtonType("确认添加",ButtonBar.ButtonData.OTHER);
    private final ButtonType backType=new ButtonType("返回修改",ButtonBar.ButtonData.OTHER);
    private final ButtonType cancelType=new ButtonType("取消",ButtonBar.ButtonData.CANCEL_CLOSE);
    private StudentBatchRequest attempt;
    private boolean reviewing,inFlight,uncertain,disposed,forceClosing;
    public static boolean hasPending(String user){return PENDING.containsKey(user);}
    public StudentBatchDialog(Window owner,String user,boolean award,List<Student> selected,IStudentClientService service){
        this.user=user;this.service=service;
        InformationDatePicker.install(date);
        Pending pending=PENDING.get(user);this.award=pending==null?award:pending.request().award()!=null;
        recipients.addAll(pending==null?selected:pending.recipients());
        if(owner!=null)initOwner(owner);
        setTitle(this.award?"批量添加奖励":"批量添加资助");setResizable(true);
        getDialogPane().getButtonTypes().addAll(nextType,backType,saveType,cancelType);
        form.setHgap(12);form.setVgap(10);
        description.setPrefRowCount(2);description.setWrapText(true);
        type.setItems(FXCollections.observableArrayList(this.award?List.of("奖学金","荣誉","其他"):List.of("助学金","勤工助学","困难补助","学费减免","其他")));
        type.setValue(this.award?"荣誉":"助学金");status.setValue("待发放");
        if(this.award){row(0,"奖励名称 *",awardName);row(1,"奖励类型 *",type);row(2,"奖励级别",level);row(3,"奖励日期 *",date);row(4,"颁发单位",provider);row(5,"奖励说明",description);}
        else{row(0,"资助名称 *",name);row(1,"资助类型 *",type);row(2,"每人金额（元） *",amount);row(3,"资助日期 *",date);row(4,"资助提供方",provider);row(5,"状态 *",status);row(6,"资助说明",description);}
        column("学号",Student::getStudentId,150);column("姓名",Student::getName,120);column("学院",Student::getCollege,280);
        table.setPrefHeight(190);table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        remove.setOnAction(e->{recipients.removeAll(List.copyOf(table.getSelectionModel().getSelectedItems()));refreshSummary();});
        amount.textProperty().addListener((o,a,b)->refreshSummary());
        errors.setEditable(false);errors.setWrapText(true);errors.setPrefRowCount(4);errors.setVisible(false);errors.setManaged(false);
        summary.setWrapText(true);preview.setWrapText(true);message.setWrapText(true);
        VBox body=new VBox(10,new Label("名单包含跨页和当前筛选范围外的所有已选学生。"),form,summary,table,remove,preview,message,errors);
        body.setPadding(new Insets(14));ScrollPane scroll=new ScrollPane(body);scroll.setFitToWidth(true);scroll.setPrefViewportHeight(650);
        getDialogPane().setContent(scroll);getDialogPane().setPrefWidth(760);
        button(nextType).addEventFilter(javafx.event.ActionEvent.ACTION,e->{e.consume();prepare();});
        button(backType).addEventFilter(javafx.event.ActionEvent.ACTION,e->{e.consume();attempt=null;reviewing=false;renderState();});
        button(saveType).addEventFilter(javafx.event.ActionEvent.ACTION,e->{e.consume();submit();});
        setOnCloseRequest(e->{if(inFlight&&!forceClosing)e.consume();});
        if(pending!=null){attempt=pending.request();restore(attempt);reviewing=true;uncertain=true;message.setText("存在结果未确认的批次，请先重试该批次。不会重复添加已成功的记录。");}
        refreshSummary();renderState();
    }
    private void row(int index,String label,Control input){
        input.setMaxWidth(Double.MAX_VALUE);GridPane.setHgrow(input,Priority.ALWAYS);form.addRow(index,new Label(label),input);
    }
    private void column(String title,java.util.function.Function<Student,String> value,int width){
        TableColumn<Student,String> c=new TableColumn<>(title);c.setCellValueFactory(x->new SimpleStringProperty(value.apply(x.getValue())));c.setPrefWidth(width);table.getColumns().add(c);
    }
    private Button button(ButtonType type){return (Button)getDialogPane().lookupButton(type);}
    private void visible(ButtonType type,boolean visible){button(type).setVisible(visible);button(type).setManaged(visible);}
    private void refreshSummary(){
        String text="已选择 "+recipients.size()+" 人（每批最多200人）";
        if(!award)try{BigDecimal per=StudentBatchValidation.amount(new BigDecimal(amount.getText().trim()));text+="；每人 "+per.toPlainString()+" 元；合计 "+per.multiply(BigDecimal.valueOf(recipients.size())).toPlainString()+" 元";}catch(Exception ignored){text+="；请填写合法的每人金额";}
        summary.setText(text);
    }
    private void renderState(){
        form.setDisable(reviewing||inFlight||uncertain);remove.setDisable(reviewing||inFlight||uncertain);
        visible(nextType,!reviewing&&!uncertain);visible(backType,reviewing&&!uncertain);visible(saveType,reviewing||uncertain);
        button(nextType).setDisable(inFlight);button(backType).setDisable(inFlight);button(saveType).setDisable(inFlight);button(cancelType).setDisable(inFlight);
        button(saveType).setText(uncertain?"重试原批次":"确认添加");button(cancelType).setText(uncertain?"稍后继续":"取消");
        if(reviewing&&attempt!=null)preview.setText("请核对上述名单：将为每名学生添加相同的"+(award?"奖励":"资助")+"。\n"+
                (award?attempt.award().getAwardName():attempt.aid().getAidName())+" · "+type.getValue()+" · "+date.getValue()+"\n"+
                (award?"奖励级别："+level.getText():"状态："+status.getValue())+"；"+(award?"颁发单位：":"提供方：")+provider.getText()+"\n说明："+description.getText());
        else preview.setText("");
    }
    private void prepare(){
        try{
            StudentAward a=null;StudentAid aid=null;
            if(award){a=new StudentAward();a.setAwardName(awardName.getValue());a.setAwardType(switch(Objects.toString(type.getValue(),"")){case "奖学金"->StudentAwardType.SCHOLARSHIP;case "荣誉"->StudentAwardType.HONOR;case "其他"->StudentAwardType.OTHER;default->throw new IllegalArgumentException("请选择奖励类型");});a.setAwardLevel(level.getText());a.setAwardDate(date.getValue()==null?null:Date.valueOf(date.getValue()));a.setOrganization(provider.getText());a.setDescription(description.getText());}
            else{aid=new StudentAid();aid.setAidName(name.getText());aid.setAidType(type.getValue());try{aid.setAmount(new BigDecimal(amount.getText().trim()));}catch(NumberFormatException e){throw new IllegalArgumentException("请填写合法的每人金额");}aid.setAidDate(date.getValue()==null?null:Date.valueOf(date.getValue()));aid.setProvider(provider.getText());aid.setDescription(description.getText());aid.setStatus(switch(Objects.toString(status.getValue(),"")){case "待发放"->StudentAidStatus.PENDING;case "已发放"->StudentAidStatus.ISSUED;case "已取消"->StudentAidStatus.CANCELLED;default->throw new IllegalArgumentException("请选择资助状态");});}
            attempt=StudentBatchValidation.normalize(new StudentBatchRequest(UUID.randomUUID().toString(),recipients.stream().map(Student::getStudentId).toList(),a,aid),award);
            reviewing=true;message.setText("核对无误后确认添加；任何一名学生存在冲突，整批都不会保存。");errors.setVisible(false);errors.setManaged(false);renderState();
        }catch(IllegalArgumentException e){message.setText(e.getMessage());}
    }
    private void submit(){
        if(inFlight||attempt==null||disposed)return;
        inFlight=true;PENDING.put(user,new Pending(attempt,List.copyOf(recipients)));renderState();message.setText("正在校验并保存整批记录...");
        if(award)service.addAwardsBatch(attempt,this::receive);else service.addAidsBatch(attempt,this::receive);
    }
    private void receive(Message response){
        if(disposed)return;inFlight=false;
        StudentBatchResult result=null;
        try{if(response!=null&&response.getData().get("batchResult")!=null){Gson gson=new Gson();result=gson.fromJson(gson.toJson(response.getData().get("batchResult")),StudentBatchResult.class);}}
        catch(RuntimeException ignored){}
        boolean matching=result!=null&&attempt.operationId().equals(result.operationId());
        if(response!=null&&response.getCode()==MessageCode.SUCCESS&&matching&&result.successCount()>0){PENDING.remove(user);forceClosing=true;setResult(result);close();return;}
        if(response!=null&&Boolean.TRUE.equals(response.getData().get("resultConfirmed"))&&matching&&result.successCount()==0){
            PENDING.remove(user);uncertain=false;reviewing=false;attempt=null;
            errors.setText(result.conflicts().stream().map(c->c.studentId()+"  "+Objects.toString(c.name(),"")+"："+c.reason()).collect(java.util.stream.Collectors.joining("\n")));
            errors.setVisible(true);errors.setManaged(true);message.setText("本批次未保存。请移除冲突人员或调整信息后重新核对。");
        }else{uncertain=true;reviewing=true;message.setText((response==null?"未收到响应":Objects.toString(response.getMessage(),"结果未确认"))+"\n请重试原批次；也可稍后继续，原内容和操作编号会保留在本次应用会话中。");}
        renderState();
    }
    private void restore(StudentBatchRequest request){
        if(request.award()!=null){var a=request.award();if(!awardName.getItems().contains(a.getAwardName()))awardName.getItems().add(a.getAwardName());awardName.setValue(a.getAwardName());type.setValue(switch(a.getAwardType()){case SCHOLARSHIP->"奖学金";case HONOR->"荣誉";default->"其他";});level.setText(a.getAwardLevel());date.setValue(a.getAwardDate().toLocalDate());provider.setText(a.getOrganization());description.setText(a.getDescription());}
        else{var a=request.aid();name.setText(a.getAidName());type.setValue(a.getAidType());amount.setText(a.getAmount().toPlainString());date.setValue(a.getAidDate().toLocalDate());provider.setText(a.getProvider());description.setText(a.getDescription());status.setValue(switch(a.getStatus()){case PENDING->"待发放";case ISSUED->"已发放";case CANCELLED->"已取消";});}
    }
    public List<String> submittedStudentIds(){return attempt==null?List.of():attempt.studentIds();}
    public void dispose(){disposed=true;forceClosing=true;close();}
}
