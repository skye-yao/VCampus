package controller;
import app.ClientMain;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import entity.*;
import enums.StudentChangeStatus;
import enums.StudentAwardType;
import enums.StudentAidStatus;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import service.StudentClientService; import service.IStudentClientService;
import session.ClientSession;
import protocol.*;
import util.AlertUtil;
import util.pdf.StudentPdfExport;
import util.spreadsheet.StudentExcelExport;
import vo.*;
import java.io.File;
import java.lang.reflect.*;
import java.sql.Date;
import java.util.*;

public class StudentController {
    @FXML private Label titleLabel,statusBarLabel,avatarLabel,sidebarAvatarLabel,sidebarNameLabel,sidebarMajorLabel,nameLabel,studentMetaLabel,pendingHintLabel,categoryValue,statusValue,gradeValue,inSchoolValue;
    @FXML private TabPane studentTabs;
    @FXML private Tab overviewTab,detailTab,experienceTab,adminListTab,reviewTab;
    @FXML private util.control.InformationReviewStatusPane reviewStatusPane;
    @FXML private ScrollPane detailScrollPane;
    @FXML private GridPane baseInfoGrid,studyInfoGrid,admissionInfoGrid,contactInfoGrid;
    @FXML private GridPane reviewStudentBaseGrid,reviewStudentStudyGrid,reviewStudentAdmissionGrid,reviewStudentContactGrid;
    @FXML private VBox reviewStudentRecords;
    @FXML private VBox studentDetailSidebar,adminDetailSidebar,reviewOverviewPane,reviewDetailPane,experienceCardContainer,familyCardContainer,adminReadOnlyInfoPane,adminExperienceCardContainer,adminFamilyCardContainer;
    @FXML private Button detailReturnButton,editBaseButton,editStudyButton,editAdmissionButton,editContactButton,exportPdfButton,exportStudentsButton,managementNavButton,maintenanceNavButton,editExperienceButton,deleteExperienceButton,editFamilyButton,deleteFamilyButton;
    @FXML private Button baseIndexButton,studyIndexButton,admissionIndexButton,contactIndexButton,adminDetailManagementNavButton,adminDetailMaintenanceNavButton;
    @FXML private VBox adminRecordMaintenancePane;
    @FXML private TextArea reviewRemarkArea;
    @FXML private TextField searchIdField,searchNameField,searchCollegeField,pageNumberField,reviewSearchIdField,reviewSearchStudentField,reviewSearchStatusField,reviewPageNumberField;
    @FXML private Label pageSummaryLabel,reviewPageSummaryLabel,adminWorkspaceTitle,selectedStudentCountLabel;
    @FXML private TilePane awardTile,aidTile,adminAwardTile,adminAidTile;
    @FXML private TableView<StudentChangeRequest> reviewTable;
    @FXML private TableColumn<StudentChangeRequest,String> reviewNameCol,reviewStudentCol,reviewTimeCol,reviewProcessedTimeCol,reviewStatusCol;
    @FXML private TableColumn<StudentChangeRequest,Void> reviewActionCol;
    @FXML private TableView<Student> studentTable;
    @FXML private TableColumn<Student,Void> stuSelectCol;
    @FXML private TableColumn<Student,String> stuIdCol,stuNameCol,stuGenderCol,stuGradeCol,stuCollegeCol,stuMajorCol,stuStatusCol;
    @FXML private TableColumn<Student,Void> stuActionCol;
    @FXML private TableView<StudentAward> maintenanceAwardTable;
    @FXML private TableColumn<StudentAward,String> maintenanceAwardIdCol,maintenanceAwardStudentCol,maintenanceAwardNameCol,maintenanceAwardTypeCol,maintenanceAwardLevelCol,maintenanceAwardDateCol,maintenanceAwardOrganizationCol,maintenanceAwardDescriptionCol;
    @FXML private TableView<StudentAid> maintenanceAidTable;
    @FXML private TableColumn<StudentAid,String> maintenanceAidIdCol,maintenanceAidStudentCol,maintenanceAidNameCol,maintenanceAidTypeCol,maintenanceAidAmountCol,maintenanceAidDateCol,maintenanceAidProviderCol,maintenanceAidStatusCol,maintenanceAidDescriptionCol;
    @FXML private Button approveButton,rejectButton;
    @FXML private ToggleButton unfinishedReviewButton,completedReviewButton;
    private static final int PAGE_SIZE=20;
    //创建StudentClientService，把单例Scoket客户端传入，让它能够发送请求。
    private final IStudentClientService service=new StudentClientService(network.SocketClient.getInstance());
    private final Gson gson=new Gson();
    //定义学生的字段能否进行编辑
    private static final Set<String> STUDENT_EDITABLE=Set.of(
            "politicalStatus","nationality","gender","idType","idNumber","idIssueDate","birthDate",
            "nativePlace","householdType","birthPlace","sourcePlace","registeredResidence","leagueMember",
            "leagueJoinDate","partyMember","partyJoinDate","healthStatus","campus","trainingMode",
            "schoolingLength","counselorName","counselorPhone","candidateCategory","admissionDate",
            "admissionMethod","graduationSchool","middleSchoolClass","middleSchoolTeacher","telephone",
            "mobile","email","qq","wechat","campusAddress","emergencyContact","emergencyPhone");
    //定义学生提交时必填的字段集合
    private static final Set<String> REQUIRED=Set.of(
            "politicalStatus","nationality","gender","idType","idNumber","idIssueDate","birthDate",
            "nativePlace","householdType","birthPlace","sourcePlace","registeredResidence","leagueMember",
            "healthStatus","graduationSchool","telephone","mobile","emergencyContact","emergencyPhone");
    private static final Set<String> DATE_FIELDS=Set.of("idIssueDate","birthDate","leagueJoinDate","partyJoinDate","admissionDate");
    private final List<Control> editable=new ArrayList<>();
    private boolean editing;
    private boolean enteringEdit;
    private long editEntryVersion;
    private StudentOverviewVO overview;
    private List<Student> students=new ArrayList<>(),filteredStudents=new ArrayList<>();
    private final Set<String> selectedStudentIds=new LinkedHashSet<>();
    private CheckBox selectCurrentPageCheckBox;
    private int currentPage=1;//学生列表初始显示第1页
    private List<StudentChangeRequest> reviewRequests=new ArrayList<>(),filteredReviewRequests=new ArrayList<>();
    private int reviewCurrentPage=1;//审核列表初始显示第1页
    private boolean showCompletedReviews;
    private boolean adminMaintenanceMode;
    private StudentChangeRequest selected;
    private Student reviewBaseStudent;
    private StudentOverviewVO reviewStudentOverview;
    private StudentExperience selectedExperience;
    private StudentFamilyMember selectedFamilyMember;
    private Pane selectedExperienceCard,selectedFamilyCard;
    private boolean disposed;
    private void runOnPage(Runnable action){util.Fx.run(()->{if(!disposed)action.run();});}
    @FXML public void initialize() {
        ClientMain.setPageCleanup(()->{disposed=true;resetEditState();service.dispose();});
        service.onEditLeaseLost(()->{if(!disposed){resetEditState();if(overview!=null)render(overview);setStatus("编辑占用已失效，请重新进入编辑页面");}});
        setupTables();
        setupRole();
        reviewStatusPane.setVisible(!isAdmin());
        reviewStatusPane.setManaged(!isAdmin());
        reviewStatusPane.setOnCancel(this::cancelPendingRequest);//方法引用，需要执行撤销时，调用当前对象的这个方法
        refreshData();
    }
    private boolean cancellingRequest; //正在撤销标记

    //撤销操作
    private void cancelPendingRequest() {
        if(isAdmin() || cancellingRequest || overview==null || overview.getPendingRequest()==null)return;//如果是管理员、正在撤销、未加载数据、没有待审核申请，就不会具有撤销申请这一个选项
        StudentChangeRequest request=overview.getPendingRequest();//取出待审核申请
        if(request.getStatus()!=StudentChangeStatus.PENDING)return;
        cancellingRequest=true;//标记正在撤销
        reviewStatusPane.setCancelling(true);
        service.cancelChangeRequest(request.getRequestId(),m->runOnPage(()->{
            cancellingRequest=false;
            reviewStatusPane.setCancelling(false);
            if(ok(m)){
                resetEditState();
                request.setStatus(StudentChangeStatus.CANCELLED);
                overview.setPendingRequest(null);
                overview.setLatestRequest(request);
                render(overview);
                setStatus("申请已撤销，可以重新编辑并提交");
            }else{
                setStatus(message(m,"撤销失败，请刷新后重试"));
                service.queryOverview(this::onOverview);
            }
        }));
    }

    //加载学籍信息
    private void refreshData() {
        setStatus("正在加载学籍信息...");
        if(isAdmin())loadAdmin();//如果是管理员，加载管理员需要的页面（列表和审核页面）
        else service.queryOverview(this::onOverview);
    }

    //学籍页面的返回按钮
    @FXML private void handleBack() {
        /*让之前尚未完成的“进入编辑”回调失效
        * 清除“正在申请进入编辑”的状态
        * 关闭编辑租约
        * */
        resetEditState();//如果正在编辑，请求先释放编辑锁
        ClientMain.switchScene("/resources/fxml/MainView.fxml");//返回主界面
    }

    //刷新按钮，刷新页面信息
    @FXML private void handleRefresh() {
        if(editing){releaseEditLock();editing=false;}//如果正在编辑，释放锁并且退出编辑状态
        if(isAdmin()&&studentTabs.getSelectionModel().getSelectedItem()==detailTab
                &&overview!=null&&overview.getStudent()!=null) {
            setStatus("正在刷新学生详情...");
            service.queryStudentOverview(overview.getStudent().getStudentId(),this::onOverview);
        } else refreshData();
    }
    //查看详情选项卡
    @FXML private void handleViewDetails() {
        studentTabs.getSelectionModel().select(detailTab);
    }
    //查看个人经历选项卡
    @FXML private void handleViewExperiences(){studentTabs.getSelectionModel().select(experienceTab);}
    //返回选项卡
    @FXML private void handleBackFromExperiences(){studentTabs.getSelectionModel().select(overviewTab);}
    //
    @FXML private void handleShowOverview() {
        studentTabs.getSelectionModel().select(overviewTab);
    }
    //管理员切换到审核页
    @FXML private void handleShowReview() {
        if(isAdmin())studentTabs.getSelectionModel().select(reviewTab);
    }
    //关闭维护模式
    @FXML private void handleShowStudentManagement(){adminMaintenanceMode=false;applyAdminMode();}
    //开启维护模式
    @FXML private void handleShowStudentMaintenance(){adminMaintenanceMode=true;applyAdminMode();}
    //切换到教师信息页面
    @FXML private void handleOpenTeacherInformation(){ClientMain.switchScene("/resources/fxml/TeacherView.fxml");}
    //开始应用到管理员模式，如果不是管理员直接返回
    private void applyAdminMode(){
        if(!isAdmin())return;
        studentTabs.getSelectionModel().select(adminListTab);//显示学生列表
        adminWorkspaceTitle.setText(adminMaintenanceMode?"学生信息维护":"学生信息管理");
        managementNavButton.getStyleClass().removeAll("student-side-button","student-side-button-active");
        maintenanceNavButton.getStyleClass().removeAll("student-side-button","student-side-button-active");
        managementNavButton.getStyleClass().add(adminMaintenanceMode?"student-side-button":"student-side-button-active");
        maintenanceNavButton.getStyleClass().add(adminMaintenanceMode?"student-side-button-active":"student-side-button");
        stuActionCol.setText("操作");studentTable.refresh();
    }

    //全局编辑状态
    @FXML private void handleEditBase() {
        beginGlobalEdit();
    }
    @FXML private void handleEditStudy() {
        beginGlobalEdit();
    }
    @FXML private void handleEditAdmission() {
        beginGlobalEdit();
    }
    @FXML private void handleEditContact() {
        beginGlobalEdit();
    }

    //返回信息的字段表
    private List<String> baseFields() {
        return List.of(
                "UID", "studentId", "name", "politicalStatus", "nationality", "gender",
                "idType", "idNumber", "idIssueDate", "birthDate", "nativePlace", "householdType",
                "birthPlace", "sourcePlace", "registeredResidence", "leagueMember", "leagueJoinDate",
                "partyMember", "partyJoinDate", "healthStatus"
        );
    }
    private List<String> studyFields() {
        return List.of(
                "studentCategory", "registered", "inSchool", "studentStatus", "campus", "grade",
                "college", "major", "className", "educationLevel", "trainingMode", "schoolingLength",
                "counselorName", "counselorPhone"
        );
    }
    private List<String> admissionFields() {
        return List.of(
                "candidateCategory", "admissionDate", "admissionMethod",
                "graduationSchool", "middleSchoolClass", "middleSchoolTeacher"
        );
    }
    private List<String> contactFields() {
        return List.of(
                "telephone", "mobile", "email", "qq", "wechat",
                "campusAddress", "emergencyContact", "emergencyPhone"
        );
    }

    //定义统一的编辑入口
    private void beginGlobalEdit() {
        if(overview==null||overview.getStudent()==null)return;
        if(!isAdmin()&&overview.getPendingRequest()!=null) {
            setStatus("当前修改申请正在审核中，审核完成后才能再次修改");
            return;
        }
        if(editing||enteringEdit||cancellingRequest)return;
        enteringEdit=true;
        long expected=++editEntryVersion;
        setStatus("正在进入编辑状态...");
        String studentId=overview.getStudent().getStudentId();
        service.beginEdit(studentId,m->runOnPage(()-> {
            if(expected!=editEntryVersion)return;
            enteringEdit=false;
            if(!ok(m)){
                String error=message(m,"暂时无法进入编辑状态");
                setStatus(error);AlertUtil.showError("无法编辑",error);return;
            }
            try{openGlobalEditor();}
            catch(RuntimeException error){
                resetEditState();render(overview);
                setStatus("编辑页面加载失败");
                AlertUtil.showError("无法编辑","编辑页面加载失败："+error.getMessage());
            }
        }));
    }

    //把所有区域变为编辑表单
    private void openGlobalEditor() {
        editable.clear();editing=true;
        fillEditable(baseInfoGrid,baseFields());
        fillEditable(studyInfoGrid,studyFields());
        fillEditable(admissionInfoGrid,admissionFields());
        fillEditable(contactInfoGrid,contactFields());
        Button cancel=new Button("取消");
        cancel.getStyleClass().add("btn-secondary");
        cancel.setOnAction(e->cancelGlobalEdit());
        Button submit=new Button(isAdmin()?"提交":"提交申请");
        submit.getStyleClass().add("btn-primary");
        submit.setOnAction(e->handleSubmitChange());
        HBox actions=new HBox(8,cancel,submit);
        actions.setAlignment(Pos.CENTER_RIGHT);
        contactInfoGrid.add(actions,0,4,6,1);
        setStatus(isAdmin()?"全部信息已进入编辑状态，提交后直接生效":"全部信息已进入编辑状态，提交后等待审核");
    }

    //修改编辑样式
    private void fillEditable(GridPane grid,List<String> names) {
        prepareSixColumns(grid);
        grid.getChildren().clear();
        Student s=overview.getStudent();
        for(int i=0;i<names.size();i++) {
            String n=names.get(i);
            int[] p=fieldPosition(grid,n,i);
            Label key=new Label(title(n));
            //必填的添加标识
            if(!isAdmin()&&REQUIRED.contains(n)) {
                Label star=new Label("*");star.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                key.setGraphic(star);key.setContentDisplay(ContentDisplay.RIGHT);
            }
            styleFieldKey(key);
            Control value=createEditor(n,safe(read(s,n)));
            value.setMinWidth(0);
            value.setMaxWidth(Double.MAX_VALUE);
            value.setUserData(n);
            value.getStyleClass().add("form-control");
            Tooltip.install(value,new Tooltip(controlValue(value)));
            boolean allowed=isAdmin()||STUDENT_EDITABLE.contains(n);
            value.setDisable(!allowed);
            if(allowed)editable.add(value);
            grid.add(key,p[1]*2,p[0]);
            grid.add(value,p[1]*2+1,p[0]);
            if(fieldSpansRemainder(grid,n))GridPane.setColumnSpan(value,5-p[1]*2);
        }
    }
    private void resetEditState(){
        ++editEntryVersion;enteringEdit=false;editing=false;
        editable.clear();service.releaseEditLease();
    }

    //取消编辑
    private void cancelGlobalEdit() {
        resetEditState();
        render(overview);//重新生成只读界面
        setStatus("已取消编辑");
    }

    //创建编辑
    private Control createEditor(String name,String initial) {
        Control control;
        if(DATE_FIELDS.contains(name)) {
            DatePicker picker=new DatePicker();
            if(!initial.isBlank())try{picker.setValue(java.time.LocalDate.parse(initial));}catch(Exception ignored){}
            control=picker;
        } else if("idType".equals(name)) {
            control=new ComboBox<>(FXCollections.observableArrayList("居民身份证","港澳台居民居住证","护照","其他"));
            ((ComboBox<String>)control).setValue(initial);
        } else if("householdType".equals(name)) {
            control=new ComboBox<>(FXCollections.observableArrayList("城镇户口","农村居民户口","集体户口","其他"));
            ((ComboBox<String>)control).setValue(initial);
        } else if(Set.of("leagueMember","partyMember","registered","inSchool").contains(name)) {
            control=new ComboBox<>(FXCollections.observableArrayList("是","否"));
            ((ComboBox<String>)control).setValue(Set.of("true","1","是","在籍","在校").contains(initial)?"是":"否");
        } else control=new TextField(initial);
        control.setUserData(name);control.getStyleClass().add("form-control");
        return control;
    }
    //定义从不同输入控件读取字符串的方法
    private String controlValue(Control control) {
        if(control instanceof TextInputControl text)return safe(text.getText());
        if(control instanceof DatePicker date)return date.getValue()==null?"":date.getValue().toString();
        if(control instanceof ComboBox<?> combo)return combo.getValue()==null?"":safe(combo.getValue());
        return "";
    }
    private void releaseEditLock(){if(overview!=null&&overview.getStudent()!=null)service.endEdit(overview.getStudent().getStudentId(),m->{});}
    @FXML private void handleIndexBase() {
        setDetailIndexActive(baseIndexButton);
        scrollDetail(0.0);
    }
    @FXML private void handleIndexStudy() {
        setDetailIndexActive(studyIndexButton);
        scrollDetail(0.34);
    }
    @FXML private void handleIndexAdmission() {
        setDetailIndexActive(admissionIndexButton);
        scrollDetail(0.68);
    }
    @FXML private void handleIndexContact() {
        setDetailIndexActive(contactIndexButton);
        scrollDetail(1.0);
    }
    private void setDetailIndexActive(Button active) {
        for(Button button:List.of(baseIndexButton,studyIndexButton,admissionIndexButton,contactIndexButton)) {
            button.getStyleClass().removeAll("student-side-button","student-side-button-active");
            button.getStyleClass().add(button==active?"student-side-button-active":"student-side-button");
        }
    }
    private void syncAdminDetailNavigation() {
        if(adminDetailManagementNavButton==null||adminDetailMaintenanceNavButton==null)return;
        adminDetailManagementNavButton.getStyleClass().removeAll("student-side-button","student-side-button-active");
        adminDetailMaintenanceNavButton.getStyleClass().removeAll("student-side-button","student-side-button-active");
        adminDetailManagementNavButton.getStyleClass().add(adminMaintenanceMode?"student-side-button":"student-side-button-active");
        adminDetailMaintenanceNavButton.getStyleClass().add(adminMaintenanceMode?"student-side-button-active":"student-side-button");
    }
    @FXML private void handleExportPdf() {
        if(overview==null||overview.getStudent()==null){
            AlertUtil.showWarning("暂时无法导出", "学籍信息尚未加载完成，请稍后重试。");
            return;
        }
        Student student=overview.getStudent();
        List<StudentAward> awards=overview.getAwards()==null?List.of():List.copyOf(overview.getAwards());
        FileChooser chooser=new FileChooser();
        chooser.setTitle("导出信息");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF 文件 (*.pdf)", "*.pdf"));
        chooser.setInitialFileName(exportFileName(student));//设置默认文件名
        //添加下载的默认路径
        File downloadsDirectory = new File(
                System.getProperty("user.home"),
                "Downloads"
        );
        if (downloadsDirectory.isDirectory()) {
            chooser.setInitialDirectory(downloadsDirectory);
        }
        File selected=chooser.showSaveDialog(studentTabs.getScene().getWindow());
        if(selected==null)return;
        File output=selected.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")
                ?selected:new File(selected.getParentFile(),selected.getName()+".pdf");
        exportPdfButton.setDisable(true);
        setStatus("正在导出学生信息...");
        Task<Void> task=new Task<>() {
            @Override protected Void call() throws Exception {
                StudentPdfExport.export(student,overview.getExperiences(),awards,overview.getFamilyMembers(),output);
                return null;
            }
        };
        task.setOnSucceeded(event->{if(disposed)return;
            exportPdfButton.setDisable(false);
            setStatus("学生信息已导出");
            AlertUtil.showInfo("导出成功", "PDF 已保存至：\n"+output.getAbsolutePath());
        });
        task.setOnFailed(event->{if(disposed)return;
            exportPdfButton.setDisable(false);
            setStatus("学生信息导出失败");
            Throwable error=task.getException();
            AlertUtil.showError("导出失败", error==null?"无法生成 PDF 文件。":"无法生成 PDF 文件：\n"+error.getMessage());
        });
        Thread worker=new Thread(task,"student-pdf-export");
        worker.setDaemon(true);
        worker.start();
    }
    //生成默认的pdf文件名
    private String exportFileName(Student student) {
        String identity=safe(student.getStudentId());
        String name=safe(student.getName());
        String base=(identity+"_"+name+"_学籍信息").replaceAll("[\\\\/:*?\"<>|]", "_");
        return base.replaceAll("^_+|_+$", "")+".pdf";
    }
    //滚动前先切到详情页
    private void scrollDetail(double value) {
        studentTabs.getSelectionModel().select(detailTab);
        runOnPage(()->detailScrollPane.setVvalue(value));
    }
    //设置角色登陆显示页面
    private void setupRole() {
        if(isAdmin()) {
            studentTabs.getTabs().removeAll(overviewTab,experienceTab);
            titleLabel.setText("学籍管理");
            studentDetailSidebar.setVisible(false);
            studentDetailSidebar.setManaged(false);
            adminDetailSidebar.setVisible(true);
            adminDetailSidebar.setManaged(true);
            detailReturnButton.setText("← 返回");
            studentTabs.getSelectionModel().select(adminListTab);
        }
        else {
            studentTabs.getTabs().removeAll(adminListTab,reviewTab);
            titleLabel.setText("学籍信息");
            adminDetailSidebar.setVisible(false);
            adminDetailSidebar.setManaged(false);//隐藏管理员侧栏并取消占位
            detailReturnButton.setText("← 返回");
        }
    }
    //判断当前会话角色是不是管理员
    private boolean isAdmin() {
        String r=ClientSession.getInstance().getRole();//获取当前会话的角色字符串
        return "ADMIN".equalsIgnoreCase(safe(r))||"管理员".equals(r);
    }
    //管理员端返回学生列表
    @FXML private void handleReturnFromDetails() {
        if(isAdmin())handleReturnToStudentList();
        else handleShowOverview();
    }
    //初始化
    private void setupTables() {
        selectCurrentPageCheckBox=new CheckBox();
        selectCurrentPageCheckBox.setOnAction(event->{
            for(Student student:filteredStudents)//筛选结果
            {
                if(selectCurrentPageCheckBox.isSelected())selectedStudentIds.add(student.getStudentId());
                else selectedStudentIds.remove(student.getStudentId());
            }
            studentTable.refresh();updateStudentSelectionState();
        });
        stuSelectCol.setGraphic(selectCurrentPageCheckBox);
        stuSelectCol.setCellFactory(column->new TableCell<>() {
            private final CheckBox checkBox=new CheckBox(); {
                setAlignment(Pos.CENTER);
                checkBox.setOnAction(event->{
                    int index=getIndex();
                    if(index<0||index>=getTableView().getItems().size())return;
                    Student student=getTableView().getItems().get(index);
                    if(checkBox.isSelected())selectedStudentIds.add(student.getStudentId());
                    else selectedStudentIds.remove(student.getStudentId());
                    updateStudentSelectionState();
                });
            }
            @Override protected void updateItem(Void item,boolean empty){
                super.updateItem(item,empty);
                if(empty||getIndex()>=getTableView().getItems().size())setGraphic(null);
                else{
                    checkBox.setSelected(selectedStudentIds.contains(getTableView().getItems().get(getIndex()).getStudentId()));
                    setGraphic(checkBox);
                }
            }
        });
        stuIdCol.setCellValueFactory(c->text(c.getValue().getStudentId()));
        stuNameCol.setCellValueFactory(c->text(c.getValue().getName()));
        stuGenderCol.setCellValueFactory(c->text(c.getValue().getGender()));
        stuGradeCol.setCellValueFactory(c->text(c.getValue().getGrade()));
        stuCollegeCol.setCellValueFactory(c->text(c.getValue().getCollege()));
        stuMajorCol.setCellValueFactory(c->text(c.getValue().getMajor()));
        stuStatusCol.setCellValueFactory(c->text(c.getValue().getStudentStatus()));
        stuActionCol.setCellFactory(column->new TableCell<>() {
            private final Button button=new Button(); {
                button.getStyleClass().add("table-action-button");button.setOnAction(e-> {
                    int index=getIndex();if(index<0||index>=getTableView().getItems().size())return;
                    Student row=getTableView().getItems().get(index);showStudentDetails(row);
                }
                );
            }
            protected void updateItem(Void item,boolean empty) {
                super.updateItem(item,empty);button.setText(adminMaintenanceMode?"编辑":"查看详情");setGraphic(empty||getIndex()<0||getIndex()>=getTableView().getItems().size()?null:button);
            }
        }
        );
        reviewActionCol.setCellFactory(column->new TableCell<>() {
            private final Button button=new Button("查看详情"); {
                button.getStyleClass().add("table-action-button");button.setOnAction(e-> {
                    StudentChangeRequest row=getTableView().getItems().get(getIndex());showReviewDetail(row);
                }
                );
            }
            protected void updateItem(Void item,boolean empty) {
                super.updateItem(item,empty);setGraphic(empty?null:button);
            }
        }
        );
        reviewNameCol.setCellValueFactory(c->text(studentName(c.getValue().getStudentId())));
        reviewStudentCol.setCellValueFactory(c->text(c.getValue().getStudentId()));
        reviewTimeCol.setCellValueFactory(c->text(c.getValue().getSubmitTime()));
        reviewProcessedTimeCol.setCellValueFactory(c->text(c.getValue().getReviewTime()));
        reviewStatusCol.setCellValueFactory(c->text(status(c.getValue().getStatus())));
        reviewActionCol.setSortable(false);
        reviewTable.setSortPolicy(table->{
            Comparator<StudentChangeRequest> comparator=table.getComparator();
            if(comparator!=null)filteredReviewRequests.sort(comparator);
            reviewCurrentPage=1;refreshReviewPage();return true;
        });
        maintenanceAwardIdCol.setCellValueFactory(c->text(c.getValue().getAwardId()));
        maintenanceAwardStudentCol.setCellValueFactory(c->text(c.getValue().getStudentId()));
        maintenanceAwardNameCol.setCellValueFactory(c->text(c.getValue().getAwardName()));
        maintenanceAwardTypeCol.setCellValueFactory(c->text(awardTypeText(c.getValue().getAwardType())));
        maintenanceAwardLevelCol.setCellValueFactory(c->text(c.getValue().getAwardLevel()));
        maintenanceAwardDateCol.setCellValueFactory(c->text(c.getValue().getAwardDate()));
        maintenanceAwardOrganizationCol.setCellValueFactory(c->text(c.getValue().getOrganization()));
        maintenanceAwardDescriptionCol.setCellValueFactory(c->text(c.getValue().getDescription()));
        maintenanceAidIdCol.setCellValueFactory(c->text(c.getValue().getAidId()));
        maintenanceAidStudentCol.setCellValueFactory(c->text(c.getValue().getStudentId()));
        maintenanceAidNameCol.setCellValueFactory(c->text(c.getValue().getAidName()));
        maintenanceAidTypeCol.setCellValueFactory(c->text(aidTypeText(c.getValue().getAidType())));
        maintenanceAidAmountCol.setCellValueFactory(c->text(c.getValue().getAmount()));
        maintenanceAidDateCol.setCellValueFactory(c->text(c.getValue().getAidDate()));
        maintenanceAidProviderCol.setCellValueFactory(c->text(c.getValue().getProvider()));
        maintenanceAidStatusCol.setCellValueFactory(c->text(aidStatusText(c.getValue().getStatus())));
        maintenanceAidDescriptionCol.setCellValueFactory(c->text(c.getValue().getDescription()));
        studentTable.setOnMouseClicked(e-> {
            if(e.getClickCount()==2)handleShowSelectedStudent();//双击选中学生
        }
        );
    }

    //解析返回数据、加载学生和申请
    private <T>T data(Message m,String key,Type type) {
        Object v=m==null?null:m.getData().get(key);
        return v==null?null:gson.fromJson(gson.toJson(v),type);
    }

    //定义总览请求的回调方法
    private void onOverview(Message m) {
        runOnPage(()-> {
            if(!ok(m)) {
                setStatus(message(m,"学籍信息加载失败"));return;
            }
            resetEditState();
            //读取overview，转换成StudentOverviewVO
            overview=data(m,"overview",StudentOverviewVO.class);
            if(!isAdmin()&&overview!=null&&overview.getStudent()!=null&&safe(overview.getStudent().getUID()).isBlank())
                overview.getStudent().setUID(safe(ClientSession.getInstance().getUsername()));
            render(overview);setStatus("学籍信息已更新");
        }
        );
    }
    //管理员数据加载
    private void loadAdmin() {
        service.listStudents(m->runOnPage(()-> {
            if(ok(m)) {
                List<Student> v=data(m,"students",new TypeToken<List<Student>>() {
                }
                .getType());students=v==null?new ArrayList<>():v;filteredStudents=new ArrayList<>(students);selectedStudentIds.clear();currentPage=1;refreshStudentPage();reviewTable.refresh();
            }
            else setStatus(message(m,"学生列表加载失败"));
        }
        ));
        service.listPendingRequests(m->runOnPage(()-> {
            List<StudentChangeRequest> v=ok(m)?data(m,"requests",new TypeToken<List<StudentChangeRequest>>() {
            }
            .getType()):List.of();reviewRequests=v==null?new ArrayList<>():new ArrayList<>(v);applyReviewBucket();
        }
        ));
    }
    //搜索审核申请
    @FXML private void handleSearchReviews() {
        String nameKeyword=safe(reviewSearchIdField.getText()).toLowerCase();
        String studentId=safe(reviewSearchStudentField.getText()).toLowerCase();
        String state=safe(reviewSearchStatusField.getText()).toLowerCase();
        filteredReviewRequests=new ArrayList<>(reviewRequests.stream().filter(this::matchesReviewBucket).filter(request->
                (nameKeyword.isEmpty()||studentName(request.getStudentId()).toLowerCase().contains(nameKeyword))&&
                (studentId.isEmpty()||safe(request.getStudentId()).toLowerCase().contains(studentId))&&
                (state.isEmpty()||(status(request.getStatus())+" "+String.valueOf(request.getStatus())).toLowerCase().contains(state)))
                .toList());
        applyCurrentReviewSort();reviewCurrentPage=1;refreshReviewPage();
    }
    @FXML private void handleResetReviewSearch() {
        reviewSearchIdField.clear();reviewSearchStudentField.clear();reviewSearchStatusField.clear();
        applyReviewBucket();
    }
    //清空对话框按钮
    @FXML private void handleClearReviewName(){reviewSearchIdField.clear();handleSearchReviews();}
    @FXML private void handleClearReviewStudent(){reviewSearchStudentField.clear();handleSearchReviews();}
    @FXML private void handleClearReviewStatus(){reviewSearchStatusField.clear();handleSearchReviews();}
    @FXML private void handleShowUnfinishedReviews(){showCompletedReviews=false;applyReviewBucket();}
    @FXML private void handleShowCompletedReviews(){showCompletedReviews=true;applyReviewBucket();}
    private boolean matchesReviewBucket(StudentChangeRequest request){return showCompletedReviews?request.getStatus()!=StudentChangeStatus.PENDING:request.getStatus()==StudentChangeStatus.PENDING;}
    private void applyReviewBucket(){
        unfinishedReviewButton.setSelected(!showCompletedReviews);completedReviewButton.setSelected(showCompletedReviews);
        reviewProcessedTimeCol.setVisible(showCompletedReviews);
        filteredReviewRequests=new ArrayList<>(reviewRequests.stream().filter(this::matchesReviewBucket).toList());applyCurrentReviewSort();reviewCurrentPage=1;refreshReviewPage();
    }
    private void applyCurrentReviewSort(){
        Comparator<StudentChangeRequest> comparator=reviewTable.getComparator();
        if(comparator!=null)filteredReviewRequests.sort(comparator);
    }
    private int reviewPageCount(){return Math.max(1,(filteredReviewRequests.size()+PAGE_SIZE-1)/PAGE_SIZE);}
    private void refreshReviewPage() {
        reviewCurrentPage=Math.max(1,Math.min(reviewCurrentPage,reviewPageCount()));
        int from=Math.min((reviewCurrentPage-1)*PAGE_SIZE,filteredReviewRequests.size());
        int to=Math.min(from+PAGE_SIZE,filteredReviewRequests.size());
        reviewTable.setItems(FXCollections.observableArrayList(filteredReviewRequests.subList(from,to)));
        reviewPageSummaryLabel.setText("共"+filteredReviewRequests.size()+"条  共"+reviewPageCount()+"页");
        reviewPageNumberField.setText(String.valueOf(reviewCurrentPage));
    }
    @FXML private void handleReviewFirstPage(){reviewCurrentPage=1;refreshReviewPage();}
    @FXML private void handleReviewPreviousPage(){if(reviewCurrentPage>1)reviewCurrentPage--;refreshReviewPage();}
    @FXML private void handleReviewNextPage(){if(reviewCurrentPage<reviewPageCount())reviewCurrentPage++;refreshReviewPage();}
    @FXML private void handleReviewLastPage(){reviewCurrentPage=reviewPageCount();refreshReviewPage();}
    @FXML private void handleReviewGoPage(){
        try{reviewCurrentPage=Integer.parseInt(reviewPageNumberField.getText().trim());}
        catch(Exception e){setStatus("请输入有效的审核页码");}
        refreshReviewPage();
    }
    @FXML private void handleSearchStudents() {
        String id=safe(searchIdField.getText()).toLowerCase(),name=safe(searchNameField.getText()).toLowerCase(),keyword=safe(searchCollegeField.getText()).toLowerCase();
        filteredStudents=students.stream()
                .filter(s->safe(s.getStudentId()).toLowerCase().contains(id))
                .filter(s->safe(s.getName()).toLowerCase().contains(name))
                .filter(s->studentSearchText(s).contains(keyword)).toList();
        currentPage=1;
        refreshStudentPage();
    }
    private String studentSearchText(Student s) {
        return String.join(" ",safe(s.getStudentId()),safe(s.getUID()),safe(s.getName()),safe(s.getGender()),
                safe(s.getGrade()),safe(s.getCollege()),safe(s.getMajor()),safe(s.getClassName()),
                safe(s.getCampus()),safe(s.getStudentStatus()),safe(s.getStudentCategory())).toLowerCase();
    }
    private String studentName(String studentId){
        return students.stream().filter(student->Objects.equals(student.getStudentId(),studentId)).map(Student::getName).findFirst().orElse("-");
    }
    @FXML private void handleResetSearch() {
        searchIdField.clear();
        searchNameField.clear();
        searchCollegeField.clear();
        filteredStudents=new ArrayList<>(students);
        currentPage=1;
        refreshStudentPage();
    }
    @FXML private void handleClearStudentId(){searchIdField.clear();handleSearchStudents();}
    @FXML private void handleClearStudentName(){searchNameField.clear();handleSearchStudents();}
    @FXML private void handleClearStudentKeyword(){searchCollegeField.clear();handleSearchStudents();}
    @FXML private void handleExportSelectedStudents(){
        List<Student> selected=students.stream().filter(student->selectedStudentIds.contains(student.getStudentId())).toList();
        if(selected.isEmpty()){
            AlertUtil.showWarning("请选择学生", "请先勾选需要导出的学生。");return;
        }
        FileChooser chooser=new FileChooser();chooser.setTitle("导出学生信息");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel 工作簿 (*.xlsx)", "*.xlsx"));
        chooser.setInitialFileName("学生信息_"+java.time.LocalDate.now()+".xlsx");
        //添加到默认下载路径
        File downloadsDirectory = new File(
                System.getProperty("user.home"),
                "Downloads"
        );
        if (downloadsDirectory.isDirectory()) {
            chooser.setInitialDirectory(downloadsDirectory);
        }
        File chosen=chooser.showSaveDialog(studentTable.getScene().getWindow());if(chosen==null)return;
        File output=chosen.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")?chosen:new File(chosen.getParentFile(),chosen.getName()+".xlsx");
        exportStudentsButton.setDisable(true);setStatus("正在导出 "+selected.size()+" 名学生的信息...");
        Task<Void> task=new Task<>(){
            @Override protected Void call() throws Exception{StudentExcelExport.export(selected,output);return null;}
        };
        task.setOnSucceeded(event->{if(disposed)return;exportStudentsButton.setDisable(false);setStatus("已导出 "+selected.size()+" 名学生的信息");AlertUtil.showInfo("导出成功", "Excel 已保存至：\n"+output.getAbsolutePath());});
        task.setOnFailed(event->{if(disposed)return;exportStudentsButton.setDisable(false);setStatus("学生信息导出失败");Throwable error=task.getException();AlertUtil.showError("导出失败", error==null?"无法生成 Excel 文件。":"无法生成 Excel 文件：\n"+error.getMessage());});
        Thread worker=new Thread(task,"student-excel-export");worker.setDaemon(true);worker.start();
    }

    @FXML private void handleShowSelectedStudent() {
        Student s=studentTable.getSelectionModel().getSelectedItem();
        if(s==null) {
            setStatus("请先选择学生");
            return;
        }
        showStudentDetails(s);
    }
    private void showStudentDetails(Student s) {
        boolean editableAdmin=isAdmin()&&adminMaintenanceMode;
        syncAdminDetailNavigation();
        setDetailIndexActive(baseIndexButton);
        for(Button button:List.of(editBaseButton,editStudyButton,editAdmissionButton,editContactButton)){
            button.setVisible(!isAdmin()||editableAdmin);button.setManaged(!isAdmin()||editableAdmin);
        }
        adminRecordMaintenancePane.setVisible(editableAdmin);adminRecordMaintenancePane.setManaged(editableAdmin);
        adminReadOnlyInfoPane.setVisible(isAdmin()&&!editableAdmin);adminReadOnlyInfoPane.setManaged(isAdmin()&&!editableAdmin);
        setStatus("正在加载学生完整信息...");
        service.queryStudentOverview(s.getStudentId(),m->runOnPage(()->{
            if(!ok(m)){setStatus(message(m,"学生完整信息加载失败"));return;}
            overview=data(m,"overview",StudentOverviewVO.class);
            render(overview);
            detailScrollPane.setVvalue(0);
            studentTabs.getSelectionModel().select(detailTab);
            setStatus("学生完整信息已加载");
        }));
    }
    @FXML private void handleReturnToStudentList() {
        editing=false;
        studentTabs.getSelectionModel().select(adminListTab);
    }
    private int pageCount() {
        return Math.max(1,(filteredStudents.size()+PAGE_SIZE-1)/PAGE_SIZE);
    }
    //刷新学生页
    private void refreshStudentPage() {
        currentPage=Math.max(1,Math.min(currentPage,pageCount()));
        int from=Math.min((currentPage-1)*PAGE_SIZE,filteredStudents.size()),to=Math.min(from+PAGE_SIZE,filteredStudents.size());
        studentTable.getItems().setAll(filteredStudents.subList(from,to));
        studentTable.refresh();
        pageNumberField.setText(String.valueOf(currentPage));
        pageSummaryLabel.setText("共"+filteredStudents.size()+"条  共"+pageCount()+"页");
        updateStudentSelectionState();
    }
    //刷新单元格
    private void updateStudentSelectionState(){
        if(selectCurrentPageCheckBox!=null){
            boolean any=!studentTable.getItems().isEmpty();
            long selectedInResult=filteredStudents.stream().filter(student->selectedStudentIds.contains(student.getStudentId())).count();
            selectCurrentPageCheckBox.setIndeterminate(selectedInResult>0&&selectedInResult<filteredStudents.size());
            selectCurrentPageCheckBox.setSelected(any&&!filteredStudents.isEmpty()&&selectedInResult==filteredStudents.size());
        }
        if(selectedStudentCountLabel!=null)selectedStudentCountLabel.setText("已选择 "+selectedStudentIds.size()+" 人");
        if(exportStudentsButton!=null)exportStudentsButton.setDisable(selectedStudentIds.isEmpty());
    }
    @FXML private void handleFirstPage() {
        currentPage=1;
        refreshStudentPage();
    }
    @FXML private void handlePreviousPage() {
        if(currentPage>1)currentPage--;
        refreshStudentPage();
    }
    @FXML private void handleNextPage() {
        if(currentPage<pageCount())currentPage++;
        refreshStudentPage();
    }
    @FXML private void handleLastPage() {
        currentPage=pageCount();
        refreshStudentPage();
    }
    @FXML private void handleGoPage() {
        try {
            currentPage=Integer.parseInt(pageNumberField.getText().trim());
        }
        catch(Exception e) {
            setStatus("请输入有效页码");
        }
        refreshStudentPage();
    }
    private void handleSubmitChange() {
        if(overview==null||overview.getStudent()==null)return;
        String validation=validateRequired();
        if(validation!=null){setStatus(validation);return;}
        List<StudentChangeItem> items=changedItems();
        if(items.isEmpty()) {
            setStatus("没有检测到修改内容");
            return;
        }
        if(isAdmin()) {
            Student updated=gson.fromJson(gson.toJson(overview.getStudent()),Student.class);
            try {
                for(Control f:editable)write(updated,(String)f.getUserData(),controlValue(f));
            }
            catch(Exception e) {
                setStatus("输入格式错误："+e.getMessage());
                return;
            }
            service.updateStudentByAdmin(updated,overview.getStudent(),m->runOnPage(()-> {
                setStatus(message(m,"学籍信息已直接更新"));if(ok(m)) {
                    releaseEditLock();editing=false;service.queryStudentOverview(updated.getStudentId(),this::onOverview);
                }
            }
            ));
            return;
        }
        StudentChangeRequest r=new StudentChangeRequest();
        r.setItems(items);
        service.submitChangeRequest(r,m->runOnPage(()-> {
            setStatus(message(m,"申请已提交"));if(ok(m)) {
                releaseEditLock();editing=false;refreshData();
            }
        }
        ));
    }
    private List<StudentChangeItem> changedItems() {
        List<StudentChangeItem> items=new ArrayList<>();
        for(Control f:editable) {
            String n=(String)f.getUserData(),old=editValue(read(overview.getStudent(),n)),now=controlValue(f);
            if(!old.equals(now)) {
                StudentChangeItem i=new StudentChangeItem();
                i.setFieldName(n);
                i.setOldValue(old);
                i.setNewValue(now);
                items.add(i);
            }
        }
        return items;
    }
    private String validateRequired() {
        if(isAdmin())return null;
        Map<String,String> values=new HashMap<>();
        for(Control control:editable)values.put((String)control.getUserData(),controlValue(control));
        for(String name:REQUIRED)if(values.getOrDefault(name,safe(read(overview.getStudent(),name))).isBlank())return title(name)+"为必填项";
        if("是".equals(values.get("leagueMember"))&&values.getOrDefault("leagueJoinDate","").isBlank())return "团员必须填写入团时间";
        if("是".equals(values.get("partyMember"))&&values.getOrDefault("partyJoinDate","").isBlank())return "党员必须填写入党时间";
        return null;
    }
    private void write(Student s,String n,String value)throws Exception {
        String x=Character.toUpperCase(n.charAt(0))+n.substring(1);
        Method setter=Arrays.stream(Student.class.getMethods()).filter(m->m.getName().equals("set"+x)&&m.getParameterCount()==1).findFirst().orElseThrow();
        Class<?> type=setter.getParameterTypes()[0];
        Object converted;
        if(type==String.class)converted=value.trim();
        else if(type==boolean.class||type==Boolean.class) {
            String v=value.trim();
            if(List.of("是","true","1","在校","在籍").contains(v))converted=true;
            else if(List.of("否","false","0","不在校","不在籍").contains(v))converted=false;
            else throw new IllegalArgumentException(title(n)+"应填写是或否");
        }
        else if(type==int.class||type==Integer.class)converted=Integer.parseInt(value.trim());
        else if(type==Date.class)converted=value.isBlank()?null:Date.valueOf(value.trim());
        else converted=gson.fromJson(gson.toJson(value.trim()),type);
        setter.invoke(s,converted);
    }
    private void showReviewDetail(StudentChangeRequest r) {
        if(r==null)return;
        selected=r;
        reviewOverviewPane.setVisible(false);
        reviewOverviewPane.setManaged(false);
        reviewDetailPane.setVisible(true);
        reviewDetailPane.setManaged(true);
        boolean pendingReview=r.getStatus()==StudentChangeStatus.PENDING;
        approveButton.setDisable(!pendingReview);
        rejectButton.setDisable(!pendingReview);
        reviewRemarkArea.setEditable(pendingReview);
        reviewRemarkArea.setText(safe(r.getReviewRemark()));
        reviewBaseStudent=null;
        reviewStudentOverview=null;
        reviewStudentRecords.getChildren().clear();
        for(GridPane grid:List.of(reviewStudentBaseGrid,reviewStudentStudyGrid,reviewStudentAdmissionGrid,reviewStudentContactGrid))grid.getChildren().clear();
        long expected=r.getRequestId();
        service.queryChangeRequest(expected,m->runOnPage(()-> {
            if(selected==null||selected.getRequestId()!=expected)return;StudentChangeRequest detail=ok(m)?data(m,"request",StudentChangeRequest.class):null;if(detail==null) {
                setStatus(message(m,"审核详情加载失败"));handleBackToReviewOverview();return;
            }
            selected=detail;
            renderReviewStudent();
        }
        ));
        service.queryStudentOverview(r.getStudentId(),m->runOnPage(()-> {
            if(selected==null||selected.getRequestId()!=expected)return;
            StudentOverviewVO studentOverview=ok(m)?data(m,"overview",StudentOverviewVO.class):null;
            if(studentOverview!=null&&studentOverview.getStudent()!=null) {
                reviewBaseStudent=studentOverview.getStudent();
                reviewStudentOverview=studentOverview;
                renderReviewStudent();
            }
        }));
    }
    private void renderReviewStudent(){
        if(reviewBaseStudent==null||selected==null||selected.getItems()==null)return;
        Student preview=gson.fromJson(gson.toJson(reviewBaseStudent),Student.class);
        Set<String> changed=new HashSet<>();
        for(StudentChangeItem item:selected.getItems()){
            String field=item.getFieldName();
            if(field==null||field.contains("."))continue;
            try{write(preview,field,safe(item.getNewValue()));changed.add(field);}catch(Exception ignored){}
        }
        fill(reviewStudentBaseGrid,preview,baseFields());
        fill(reviewStudentStudyGrid,preview,studyFields());
        highlightReviewFields(reviewStudentBaseGrid,baseFields(),changed);
        highlightReviewFields(reviewStudentStudyGrid,studyFields(),changed);
        fill(reviewStudentAdmissionGrid,preview,admissionFields());
        fill(reviewStudentContactGrid,preview,contactFields());
        highlightReviewFields(reviewStudentAdmissionGrid,admissionFields(),changed);
        highlightReviewFields(reviewStudentContactGrid,contactFields(),changed);
        reviewStudentRecords.getChildren().clear();
        util.control.InformationReviewRecords.student(reviewStudentRecords,reviewStudentOverview);
        for(StudentChangeItem item:selected.getItems()){
            String field=item.getFieldName();
            if(field==null||!field.contains("."))continue;
            StudentChangeRequest change=new StudentChangeRequest();
            change.setItems(List.of(item));
            Label description=new Label(changeSummary(change));
            description.setWrapText(true);
            description.getStyleClass().add("student-review-changed-value");
            reviewStudentRecords.getChildren().add(description);
        }
    }
    private void highlightReviewFields(GridPane grid,List<String> fields,Set<String> changed){
        for(int i=0;i<fields.size();i++){
            String field=fields.get(i);if(!changed.contains(field))continue;
            int[] position=fieldPosition(grid,field,i);
            for(Node node:grid.getChildren())if(GridPane.getRowIndex(node)==position[0]&&GridPane.getColumnIndex(node)==position[1]*2+1){
                node.getStyleClass().add("student-review-changed-value");break;
            }
        }
    }
    @FXML private void handleBackToReviewOverview() {
        selected=null;
        reviewBaseStudent=null;
        reviewStudentOverview=null;
        reviewStudentRecords.getChildren().clear();
        for(GridPane grid:List.of(reviewStudentBaseGrid,reviewStudentStudyGrid,reviewStudentAdmissionGrid,reviewStudentContactGrid))grid.getChildren().clear();
        reviewRemarkArea.clear();
        approveButton.setDisable(true);
        rejectButton.setDisable(true);
        reviewDetailPane.setVisible(false);
        reviewDetailPane.setManaged(false);
        reviewOverviewPane.setVisible(true);
        reviewOverviewPane.setManaged(true);
    }
    @FXML private void handleApprove() {
        review(StudentChangeStatus.APPROVED);
    }
    @FXML private void handleReject() {
        review(StudentChangeStatus.REJECTED);
    }
    private void review(StudentChangeStatus s) {
        if(selected==null)return;
        if(s==StudentChangeStatus.REJECTED&&safe(reviewRemarkArea.getText()).isBlank()){
            setStatus("不通过时必须填写理由");reviewRemarkArea.requestFocus();return;
        }
        StudentReviewVO v=new StudentReviewVO();
        v.setRequestId(selected.getRequestId());
        v.setReviewResult(s);
        v.setReviewRemark(safe(reviewRemarkArea.getText()));
        long expectedReview=selected.getRequestId();
        service.reviewChangeRequest(v,m->runOnPage(()-> {
            if(selected==null||selected.getRequestId()!=expectedReview)return;
            setStatus(message(m,"审核完成"));if(ok(m)) {
                StudentChangeRequest reviewed=selected;
                reviewed.setStatus(s);
                reviewed.setReviewTime(new java.sql.Timestamp(System.currentTimeMillis()));
                reviewed.setReviewRemark(v.getReviewRemark());
                boolean exists=reviewRequests.stream().anyMatch(request->request.getRequestId()==reviewed.getRequestId());
                if(!exists)reviewRequests.add(reviewed);
                handleBackToReviewOverview();
                showCompletedReviews=true;
                reviewSearchIdField.clear();reviewSearchStudentField.clear();reviewSearchStatusField.clear();
                applyReviewBucket();
            }
        }
        ));
    }
    private void render(StudentOverviewVO v) {
        StudentChangeRequest progress=v==null?null:(v.getPendingRequest()!=null?v.getPendingRequest():v.getLatestRequest());
        reviewStatusPane.showRequest(progress==null?null:progress.getStatus(),progress==null?null:progress.getSubmitTime());
        Student s=displayStudent(v);
        if(s==null) {
            nameLabel.setText("暂无学籍信息");
            return;
        }
        nameLabel.setText(safe(s.getName()));
        String initial=nameLabel.getText().isEmpty()?"学":nameLabel.getText().substring(0,1);
        avatarLabel.setText(initial);
        sidebarAvatarLabel.setText(initial);
        sidebarNameLabel.setText(nameLabel.getText());
        sidebarMajorLabel.setText(show(s.getMajor()));
        studentMetaLabel.setText(String.join(" · ",List.of(safe(s.getStudentId()),safe(s.getGrade()),safe(s.getCollege()),safe(s.getMajor()))));
        categoryValue.setText(show(s.getStudentCategory()));
        statusValue.setText(show(s.getStudentStatus()));
        gradeValue.setText(show(s.getGrade()));
        inSchoolValue.setText(s.isInSchool()?"在校":"不在校");
        boolean pending=v.getPendingRequest()!=null;
        pendingHintLabel.setText(pending?"待管理员审核":"");
        pendingHintLabel.setVisible(pending);pendingHintLabel.setManaged(pending);
        fill(baseInfoGrid,s,baseFields());
        fill(studyInfoGrid,s,studyFields());
        fill(admissionInfoGrid,s,admissionFields());
        fill(contactInfoGrid,s,contactFields());
        renderAwards(v.getAwards());
        renderAids(v.getAids());
        if(maintenanceAwardTable!=null)maintenanceAwardTable.setItems(FXCollections.observableArrayList(v.getAwards()==null?List.of():v.getAwards()));
        if(maintenanceAidTable!=null)maintenanceAidTable.setItems(FXCollections.observableArrayList(v.getAids()==null?List.of():v.getAids()));
        renderExperienceCards(displayExperiences(v));
        renderFamilyCards(displayFamilyMembers(v));
        renderAdminReadOnlyInfo(v);
    }
    private Student displayStudent(StudentOverviewVO value){
        if(value==null||value.getStudent()==null)return null;
        Student result=gson.fromJson(gson.toJson(value.getStudent()),Student.class);
        StudentChangeRequest pending=value.getPendingRequest();
        if(!isAdmin()&&pending!=null&&pending.getItems()!=null){
            for(StudentChangeItem item:pending.getItems()){
                if(item.getFieldName()==null||item.getFieldName().contains("."))continue;
                try{write(result,item.getFieldName(),safe(item.getNewValue()));}catch(Exception ignored){}
            }
        }
        return result;
    }
    private List<StudentExperience> displayExperiences(StudentOverviewVO value){
        List<StudentExperience> result=new ArrayList<>();
        if(value!=null&&value.getExperiences()!=null)for(StudentExperience record:value.getExperiences())result.add(gson.fromJson(gson.toJson(record),StudentExperience.class));
        StudentChangeRequest pending=value==null?null:value.getPendingRequest();
        if(isAdmin()||pending==null||pending.getItems()==null)return result;
        for(StudentChangeItem item:pending.getItems()){
            if(item.getFieldName()==null||!item.getFieldName().startsWith("experience."))continue;
            StudentExperience changed=gson.fromJson(item.getNewValue(),StudentExperience.class);if(changed==null)continue;
            if("experience.add".equals(item.getFieldName()))result.add(changed);
            else if("experience.update".equals(item.getFieldName())){result.removeIf(record->Objects.equals(record.getExperienceId(),changed.getExperienceId()));result.add(changed);}
            else if("experience.delete".equals(item.getFieldName()))result.removeIf(record->Objects.equals(record.getExperienceId(),changed.getExperienceId()));
        }
        return result;
    }
    private List<StudentFamilyMember> displayFamilyMembers(StudentOverviewVO value){
        List<StudentFamilyMember> result=new ArrayList<>();
        if(value!=null&&value.getFamilyMembers()!=null)for(StudentFamilyMember record:value.getFamilyMembers())result.add(gson.fromJson(gson.toJson(record),StudentFamilyMember.class));
        StudentChangeRequest pending=value==null?null:value.getPendingRequest();
        if(isAdmin()||pending==null||pending.getItems()==null)return result;
        for(StudentChangeItem item:pending.getItems()){
            if(item.getFieldName()==null||!item.getFieldName().startsWith("family."))continue;
            StudentFamilyMember changed=gson.fromJson(item.getNewValue(),StudentFamilyMember.class);if(changed==null)continue;
            if("family.add".equals(item.getFieldName()))result.add(changed);
            else if("family.update".equals(item.getFieldName())){result.removeIf(record->Objects.equals(record.getMemberId(),changed.getMemberId()));result.add(changed);}
            else if("family.delete".equals(item.getFieldName()))result.removeIf(record->Objects.equals(record.getMemberId(),changed.getMemberId()));
        }
        return result;
    }
    private void renderAdminReadOnlyInfo(StudentOverviewVO value){
        if(adminReadOnlyInfoPane==null)return;
        renderAwards(value.getAwards(),adminAwardTile);renderAids(value.getAids(),adminAidTile);
        renderAdminExperiences(value.getExperiences());renderAdminFamily(value.getFamilyMembers());
    }
    private void renderAdminExperiences(List<StudentExperience> records){
        adminExperienceCardContainer.getChildren().clear();
        if(records==null||records.isEmpty()){adminExperienceCardContainer.getChildren().add(emptyInfoCard("暂无学习经历"));return;}
        for(StudentExperience record:records){GridPane grid=recordGrid();addRecordField(grid,"开始年月",showMonth(record.getStartDate()),0,0);addRecordField(grid,"结束年月",showMonth(record.getEndDate()),0,1);addRecordField(grid,"学校名称",record.getSchoolName(),1,0);addRecordField(grid,"学习阶段",record.getEducationLevel(),1,1);addWideRecordField(grid,"备注",record.getDescription(),2);adminExperienceCardContainer.getChildren().add(recordCard(grid));}
    }
    private void renderAdminFamily(List<StudentFamilyMember> records){
        adminFamilyCardContainer.getChildren().clear();
        if(records==null||records.isEmpty()){adminFamilyCardContainer.getChildren().add(emptyInfoCard("暂无家庭成员"));return;}
        for(StudentFamilyMember record:records){GridPane grid=recordGrid();addRecordField(grid,"姓名",record.getName(),0,0);addRecordField(grid,"与本人关系",record.getRelationship(),0,1);addRecordField(grid,"出生年月",record.getBirthDate(),1,0);addRecordField(grid,"健康状况",record.getHealthStatus(),1,1);addRecordField(grid,"户口所在地",record.getRegisteredResidence(),2,0);addRecordField(grid,"联系电话",record.getPhone(),2,1);addRecordField(grid,"工作单位",record.getWorkplace(),3,0);addRecordField(grid,"工作单位地址",record.getWorkplaceAddress(),3,1);adminFamilyCardContainer.getChildren().add(recordCard(grid));}
    }
    private void renderExperienceCards(List<StudentExperience> records){
        if(experienceCardContainer==null)return;
        clearExperienceSelection();experienceCardContainer.getChildren().clear();
        if(records==null||records.isEmpty()){experienceCardContainer.getChildren().add(emptyInfoCard("暂无学习经历"));return;}
        for(StudentExperience record:records){
            GridPane grid=recordGrid();
            addRecordField(grid,"开始年月",showMonth(record.getStartDate()),0,0);addRecordField(grid,"结束年月",showMonth(record.getEndDate()),0,1);
            addRecordField(grid,"学校名称",record.getSchoolName(),1,0);addRecordField(grid,"学习阶段",record.getEducationLevel(),1,1);
            addWideRecordField(grid,"备注",record.getDescription(),2);
            VBox card=recordCard(grid);card.setOnMouseClicked(event->selectExperience(record,card));experienceCardContainer.getChildren().add(card);
        }
    }
    private void renderFamilyCards(List<StudentFamilyMember> records){
        if(familyCardContainer==null)return;
        clearFamilySelection();familyCardContainer.getChildren().clear();
        if(records==null||records.isEmpty()){familyCardContainer.getChildren().add(emptyInfoCard("暂无家庭成员"));return;}
        for(StudentFamilyMember record:records){
            GridPane grid=recordGrid();
            addRecordField(grid,"姓名",record.getName(),0,0);addRecordField(grid,"与本人关系",record.getRelationship(),0,1);
            addRecordField(grid,"出生年月",record.getBirthDate(),1,0);addRecordField(grid,"健康状况",record.getHealthStatus(),1,1);
            addRecordField(grid,"户口所在地",record.getRegisteredResidence(),2,0);addRecordField(grid,"联系电话",record.getPhone(),2,1);
            addRecordField(grid,"工作单位",record.getWorkplace(),3,0);addRecordField(grid,"工作单位地址",record.getWorkplaceAddress(),3,1);
            VBox card=recordCard(grid);card.setOnMouseClicked(event->selectFamilyMember(record,card));familyCardContainer.getChildren().add(card);
        }
    }
    private GridPane recordGrid(){
        GridPane grid=new GridPane();grid.getStyleClass().add("student-info-record-grid");grid.setMaxWidth(Double.MAX_VALUE);
        ColumnConstraints key1=new ColumnConstraints(120),value1=new ColumnConstraints(),key2=new ColumnConstraints(120),value2=new ColumnConstraints();
        value1.setHgrow(Priority.ALWAYS);value1.setFillWidth(true);value2.setHgrow(Priority.ALWAYS);value2.setFillWidth(true);
        grid.getColumnConstraints().addAll(key1,value1,key2,value2);return grid;
    }
    private VBox recordCard(GridPane content){VBox card=new VBox(content);card.getStyleClass().add("student-info-record-card");card.setMaxWidth(Double.MAX_VALUE);return card;}
    private Label emptyInfoCard(String text){Label label=new Label(text);label.getStyleClass().add("student-info-empty-card");label.setMaxWidth(Double.MAX_VALUE);return label;}
    private void addRecordField(GridPane grid,String name,Object value,int row,int pair){
        Label key=new Label(name),text=new Label(show(value));styleRecordLabels(key,text);grid.add(key,pair*2,row);grid.add(text,pair*2+1,row);
    }
    private void addWideRecordField(GridPane grid,String name,Object value,int row){
        Label key=new Label(name),text=new Label(show(value));styleRecordLabels(key,text);grid.add(key,0,row);grid.add(text,1,row,3,1);
    }
    private void styleRecordLabels(Label key,Label value){
        key.getStyleClass().add("student-field-key");key.setMaxWidth(Double.MAX_VALUE);
        value.getStyleClass().add("student-field-value");value.setWrapText(true);value.setMaxWidth(Double.MAX_VALUE);GridPane.setHgrow(value,Priority.ALWAYS);
    }
    private void selectExperience(StudentExperience record,Pane card){
        if(selectedExperienceCard!=null)selectedExperienceCard.getStyleClass().remove("student-info-record-card-selected");
        selectedExperience=record;selectedExperienceCard=card;card.getStyleClass().add("student-info-record-card-selected");
        editExperienceButton.setDisable(false);deleteExperienceButton.setDisable(false);
    }
    private void selectFamilyMember(StudentFamilyMember record,Pane card){
        if(selectedFamilyCard!=null)selectedFamilyCard.getStyleClass().remove("student-info-record-card-selected");
        selectedFamilyMember=record;selectedFamilyCard=card;card.getStyleClass().add("student-info-record-card-selected");
        editFamilyButton.setDisable(false);deleteFamilyButton.setDisable(false);
    }
    private void clearExperienceSelection(){selectedExperience=null;selectedExperienceCard=null;if(editExperienceButton!=null)editExperienceButton.setDisable(true);if(deleteExperienceButton!=null)deleteExperienceButton.setDisable(true);}
    private void clearFamilySelection(){selectedFamilyMember=null;selectedFamilyCard=null;if(editFamilyButton!=null)editFamilyButton.setDisable(true);if(deleteFamilyButton!=null)deleteFamilyButton.setDisable(true);}
    private void renderAwards(List<StudentAward> list) {
        renderAwards(list,awardTile);
    }
    private void renderAwards(List<StudentAward> list,TilePane target) {
        target.getChildren().clear();
        if(list==null||list.isEmpty()) {
            target.getChildren().add(emptyRecord("暂无奖励记录"));
            return;
        }
        for(StudentAward a:list)target.getChildren().add(recordCard("🏆",show(a.getAwardName()),"",show(a.getAwardDate()),"award-record-icon"));
    }
    private void renderAids(List<StudentAid> list) {
        renderAids(list,aidTile);
    }
    private void renderAids(List<StudentAid> list,TilePane target) {
        target.getChildren().clear();
        if(list==null||list.isEmpty()) {
            target.getChildren().add(emptyRecord("暂无资助记录"));
            return;
        }
        for(StudentAid a:list) {
            target.getChildren().add(recordCard("◉",show(a.getAidName()),"",show(a.getAidDate()),"aid-record-icon"));
        }
    }
    private HBox recordCard(String icon,String name,String meta,String date,String iconStyle) {
        Label badge=new Label(icon);
        badge.getStyleClass().add(iconStyle);
        Label title=new Label(name);
        title.getStyleClass().add("record-title");
        title.setWrapText(false);
        Label detail=new Label(meta);
        detail.getStyleClass().add("record-meta");
        Label time=new Label(date);
        time.getStyleClass().add("record-date");
        VBox textBox=meta==null||meta.isBlank()?new VBox(3,title,time):new VBox(3,title,detail,time);
        HBox card=new HBox(11,badge,textBox);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("student-record-card");
        return card;
    }
    private HBox emptyRecord(String text) {
        Label l=new Label(text);
        l.getStyleClass().add("record-empty");
        HBox box=new HBox(l);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
    private String joinLine(String a,String b) {
        if("-".equals(a))return b;
        if(b==null||b.isBlank()||"-".equals(b))return a;
        return a+" · "+b;
    }
    @FXML private void handleAddAward(){saveAward(null);}
    @FXML private void handleEditAward(){
        StudentAward selectedAward=maintenanceAwardTable.getSelectionModel().getSelectedItem();
        if(selectedAward==null){setStatus("请先选择奖励记录");return;}saveAward(selectedAward);
    }
    private void saveAward(StudentAward source){
        StudentAward award=source==null?new StudentAward():source;
        LinkedHashMap<String,String> initial=new LinkedHashMap<>();
        initial.put("奖励名称",showForInput(award.getAwardName()));
        initial.put("类型",award.getAwardType()==null?"荣誉":awardTypeText(award.getAwardType()));
        initial.put("奖励级别",showForInput(award.getAwardLevel()));
        initial.put("奖励日期",showForInput(award.getAwardDate()));
        initial.put("颁发单位",showForInput(award.getOrganization()));
        initial.put("奖励说明",showForInput(award.getDescription()));
        Optional<Map<String,String>> result=showRecordDialog("奖励维护",initial);if(result.isEmpty())return;
        Map<String,String> values=result.get();
        try{
            if(values.get("奖励名称").isBlank())throw new IllegalArgumentException("奖励名称不能为空");
            if(values.get("类型").isBlank())throw new IllegalArgumentException("类型不能为空");
            if(values.get("奖励日期").isBlank())throw new IllegalArgumentException("奖励日期不能为空");
            award.setStudentId(overview.getStudent().getStudentId());award.setAwardName(values.get("奖励名称"));
            award.setAwardType(parseAwardType(values.get("类型")));
            award.setAwardLevel(values.get("奖励级别"));String date=values.get("奖励日期");
            award.setAwardDate(Date.valueOf(date));award.setOrganization(values.get("颁发单位"));award.setDescription(values.get("奖励说明"));
        }catch(Exception e){setStatus("奖励信息格式错误："+e.getMessage());return;}
        java.util.function.Consumer<Message> done=m->runOnPage(()->{setStatus(message(m,"奖励维护完成"));if(ok(m))reloadCurrentOverview();});
        if(source==null)service.addAward(award,done);else service.updateAward(award,done);
    }
    //删除奖励
    @FXML private void handleDeleteAward(){
        StudentAward award=maintenanceAwardTable.getSelectionModel().getSelectedItem();
        if(award==null){setStatus("请先选择奖励记录");return;}
        service.deleteAward(award.getAwardId(),m->runOnPage(()->{setStatus(message(m,"奖励已删除"));if(ok(m))reloadCurrentOverview();}));
    }
    //添加奖励
    @FXML private void handleAddAid(){saveAid(null);}
    //编辑资助
    @FXML private void handleEditAid(){
        StudentAid selectedAid=maintenanceAidTable.getSelectionModel().getSelectedItem();
        if(selectedAid==null){setStatus("请先选择资助记录");return;}saveAid(selectedAid);
    }
    //保存资助
    private void saveAid(StudentAid source){
        StudentAid aid=source==null?new StudentAid():source;
        LinkedHashMap<String,String> initial=new LinkedHashMap<>();
        initial.put("资助名称",showForInput(aid.getAidName()));initial.put("资助类型",showForInput(aid.getAidType()));
        initial.put("金额",aid.getAmount()==null?"0":aid.getAmount().toPlainString());initial.put("资助日期",showForInput(aid.getAidDate()));
        initial.put("资助提供方",showForInput(aid.getProvider()));initial.put("状态",aid.getStatus()==null?"待发放":aidStatusText(aid.getStatus()));
        initial.put("资助说明",showForInput(aid.getDescription()));
        Optional<Map<String,String>> result=showRecordDialog("资助维护",initial);if(result.isEmpty())return;
        Map<String,String> values=result.get();
        try{
            if(values.get("资助名称").isBlank())throw new IllegalArgumentException("资助名称不能为空");
            if(values.get("资助类型").isBlank())throw new IllegalArgumentException("资助类型不能为空");
            if(values.get("资助日期").isBlank())throw new IllegalArgumentException("资助日期不能为空");
            aid.setStudentId(overview.getStudent().getStudentId());aid.setAidName(values.get("资助名称"));aid.setAidType(values.get("资助类型"));
            aid.setAmount(new java.math.BigDecimal(values.get("金额")));String date=values.get("资助日期");
            aid.setAidDate(Date.valueOf(date));aid.setProvider(values.get("资助提供方"));
            aid.setStatus(parseAidStatus(values.get("状态")));aid.setDescription(values.get("资助说明"));
        }catch(Exception e){setStatus("资助信息格式错误："+e.getMessage());return;}
        java.util.function.Consumer<Message> done=m->runOnPage(()->{setStatus(message(m,"资助维护完成"));if(ok(m))reloadCurrentOverview();});
        if(source==null)service.addAid(aid,done);else service.updateAid(aid,done);
    }
    @FXML private void handleDeleteAid(){
        StudentAid aid=maintenanceAidTable.getSelectionModel().getSelectedItem();
        if(aid==null){setStatus("请先选择资助记录");return;}
        service.deleteAid(aid.getAidId(),m->runOnPage(()->{setStatus(message(m,"资助已删除"));if(ok(m))reloadCurrentOverview();}));
    }
    private Optional<Map<String,String>> showRecordDialog(String title,LinkedHashMap<String,String> initial){
        Dialog<Map<String,String>> dialog=new Dialog<>();dialog.setTitle(title);dialog.setHeaderText("请在同一表单中填写全部信息");
        GridPane grid=new GridPane();grid.setHgap(12);grid.setVgap(10);grid.setPadding(new Insets(8,12,8,12));
        LinkedHashMap<String,Node> fields=new LinkedHashMap<>();
        LinkedHashMap<String,java.util.function.Supplier<String>> readers=new LinkedHashMap<>();int row=0;
        for(Map.Entry<String,String> entry:initial.entrySet()){
            String key=entry.getKey();Node field;
            if(key.startsWith("开始年月")||key.startsWith("结束年月")){
                ComboBox<Integer> year=new ComboBox<>(),month=new ComboBox<>();
                int currentYear=java.time.Year.now().getValue();
                for(int value=currentYear+10;value>=1900;value--)year.getItems().add(value);
                for(int value=1;value<=12;value++)month.getItems().add(value);
                String initialValue=entry.getValue();
                if(!initialValue.isBlank())try{String[] parts=initialValue.split("-");year.setValue(Integer.parseInt(parts[0]));month.setValue(Integer.parseInt(parts[1]));}catch(Exception ignored){}
                year.setPromptText("年份");month.setPromptText("月份");year.setPrefWidth(210);month.setPrefWidth(135);
                field=new HBox(10,year,month);
                readers.put(key,()->year.getValue()==null||month.getValue()==null?"":String.format("%04d-%02d",year.getValue(),month.getValue()));
            }else if(key.startsWith("出生年月")){
                DatePicker picker=new DatePicker();
                if(!entry.getValue().isBlank())try{picker.setValue(java.time.LocalDate.parse(entry.getValue()));}catch(Exception ignored){}
                picker.setPrefWidth(360);field=picker;readers.put(key,()->picker.getValue()==null?"":picker.getValue().toString());
            }else if("奖励日期".equals(key)||"资助日期".equals(key)){
                DatePicker picker=new DatePicker();
                if(!entry.getValue().isBlank())try{picker.setValue(java.time.LocalDate.parse(entry.getValue()));}catch(Exception ignored){}
                picker.setPrefWidth(360);field=picker;readers.put(key,()->picker.getValue()==null?"":picker.getValue().toString());
            }else if("类型".equals(key)&&title.contains("奖励")){
                ComboBox<String> combo=new ComboBox<>(FXCollections.observableArrayList("奖学金","荣誉","竞赛","科研","实践","其他"));
                combo.setValue(entry.getValue().isBlank()?null:entry.getValue());combo.setPrefWidth(360);field=combo;readers.put(key,()->combo.getValue()==null?"":combo.getValue());
            }else if("资助类型".equals(key)&&title.contains("资助")){
                ComboBox<String> combo=new ComboBox<>(FXCollections.observableArrayList("助学金","助学贷款","勤工助学","困难补助","学费减免","其他"));
                combo.setValue(entry.getValue().isBlank()?null:entry.getValue());combo.setPrefWidth(360);field=combo;readers.put(key,()->combo.getValue()==null?"":combo.getValue());
            }else if("状态".equals(key)&&title.contains("资助")){
                ComboBox<String> combo=new ComboBox<>(FXCollections.observableArrayList("待发放","已发放","已取消"));
                combo.setValue(entry.getValue().isBlank()?null:entry.getValue());combo.setPrefWidth(360);field=combo;readers.put(key,()->combo.getValue()==null?"":combo.getValue());
            }else if("学习阶段".equals(key)){
                ComboBox<String> combo=new ComboBox<>(FXCollections.observableArrayList("小学","初中","高中","大学","研究生","博士"));
                combo.setValue(entry.getValue().isBlank()?null:entry.getValue());combo.setPrefWidth(360);field=combo;readers.put(key,()->combo.getValue()==null?"":combo.getValue());
            }else if("与本人关系".equals(key)){
                ComboBox<String> combo=new ComboBox<>(FXCollections.observableArrayList("父亲","母亲","配偶","子女","兄弟","姐妹","祖父","祖母","外祖父","外祖母","其他"));
                combo.setValue(entry.getValue().isBlank()?null:entry.getValue());combo.setPrefWidth(360);field=combo;readers.put(key,()->combo.getValue()==null?"":combo.getValue());
            }else{
                TextField text=new TextField(entry.getValue());text.setPrefWidth(360);field=text;readers.put(key,()->text.getText().trim());
            }
            fields.put(key,field);
            Label label=new Label(key);label.setWrapText(true);label.setMaxWidth(250);
            if((title.contains("学习经历")&&Set.of("开始年月","结束年月","学校名称","学习阶段").stream().anyMatch(key::startsWith))
                    ||(title.contains("家庭")&&Set.of("姓名","与本人关系","出生年月","户口所在地","工作单位","联系电话").contains(key))
                    ||(title.contains("奖励")&&Set.of("奖励名称","类型","奖励日期").contains(key))
                    ||(title.contains("资助")&&Set.of("资助名称","资助类型","资助日期").contains(key))){
                Label star=new Label("*");star.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                label.setGraphic(star);label.setContentDisplay(ContentDisplay.RIGHT);
            }
            grid.add(label,0,row);grid.add(field,1,row++);
        }
        dialog.getDialogPane().setContent(grid);dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        dialog.setResultConverter(button->{
            if(button!=ButtonType.OK)return null;Map<String,String> values=new LinkedHashMap<>();
            readers.forEach((key,reader)->values.put(key,reader.get()));return values;
        });
        return dialog.showAndWait();
    }
    @FXML private void handleAddExperience(){LinkedHashMap<String,String> f=new LinkedHashMap<>();f.put("开始年月","");f.put("结束年月","");f.put("学校名称","");f.put("学习阶段","");f.put("备注","");showRecordDialog("新增主要学习经历",f).ifPresent(v->{try{validateExperienceForm(v);StudentExperience x=new StudentExperience();x.setStartDate(monthDate(v.get("开始年月")));x.setEndDate(monthDate(v.get("结束年月")));x.setSchoolName(v.get("学校名称"));x.setEducationLevel(v.get("学习阶段"));x.setDescription(v.get("备注"));service.addExperience(x,m->runOnPage(()->{setStatus(message(m,"学习经历已添加"));if(ok(m))refreshData();}));}catch(Exception e){setStatus("学习经历格式错误："+e.getMessage());}});}
    @FXML private void handleAddFamilyMember(){LinkedHashMap<String,String> f=new LinkedHashMap<>();f.put("姓名","");f.put("与本人关系","");f.put("出生年月","");f.put("户口所在地","");f.put("工作单位","");f.put("工作单位地址","");f.put("健康状况","");f.put("联系电话","");showRecordDialog("新增家庭主要关系成员",f).ifPresent(v->{try{validateFamilyForm(v);StudentFamilyMember x=new StudentFamilyMember();x.setName(v.get("姓名"));x.setRelationship(v.get("与本人关系"));String birth=v.get("出生年月");x.setBirthDate(birth.isBlank()?null:Date.valueOf(birth));x.setRegisteredResidence(v.get("户口所在地"));x.setWorkplace(v.get("工作单位"));x.setWorkplaceAddress(v.get("工作单位地址"));x.setHealthStatus(v.get("健康状况"));x.setPhone(v.get("联系电话"));service.addFamilyMember(x,m->runOnPage(()->{setStatus(message(m,"家庭成员已添加"));if(ok(m))refreshData();}));}catch(Exception e){setStatus("家庭成员信息格式错误："+e.getMessage());}});}
    @FXML private void handleEditExperience(){StudentExperience x=selectedExperience;if(x==null){setStatus("请先选择学习经历");return;}LinkedHashMap<String,String> f=new LinkedHashMap<>();f.put("开始年月",showMonth(x.getStartDate()));f.put("结束年月",showMonth(x.getEndDate()));f.put("学校名称",showForInput(x.getSchoolName()));f.put("学习阶段",showForInput(x.getEducationLevel()));f.put("备注",showForInput(x.getDescription()));showRecordDialog("编辑主要学习经历",f).ifPresent(v->{try{validateExperienceForm(v);StudentExperience changed=gson.fromJson(gson.toJson(x),StudentExperience.class);changed.setStartDate(monthDate(v.get("开始年月")));changed.setEndDate(monthDate(v.get("结束年月")));changed.setSchoolName(v.get("学校名称"));changed.setEducationLevel(v.get("学习阶段"));changed.setDescription(v.get("备注"));service.updateExperience(changed,m->runOnPage(()->{setStatus(message(m,"学习经历已更新"));if(ok(m))refreshData();}));}catch(Exception e){setStatus("学习经历格式错误："+e.getMessage());}});}
    @FXML private void handleDeleteExperience(){StudentExperience x=selectedExperience;if(x==null){setStatus("请先选择学习经历");return;}if(!confirmDelete("确定删除选中的学习经历吗？"))return;service.deleteExperience(x.getExperienceId(),m->runOnPage(()->{setStatus(message(m,"学习经历已删除"));if(ok(m))refreshData();}));}
    @FXML private void handleEditFamilyMember(){StudentFamilyMember x=selectedFamilyMember;if(x==null){setStatus("请先选择家庭成员");return;}LinkedHashMap<String,String> f=new LinkedHashMap<>();f.put("姓名",showForInput(x.getName()));f.put("与本人关系",showForInput(x.getRelationship()));f.put("出生年月",showForInput(x.getBirthDate()));f.put("户口所在地",showForInput(x.getRegisteredResidence()));f.put("工作单位",showForInput(x.getWorkplace()));f.put("工作单位地址",showForInput(x.getWorkplaceAddress()));f.put("健康状况",showForInput(x.getHealthStatus()));f.put("联系电话",showForInput(x.getPhone()));showRecordDialog("编辑家庭主要关系成员",f).ifPresent(v->{try{validateFamilyForm(v);StudentFamilyMember changed=gson.fromJson(gson.toJson(x),StudentFamilyMember.class);changed.setName(v.get("姓名"));changed.setRelationship(v.get("与本人关系"));String birth=v.get("出生年月");changed.setBirthDate(birth.isBlank()?null:Date.valueOf(birth));changed.setRegisteredResidence(v.get("户口所在地"));changed.setWorkplace(v.get("工作单位"));changed.setWorkplaceAddress(v.get("工作单位地址"));changed.setHealthStatus(v.get("健康状况"));changed.setPhone(v.get("联系电话"));service.updateFamilyMember(changed,m->runOnPage(()->{setStatus(message(m,"家庭成员已更新"));if(ok(m))refreshData();}));}catch(Exception e){setStatus("家庭成员信息格式错误："+e.getMessage());}});}
    @FXML private void handleDeleteFamilyMember(){StudentFamilyMember x=selectedFamilyMember;if(x==null){setStatus("请先选择家庭成员");return;}if(!confirmDelete("确定删除选中的家庭成员吗？"))return;service.deleteFamilyMember(x.getMemberId(),m->runOnPage(()->{setStatus(message(m,"家庭成员已删除"));if(ok(m))refreshData();}));}
    private boolean confirmDelete(String text){Alert alert=new Alert(Alert.AlertType.CONFIRMATION,text,ButtonType.OK,ButtonType.CANCEL);alert.setTitle("删除确认");alert.setHeaderText(null);return alert.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;}
    private void validateExperienceForm(Map<String,String> values){
        for(String field:List.of("开始年月","结束年月","学校名称","学习阶段"))if(values.getOrDefault(field,"").isBlank())throw new IllegalArgumentException(field+"不能为空");
        Date start=monthDate(values.get("开始年月")),end=monthDate(values.get("结束年月"));
        if(end.before(start))throw new IllegalArgumentException("结束日期不能早于开始日期");
    }
    private void validateFamilyForm(Map<String,String> values){for(String field:List.of("姓名","与本人关系","出生年月","户口所在地","工作单位","联系电话"))if(values.getOrDefault(field,"").isBlank())throw new IllegalArgumentException(field+"不能为空");}
    private Date monthDate(String value){return Date.valueOf(value+"-01");}
    private String showMonth(Date value){return value==null?"":value.toString().substring(0,7);}
    private String showForInput(Object value){return value==null?"":String.valueOf(value);}
    private void reloadCurrentOverview(){
        if(overview!=null&&overview.getStudent()!=null)service.queryStudentOverview(overview.getStudent().getStudentId(),this::onOverview);
    }
    private void fill(GridPane g,Student s,List<String> names) {
        prepareSixColumns(g);
        g.getChildren().clear();
        for(int i=0;i<names.size();i++) {
            String n=names.get(i);
            int[] p=fieldPosition(g,n,i);
            Label k=new Label(title(n)),v=new Label(show(read(s,n)));
            styleFieldKey(k);
            v.getStyleClass().add("student-field-value");
            v.setAlignment(Pos.CENTER_LEFT);
            v.setWrapText(true);
            v.setMinWidth(0);
            v.setMaxWidth(Double.MAX_VALUE);
            v.setTextOverrun(OverrunStyle.ELLIPSIS);
            v.setTooltip(new Tooltip(v.getText()));
            g.add(k,p[1]*2,p[0]);
            g.add(v,p[1]*2+1,p[0]);
            if(fieldSpansRemainder(g,n))GridPane.setColumnSpan(v,5-p[1]*2);
        }
    }
    private void styleFieldKey(Label key) {
        key.getStyleClass().add("student-field-key");
        key.setAlignment(Pos.CENTER_LEFT);
        key.setWrapText(false);
        key.setMinWidth(136);
        key.setPrefWidth(136);
        key.setMaxWidth(136);
        key.setTextOverrun(OverrunStyle.ELLIPSIS);
        key.setTooltip(new Tooltip(key.getText()));
    }
    private void prepareSixColumns(GridPane g) {
        g.setAlignment(Pos.CENTER_LEFT);
        g.setHgap(12);
        g.setVgap(9);
        g.setMaxWidth(Double.MAX_VALUE);
        if(g.getColumnConstraints().size()==6)return;
        g.getColumnConstraints().clear();
        for(int i=0;i<6;i++) {
            ColumnConstraints c=new ColumnConstraints();
            if(i%2==0) {
                c.setMinWidth(136);
                c.setPrefWidth(136);
                c.setMaxWidth(136);
                c.setHgrow(Priority.NEVER);
            }
            else {
                c.setMinWidth(0);
                c.setPrefWidth(150);
                c.setMaxWidth(Double.MAX_VALUE);
                c.setHgrow(Priority.ALWAYS);
            }
            c.setHalignment(javafx.geometry.HPos.LEFT);
            c.setFillWidth(true);
            g.getColumnConstraints().add(c);
        }
    }
    private int[] fieldPosition(GridPane g,String n,int index) {
        if(g==baseInfoGrid) {
            return switch(n) {
                case"UID"->new int[] {
                    0,0
                }
                ;
                case"studentId"->new int[] {
                    0,1
                }
                ;
                case"name"->new int[] {
                    0,2
                }
                ;
                case"politicalStatus"->new int[] {
                    1,0
                }
                ;
                case"nationality"->new int[] {
                    1,1
                }
                ;
                case"gender"->new int[] {
                    1,2
                }
                ;
                case"idType"->new int[] {
                    2,0
                }
                ;
                case"idNumber"->new int[] {
                    2,1
                }
                ;
                case"idIssueDate"->new int[] {
                    2,2
                }
                ;
                case"birthDate"->new int[] {
                    3,0
                }
                ;
                case"nativePlace"->new int[] {
                    3,1
                }
                ;
                case"householdType"->new int[] {
                    3,2
                }
                ;
                case"birthPlace"->new int[] {
                    4,0
                }
                ;
                case"sourcePlace"->new int[] {
                    4,1
                }
                ;
                case"registeredResidence"->new int[] {
                    5,0
                }
                ;
                case"leagueMember"->new int[] {
                    6,0
                }
                ;
                case"leagueJoinDate"->new int[] {
                    6,1
                }
                ;
                case"partyMember"->new int[] {
                    6,2
                }
                ;
                case"partyJoinDate"->new int[] {
                    7,0
                }
                ;
                case"healthStatus"->new int[] {
                    7,1
                }
                ;
                default->new int[] {
                    index/3,index%3
                }
                ;
            }
            ;
        }
        if(g==contactInfoGrid) {
            return switch(n) {
                case"telephone"->new int[] {
                    0,0
                }
                ;
                case"mobile"->new int[] {
                    0,1
                }
                ;
                case"email"->new int[] {
                    0,2
                }
                ;
                case"qq"->new int[] {
                    1,0
                }
                ;
                case"wechat"->new int[] {
                    1,1
                }
                ;
                case"campusAddress"->new int[] {
                    2,0
                }
                ;
                case"emergencyContact"->new int[] {
                    3,0
                }
                ;
                case"emergencyPhone"->new int[] {
                    3,1
                }
                ;
                default->new int[] {
                    index/3,index%3
                }
                ;
            }
            ;
        }
        return new int[] {
            index/3,index%3
        }
        ;
    }
    private boolean fieldSpansRemainder(GridPane g,String n) {
        return(g==baseInfoGrid&&"registeredResidence".equals(n))||(g==contactInfoGrid&&"campusAddress".equals(n));
    }
    private Object read(Student s,String n) {
        try {
            String x=Character.toUpperCase(n.charAt(0))+n.substring(1);
            try {
                return Student.class.getMethod("get"+x).invoke(s);
            }
            catch(NoSuchMethodException e) {
                return Student.class.getMethod("is"+x).invoke(s);
            }
        }
        catch(Exception e) {
            return null;
        }
    }
    private String title(String n) {
        return switch (n) {
            case "UID" -> "用户名";
            case "studentId" -> "学号";
            case "name" -> "姓名";
            case "gender" -> "性别";
            case "politicalStatus" -> "政治面貌";
            case "nationality" -> "民族";
            case "idType" -> "身份证件类型";
            case "idNumber" -> "身份证号";
            case "idIssueDate" -> "身份证签发日期";
            case "birthDate" -> "出生日期";
            case "nativePlace" -> "籍贯";
            case "householdType" -> "户口性质";
            case "birthPlace" -> "出生地";
            case "sourcePlace" -> "生源地";
            case "registeredResidence" -> "户口所在地";
            case "leagueMember" -> "是否团员";
            case "leagueJoinDate" -> "入团时间";
            case "partyMember" -> "是否党员";
            case "partyJoinDate" -> "入党时间";
            case "healthStatus" -> "健康状况";
            case "studentCategory" -> "学生类别";
            case "registered" -> "是否在籍";
            case "inSchool" -> "是否在校";
            case "studentStatus" -> "学籍状态";
            case "campus" -> "校区";
            case "grade" -> "年级";
            case "college" -> "院系";
            case "major" -> "专业";
            case "className" -> "班级";
            case "educationLevel" -> "培养层次";
            case "trainingMode" -> "培养方式";
            case "schoolingLength" -> "学制";
            case "counselorName" -> "辅导员";
            case "counselorPhone" -> "辅导员联系方式";
            case "candidateCategory" -> "考生类别";
            case "admissionDate" -> "入学日期";
            case "admissionMethod" -> "入学方式";
            case "graduationSchool" -> "毕业中学";
            case "middleSchoolClass" -> "中学所在班级";
            case "middleSchoolTeacher" -> "中学班主任姓名";
            case "telephone" -> "联系电话";
            case "mobile" -> "手机号";
            case "email" -> "电子邮箱";
            case "qq" -> "QQ号";
            case "wechat" -> "微信号";
            case "campusAddress" -> "在校地址";
            case "emergencyContact" -> "紧急联系人";
            case "emergencyPhone" -> "紧急联系人联系方式";
            case "experience.add" -> "新增主要学习经历";
            case "experience.update" -> "修改主要学习经历";
            case "experience.delete" -> "删除主要学习经历";
            case "family.add" -> "新增家庭主要关系成员";
            case "family.update" -> "修改家庭主要关系成员";
            case "family.delete" -> "删除家庭主要关系成员";
            default -> n;
        };
    }
    private String changeSummary(StudentChangeRequest request){
        if(request==null||request.getItems()==null||request.getItems().isEmpty())return "无修改内容";
        StringBuilder out=new StringBuilder();
        for(StudentChangeItem item:request.getItems()){
            if(out.length()>0)out.append("\n\n");
            out.append(title(item.getFieldName())).append("\n");
            if(item.getFieldName().startsWith("experience.")){
                StudentExperience x=gson.fromJson(item.getNewValue(),StudentExperience.class);
                out.append("时间：").append(showMonth(x.getStartDate())).append(" 至 ").append(showMonth(x.getEndDate()))
                   .append("\n学校：").append(show(x.getSchoolName())).append("\n学习阶段：").append(show(x.getEducationLevel()))
                   .append("\n备注：").append(show(x.getDescription()));
            }else if(item.getFieldName().startsWith("family.")){
                StudentFamilyMember x=gson.fromJson(item.getNewValue(),StudentFamilyMember.class);
                out.append("姓名：").append(show(x.getName())).append("；关系：").append(show(x.getRelationship()))
                   .append("\n出生年月：").append(show(x.getBirthDate())).append("；健康状况：").append(show(x.getHealthStatus()))
                   .append("\n户口所在地：").append(show(x.getRegisteredResidence())).append("\n工作单位：").append(show(x.getWorkplace()))
                   .append("；单位地址：").append(show(x.getWorkplaceAddress())).append("\n联系电话：").append(show(x.getPhone()));
            }else out.append("原内容：").append(show(item.getOldValue())).append("\n新内容：").append(show(item.getNewValue()));
        }
        return out.toString();
    }
    private boolean ok(Message m) {
        return m!=null&&m.getCode()==MessageCode.SUCCESS;
    }
    private String message(Message m,String f) {
        return m!=null&&m.getMessage()!=null&&!m.getMessage().isBlank()?m.getMessage():f;
    }
    private SimpleStringProperty text(Object v) {
        return new SimpleStringProperty(show(v));
    }
    private String show(Object v) {
        if(v==null||String.valueOf(v).isBlank())return "-";
        if(v instanceof Boolean)return(Boolean)v?"是":"否";
        return String.valueOf(v);
    }
    private String safe(Object v) {
        return v==null?"":String.valueOf(v).trim();
    }
    private String editValue(Object v) {
        return v instanceof Boolean ? ((Boolean)v?"是":"否") : safe(v);
    }
    private String awardTypeText(StudentAwardType type){
        if(type==null)return "-";
        return switch(type){case SCHOLARSHIP->"奖学金";case HONOR->"荣誉";case COMPETITION->"竞赛";case RESEARCH->"科研";case PRACTICE->"实践";case OTHER->"其他";};
    }
    private StudentAwardType parseAwardType(String text){
        return switch(safe(text)){case "奖学金"->StudentAwardType.SCHOLARSHIP;case "荣誉"->StudentAwardType.HONOR;case "竞赛"->StudentAwardType.COMPETITION;case "科研"->StudentAwardType.RESEARCH;case "实践"->StudentAwardType.PRACTICE;case "其他"->StudentAwardType.OTHER;default->StudentAwardType.valueOf(safe(text).toUpperCase());};
    }
    private String aidTypeText(String type){return show(type);}
    private String aidStatusText(StudentAidStatus status){
        if(status==null)return "-";
        return switch(status){case PENDING->"待发放";case ISSUED->"已发放";case CANCELLED->"已取消";};
    }
    private StudentAidStatus parseAidStatus(String text){
        return switch(safe(text)){case "待发放"->StudentAidStatus.PENDING;case "已发放"->StudentAidStatus.ISSUED;case "已取消"->StudentAidStatus.CANCELLED;default->StudentAidStatus.valueOf(safe(text).toUpperCase());};
    }
    private String status(StudentChangeStatus s) {
        return s==null?"-":switch(s) {
            case PENDING->"待审核";
            case APPROVED->"已通过";
            case REJECTED->"未通过";
            case CANCELLED->"已取消";
        }
        ;
    }
    private void setStatus(String s) {
        if(statusBarLabel!=null)statusBarLabel.setText(s);
    }
}
