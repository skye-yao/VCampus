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
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import service.StudentClientService;
import session.ClientSession;
import protocol.*;
import vo.*;
import java.lang.reflect.*;
import java.sql.Date;
import java.util.*;
public class StudentController {
    @FXML private Label titleLabel,statusBarLabel,avatarLabel,sidebarAvatarLabel,sidebarNameLabel,sidebarMajorLabel,nameLabel,studentMetaLabel,pendingHintLabel,categoryValue,statusValue,gradeValue,inSchoolValue;
    @FXML private TabPane studentTabs;
    @FXML private Tab overviewTab,detailTab,adminListTab,reviewTab;
    @FXML private ScrollPane detailScrollPane;
    @FXML private GridPane baseInfoGrid,studyInfoGrid,admissionInfoGrid,contactInfoGrid;
    @FXML private GridPane reviewStudentBaseGrid,reviewStudentStudyGrid;
    @FXML private VBox studentDetailSidebar,adminDetailSidebar,reviewOverviewPane,reviewDetailPane;
    @FXML private Button detailReturnButton,editBaseButton,editStudyButton,editAdmissionButton,editContactButton,managementNavButton,maintenanceNavButton;
    @FXML private VBox adminRecordMaintenancePane;
    @FXML private TextArea reviewRemarkArea;
    @FXML private TextField searchIdField,searchNameField,searchCollegeField,pageNumberField,reviewSearchIdField,reviewSearchStudentField,reviewSearchStatusField,reviewPageNumberField;
    @FXML private Label pageSummaryLabel,reviewPageSummaryLabel,adminWorkspaceTitle;
    @FXML private TilePane awardTile,aidTile;
    @FXML private TableView<StudentChangeRequest> reviewTable;
    @FXML private TableColumn<StudentChangeRequest,String> reviewNameCol,reviewStudentCol,reviewTimeCol,reviewProcessedTimeCol,reviewStatusCol;
    @FXML private TableColumn<StudentChangeRequest,Void> reviewActionCol;
    @FXML private TableView<Student> studentTable;
    @FXML private TableColumn<Student,String> stuIdCol,stuNameCol,stuGenderCol,stuGradeCol,stuCollegeCol,stuMajorCol,stuStatusCol;
    @FXML private TableColumn<Student,Void> stuActionCol;
    @FXML private TableView<StudentAward> maintenanceAwardTable;
    @FXML private TableColumn<StudentAward,String> maintenanceAwardIdCol,maintenanceAwardStudentCol,maintenanceAwardNameCol,maintenanceAwardTypeCol,maintenanceAwardLevelCol,maintenanceAwardDateCol,maintenanceAwardOrganizationCol,maintenanceAwardDescriptionCol;
    @FXML private TableView<StudentAid> maintenanceAidTable;
    @FXML private TableColumn<StudentAid,String> maintenanceAidIdCol,maintenanceAidStudentCol,maintenanceAidNameCol,maintenanceAidTypeCol,maintenanceAidAmountCol,maintenanceAidDateCol,maintenanceAidProviderCol,maintenanceAidStatusCol,maintenanceAidDescriptionCol;
    @FXML private Button approveButton,rejectButton;
    @FXML private ToggleButton unfinishedReviewButton,completedReviewButton;
    private static final int PAGE_SIZE=12;
    private final StudentClientService service=new StudentClientService(network.SocketClient.getInstance());
    private final Gson gson=new Gson();
    private static final Set<String> STUDENT_EDITABLE=Set.of(
            "politicalStatus","nationality","gender","idType","idNumber","idIssueDate","birthDate",
            "nativePlace","householdType","birthPlace","sourcePlace","registeredResidence","leagueMember",
            "leagueJoinDate","partyMember","partyJoinDate","healthStatus","campus","trainingMode",
            "schoolingLength","counselorName","counselorPhone","candidateCategory","admissionDate",
            "admissionMethod","graduationSchool","middleSchoolClass","middleSchoolTeacher","telephone",
            "mobile","email","qq","wechat","campusAddress","emergencyContact","emergencyPhone");
    private static final Set<String> REQUIRED=Set.of(
            "politicalStatus","nationality","gender","idType","idNumber","idIssueDate","birthDate",
            "nativePlace","householdType","birthPlace","sourcePlace","registeredResidence","leagueMember",
            "healthStatus","graduationSchool","telephone","mobile","emergencyContact","emergencyPhone");
    private static final Set<String> DATE_FIELDS=Set.of("idIssueDate","birthDate","leagueJoinDate","partyJoinDate","admissionDate");
    private final List<Control> editable=new ArrayList<>();
    private boolean editing;
    private StudentOverviewVO overview;
    private List<Student> students=new ArrayList<>(),filteredStudents=new ArrayList<>();
    private int currentPage=1;
    private List<StudentChangeRequest> reviewRequests=new ArrayList<>(),filteredReviewRequests=new ArrayList<>();
    private int reviewCurrentPage=1;
    private boolean showCompletedReviews;
    private boolean adminMaintenanceMode;
    private StudentChangeRequest selected;
    @FXML public void initialize() {
        setupTables();
        setupRole();
        refreshData();
    }
    private void refreshData() {
        setStatus("正在加载学籍信息...");
        if(isAdmin())loadAdmin();
        else service.queryOverview(this::onOverview);
    }
    @FXML private void handleBack() {
        if(editing)releaseEditLock();
        ClientMain.switchScene("/resources/fxml/MainView.fxml");
    }
    @FXML private void handleRefresh() {
        if(editing){releaseEditLock();editing=false;}
        refreshData();
    }
    @FXML private void handleViewDetails() {
        studentTabs.getSelectionModel().select(detailTab);
    }
    @FXML private void handleShowOverview() {
        studentTabs.getSelectionModel().select(overviewTab);
    }
    @FXML private void handleShowReview() {
        if(isAdmin())studentTabs.getSelectionModel().select(reviewTab);
    }
    @FXML private void handleShowStudentManagement(){adminMaintenanceMode=false;applyAdminMode();}
    @FXML private void handleShowStudentMaintenance(){adminMaintenanceMode=true;applyAdminMode();}
    private void applyAdminMode(){
        if(!isAdmin())return;
        studentTabs.getSelectionModel().select(adminListTab);
        titleLabel.setText(adminMaintenanceMode?"学生信息维护":"学生信息管理");
        adminWorkspaceTitle.setText(adminMaintenanceMode?"学生信息维护":"学生信息管理");
        managementNavButton.getStyleClass().removeAll("student-side-button","student-side-button-active");
        maintenanceNavButton.getStyleClass().removeAll("student-side-button","student-side-button-active");
        managementNavButton.getStyleClass().add(adminMaintenanceMode?"student-side-button":"student-side-button-active");
        maintenanceNavButton.getStyleClass().add(adminMaintenanceMode?"student-side-button-active":"student-side-button");
        stuActionCol.setText("操作");studentTable.refresh();
    }
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
    private void beginGlobalEdit() {
        if(overview==null||overview.getStudent()==null)return;
        if(!isAdmin()&&overview.getPendingRequest()!=null) {
            setStatus("当前修改申请正在审核中，审核完成后才能再次修改");
            return;
        }
        if(editing)return;
        String studentId=overview.getStudent().getStudentId();
        service.beginEdit(studentId,m->Platform.runLater(()-> {
            if(!ok(m)){setStatus(message(m,"暂时无法进入编辑状态"));return;}
            openGlobalEditor();
        }));
    }
    private void openGlobalEditor() {
        editable.clear();editing=true;
        fillEditable(baseInfoGrid,baseFields());
        fillEditable(studyInfoGrid,studyFields());
        fillEditable(admissionInfoGrid,admissionFields());
        fillEditable(contactInfoGrid,contactFields());
        Button cancel=new Button("取消");
        cancel.getStyleClass().add("btn-secondary");
        cancel.setOnAction(e->cancelGlobalEdit());
        Button submit=new Button(isAdmin()?"提交":"提交修改申请");
        submit.getStyleClass().add("btn-primary");
        submit.setOnAction(e->handleSubmitChange());
        HBox actions=new HBox(8,cancel,submit);
        actions.setAlignment(Pos.CENTER_RIGHT);
        contactInfoGrid.add(actions,0,4,6,1);
        setStatus(isAdmin()?"全部信息已进入编辑状态，提交后直接生效":"全部信息已进入编辑状态，提交后等待审核");
    }
    private void fillEditable(GridPane grid,List<String> names) {
        prepareSixColumns(grid);
        grid.getChildren().clear();
        Student s=overview.getStudent();
        for(int i=0;i<names.size();i++) {
            String n=names.get(i);
            int[] p=fieldPosition(grid,n,i);
            Label key=new Label(title(n));
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
    private void cancelGlobalEdit() {
        releaseEditLock();editing=false;
        render(overview);
        setStatus("已取消编辑");
    }
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
            control=new ComboBox<>(FXCollections.observableArrayList("城镇户口","农村居民户口","集体户口"));
            ((ComboBox<String>)control).setValue(initial);
        } else if(Set.of("leagueMember","partyMember","registered","inSchool").contains(name)) {
            control=new ComboBox<>(FXCollections.observableArrayList("是","否"));
            ((ComboBox<String>)control).setValue(Set.of("true","1","是","在籍","在校").contains(initial)?"是":"否");
        } else control=new TextField(initial);
        control.setUserData(name);control.getStyleClass().add("form-control");
        return control;
    }
    private String controlValue(Control control) {
        if(control instanceof TextInputControl text)return safe(text.getText());
        if(control instanceof DatePicker date)return date.getValue()==null?"":date.getValue().toString();
        if(control instanceof ComboBox<?> combo)return combo.getValue()==null?"":safe(combo.getValue());
        return "";
    }
    private void releaseEditLock(){if(overview!=null&&overview.getStudent()!=null)service.endEdit(overview.getStudent().getStudentId(),m->{});}
    @FXML private void handleIndexBase() {
        scrollDetail(0.0);
    }
    @FXML private void handleIndexStudy() {
        scrollDetail(0.34);
    }
    @FXML private void handleIndexAdmission() {
        scrollDetail(0.68);
    }
    @FXML private void handleIndexContact() {
        scrollDetail(1.0);
    }
    private void scrollDetail(double value) {
        studentTabs.getSelectionModel().select(detailTab);
        Platform.runLater(()->detailScrollPane.setVvalue(value));
    }
    private void setupRole() {
        if(isAdmin()) {
            studentTabs.getTabs().removeAll(overviewTab);
            titleLabel.setText("学籍管理");
            studentDetailSidebar.setVisible(false);
            studentDetailSidebar.setManaged(false);
            adminDetailSidebar.setVisible(true);
            adminDetailSidebar.setManaged(true);
            detailReturnButton.setText("← 返回学生总览");
            studentTabs.getSelectionModel().select(adminListTab);
        }
        else {
            studentTabs.getTabs().removeAll(adminListTab,reviewTab);
            titleLabel.setText("我的学籍");
            adminDetailSidebar.setVisible(false);
            adminDetailSidebar.setManaged(false);
            detailReturnButton.setText("← 返回学籍总览");
        }
    }
    private boolean isAdmin() {
        String r=ClientSession.getInstance().getRole();
        return "ADMIN".equalsIgnoreCase(safe(r))||"管理员".equals(r);
    }
    @FXML private void handleReturnFromDetails() {
        if(isAdmin())handleReturnToStudentList();
        else handleShowOverview();
    }
    private void setupTables() {
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
                    Student row=getTableView().getItems().get(getIndex());showStudentDetails(row);
                }
                );
            }
            protected void updateItem(Void item,boolean empty) {
                super.updateItem(item,empty);button.setText(adminMaintenanceMode?"编辑":"查看详情");setGraphic(empty?null:button);
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
        maintenanceAwardTypeCol.setCellValueFactory(c->text(c.getValue().getAwardType()));
        maintenanceAwardLevelCol.setCellValueFactory(c->text(c.getValue().getAwardLevel()));
        maintenanceAwardDateCol.setCellValueFactory(c->text(c.getValue().getAwardDate()));
        maintenanceAwardOrganizationCol.setCellValueFactory(c->text(c.getValue().getOrganization()));
        maintenanceAwardDescriptionCol.setCellValueFactory(c->text(c.getValue().getDescription()));
        maintenanceAidIdCol.setCellValueFactory(c->text(c.getValue().getAidId()));
        maintenanceAidStudentCol.setCellValueFactory(c->text(c.getValue().getStudentId()));
        maintenanceAidNameCol.setCellValueFactory(c->text(c.getValue().getAidName()));
        maintenanceAidTypeCol.setCellValueFactory(c->text(c.getValue().getAidType()));
        maintenanceAidAmountCol.setCellValueFactory(c->text(c.getValue().getAmount()));
        maintenanceAidDateCol.setCellValueFactory(c->text(c.getValue().getAidDate()));
        maintenanceAidProviderCol.setCellValueFactory(c->text(c.getValue().getProvider()));
        maintenanceAidStatusCol.setCellValueFactory(c->text(c.getValue().getStatus()));
        maintenanceAidDescriptionCol.setCellValueFactory(c->text(c.getValue().getDescription()));
        studentTable.setOnMouseClicked(e-> {
            if(e.getClickCount()==2)handleShowSelectedStudent();
        }
        );
    }
    private <T>T data(Message m,String key,Type type) {
        Object v=m==null?null:m.getData().get(key);
        return v==null?null:gson.fromJson(gson.toJson(v),type);
    }
    private void onOverview(Message m) {
        Platform.runLater(()-> {
            if(!ok(m)) {
                setStatus(message(m,"学籍信息加载失败"));return;
            }
            overview=data(m,"overview",StudentOverviewVO.class);
            if(!isAdmin()&&overview!=null&&overview.getStudent()!=null&&safe(overview.getStudent().getUID()).isBlank())
                overview.getStudent().setUID(safe(ClientSession.getInstance().getUsername()));
            render(overview);setStatus("学籍信息已更新");
        }
        );
    }
    private void loadAdmin() {
        service.listStudents(m->Platform.runLater(()-> {
            if(ok(m)) {
                List<Student> v=data(m,"students",new TypeToken<List<Student>>() {
                }
                .getType());students=v==null?new ArrayList<>():v;filteredStudents=new ArrayList<>(students);currentPage=1;refreshStudentPage();reviewTable.refresh();
            }
            else setStatus(message(m,"学生列表加载失败"));
        }
        ));
        service.listPendingRequests(m->Platform.runLater(()-> {
            List<StudentChangeRequest> v=ok(m)?data(m,"requests",new TypeToken<List<StudentChangeRequest>>() {
            }
            .getType()):List.of();reviewRequests=v==null?new ArrayList<>():new ArrayList<>(v);applyReviewBucket();
        }
        ));
    }
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
        for(Button button:List.of(editBaseButton,editStudyButton,editAdmissionButton,editContactButton)){
            button.setVisible(!isAdmin()||editableAdmin);button.setManaged(!isAdmin()||editableAdmin);
        }
        adminRecordMaintenancePane.setVisible(editableAdmin);adminRecordMaintenancePane.setManaged(editableAdmin);
        service.queryStudentOverview(s.getStudentId(),m-> {
            onOverview(m);Platform.runLater(()->studentTabs.getSelectionModel().select(detailTab));
        }
        );
    }
    @FXML private void handleReturnToStudentList() {
        editing=false;
        studentTabs.getSelectionModel().select(adminListTab);
    }
    private int pageCount() {
        return Math.max(1,(filteredStudents.size()+PAGE_SIZE-1)/PAGE_SIZE);
    }
    private void refreshStudentPage() {
        currentPage=Math.max(1,Math.min(currentPage,pageCount()));
        int from=Math.min((currentPage-1)*PAGE_SIZE,filteredStudents.size()),to=Math.min(from+PAGE_SIZE,filteredStudents.size());
        studentTable.setItems(FXCollections.observableArrayList(filteredStudents.subList(from,to)));
        pageNumberField.setText(String.valueOf(currentPage));
        pageSummaryLabel.setText("共"+filteredStudents.size()+"条  共"+pageCount()+"页");
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
            service.updateStudentByAdmin(updated,m->Platform.runLater(()-> {
                setStatus(message(m,"学籍信息已直接更新"));if(ok(m)) {
                    releaseEditLock();editing=false;service.queryStudentOverview(updated.getStudentId(),this::onOverview);
                }
            }
            ));
            return;
        }
        StudentChangeRequest r=new StudentChangeRequest();
        r.setItems(items);
        service.submitChangeRequest(r,m->Platform.runLater(()-> {
            setStatus(message(m,"申请已提交"));if(ok(m)) {
                releaseEditLock();editing=false;refreshData();
            }
        }
        ));
    }
    private List<StudentChangeItem> changedItems() {
        List<StudentChangeItem> items=new ArrayList<>();
        for(Control f:editable) {
            String n=(String)f.getUserData(),old=safe(read(overview.getStudent(),n)),now=controlValue(f);
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
        long expected=r.getRequestId();
        service.queryChangeRequest(expected,m->Platform.runLater(()-> {
            if(selected==null||selected.getRequestId()!=expected)return;StudentChangeRequest detail=ok(m)?data(m,"request",StudentChangeRequest.class):null;if(detail==null) {
                setStatus(message(m,"审核详情加载失败"));handleBackToReviewOverview();return;
            }
            selected=detail;
        }
        ));
        service.queryStudentOverview(r.getStudentId(),m->Platform.runLater(()-> {
            StudentOverviewVO studentOverview=ok(m)?data(m,"overview",StudentOverviewVO.class):null;
            if(studentOverview!=null&&studentOverview.getStudent()!=null) {
                fill(reviewStudentBaseGrid,studentOverview.getStudent(),baseFields());
                fill(reviewStudentStudyGrid,studentOverview.getStudent(),studyFields());
            }
        }));
    }
    @FXML private void handleBackToReviewOverview() {
        selected=null;
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
        StudentReviewVO v=new StudentReviewVO();
        v.setRequestId(selected.getRequestId());
        v.setReviewResult(s);
        v.setReviewRemark(safe(reviewRemarkArea.getText()));
        service.reviewChangeRequest(v,m->Platform.runLater(()-> {
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
        Student s=v==null?null:v.getStudent();
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
    }
    private void renderAwards(List<StudentAward> list) {
        awardTile.getChildren().clear();
        if(list==null||list.isEmpty()) {
            awardTile.getChildren().add(emptyRecord("暂无奖励记录"));
            return;
        }
        for(StudentAward a:list)awardTile.getChildren().add(recordCard("🏆",show(a.getAwardName()),joinLine(show(a.getAwardType()),show(a.getAwardLevel())),show(a.getAwardDate()),"award-record-icon"));
    }
    private void renderAids(List<StudentAid> list) {
        aidTile.getChildren().clear();
        if(list==null||list.isEmpty()) {
            aidTile.getChildren().add(emptyRecord("暂无资助记录"));
            return;
        }
        for(StudentAid a:list) {
            String amount=a.getAmount()==null?"":("¥"+a.getAmount());
            aidTile.getChildren().add(recordCard("◉",show(a.getAidName()),joinLine(show(a.getAidType()),amount),show(a.getAidDate()),"aid-record-icon"));
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
        VBox textBox=new VBox(3,title,detail,time);
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
        initial.put("类型（SCHOLARSHIP/HONOR/COMPETITION/RESEARCH/PRACTICE/OTHER）",award.getAwardType()==null?"HONOR":award.getAwardType().name());
        initial.put("奖励级别",showForInput(award.getAwardLevel()));
        initial.put("奖励日期（yyyy-MM-dd，可留空）",showForInput(award.getAwardDate()));
        initial.put("颁发单位",showForInput(award.getOrganization()));
        initial.put("奖励说明",showForInput(award.getDescription()));
        Optional<Map<String,String>> result=showRecordDialog("奖励维护",initial);if(result.isEmpty())return;
        Map<String,String> values=result.get();
        try{
            award.setStudentId(overview.getStudent().getStudentId());award.setAwardName(values.get("奖励名称"));
            award.setAwardType(StudentAwardType.valueOf(values.get("类型（SCHOLARSHIP/HONOR/COMPETITION/RESEARCH/PRACTICE/OTHER）").toUpperCase()));
            award.setAwardLevel(values.get("奖励级别"));String date=values.get("奖励日期（yyyy-MM-dd，可留空）");
            award.setAwardDate(date.isBlank()?null:Date.valueOf(date));award.setOrganization(values.get("颁发单位"));award.setDescription(values.get("奖励说明"));
        }catch(Exception e){setStatus("奖励信息格式错误："+e.getMessage());return;}
        java.util.function.Consumer<Message> done=m->Platform.runLater(()->{setStatus(message(m,"奖励维护完成"));if(ok(m))reloadCurrentOverview();});
        if(source==null)service.addAward(award,done);else service.updateAward(award,done);
    }
    @FXML private void handleDeleteAward(){
        StudentAward award=maintenanceAwardTable.getSelectionModel().getSelectedItem();
        if(award==null){setStatus("请先选择奖励记录");return;}
        service.deleteAward(award.getAwardId(),m->Platform.runLater(()->{setStatus(message(m,"奖励已删除"));if(ok(m))reloadCurrentOverview();}));
    }
    @FXML private void handleAddAid(){saveAid(null);}
    @FXML private void handleEditAid(){
        StudentAid selectedAid=maintenanceAidTable.getSelectionModel().getSelectedItem();
        if(selectedAid==null){setStatus("请先选择资助记录");return;}saveAid(selectedAid);
    }
    private void saveAid(StudentAid source){
        StudentAid aid=source==null?new StudentAid():source;
        LinkedHashMap<String,String> initial=new LinkedHashMap<>();
        initial.put("资助名称",showForInput(aid.getAidName()));initial.put("资助类型",showForInput(aid.getAidType()));
        initial.put("金额",aid.getAmount()==null?"0":aid.getAmount().toPlainString());initial.put("资助日期（yyyy-MM-dd，可留空）",showForInput(aid.getAidDate()));
        initial.put("资助提供方",showForInput(aid.getProvider()));initial.put("状态（PENDING/ISSUED/CANCELLED）",aid.getStatus()==null?"PENDING":aid.getStatus().name());
        initial.put("资助说明",showForInput(aid.getDescription()));
        Optional<Map<String,String>> result=showRecordDialog("资助维护",initial);if(result.isEmpty())return;
        Map<String,String> values=result.get();
        try{
            aid.setStudentId(overview.getStudent().getStudentId());aid.setAidName(values.get("资助名称"));aid.setAidType(values.get("资助类型"));
            aid.setAmount(new java.math.BigDecimal(values.get("金额")));String date=values.get("资助日期（yyyy-MM-dd，可留空）");
            aid.setAidDate(date.isBlank()?null:Date.valueOf(date));aid.setProvider(values.get("资助提供方"));
            aid.setStatus(StudentAidStatus.valueOf(values.get("状态（PENDING/ISSUED/CANCELLED）").toUpperCase()));aid.setDescription(values.get("资助说明"));
        }catch(Exception e){setStatus("资助信息格式错误："+e.getMessage());return;}
        java.util.function.Consumer<Message> done=m->Platform.runLater(()->{setStatus(message(m,"资助维护完成"));if(ok(m))reloadCurrentOverview();});
        if(source==null)service.addAid(aid,done);else service.updateAid(aid,done);
    }
    @FXML private void handleDeleteAid(){
        StudentAid aid=maintenanceAidTable.getSelectionModel().getSelectedItem();
        if(aid==null){setStatus("请先选择资助记录");return;}
        service.deleteAid(aid.getAidId(),m->Platform.runLater(()->{setStatus(message(m,"资助已删除"));if(ok(m))reloadCurrentOverview();}));
    }
    private Optional<Map<String,String>> showRecordDialog(String title,LinkedHashMap<String,String> initial){
        Dialog<Map<String,String>> dialog=new Dialog<>();dialog.setTitle(title);dialog.setHeaderText("请在同一表单中填写全部信息");
        GridPane grid=new GridPane();grid.setHgap(12);grid.setVgap(10);grid.setPadding(new Insets(8,12,8,12));
        LinkedHashMap<String,TextField> fields=new LinkedHashMap<>();int row=0;
        for(Map.Entry<String,String> entry:initial.entrySet()){
            TextField field=new TextField(entry.getValue());field.setPrefWidth(360);fields.put(entry.getKey(),field);
            Label label=new Label(entry.getKey());label.setWrapText(true);label.setMaxWidth(250);grid.add(label,0,row);grid.add(field,1,row++);
        }
        dialog.getDialogPane().setContent(grid);dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        dialog.setResultConverter(button->{
            if(button!=ButtonType.OK)return null;Map<String,String> values=new LinkedHashMap<>();
            fields.forEach((key,field)->values.put(key,field.getText().trim()));return values;
        });
        return dialog.showAndWait();
    }
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
            default -> n;
        };
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
