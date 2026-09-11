package controller;

import app.ClientMain;
import com.google.gson.Gson;
import entity.BorrowRecord;
import entity.FineRecord;
import entity.Reservation;
import entity.User;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import entity.AdminPermission;
import entity.Student;
import entity.Teacher;
import enums.ReservationStatus;
import vo.StudentOverviewVO;
import vo.TeacherOverviewVO;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.LibraryClientService;
import session.ClientSession;
import util.AlertUtil;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;

public class MainController {

    // ===== 侧边栏控件 =====
    @FXML private ImageView sidebarAvatarView;
    @FXML private Label sidebarNameLabel;
    @FXML private Label sidebarRoleLabel;

    @FXML private Button navHomeBtn;
    @FXML private Button navProfileBtn;
    @FXML private Button navStudentBtn;
    @FXML private Button navLibraryBtn;
    @FXML private Button navCourseBtn;
    @FXML private Button navStoreBtn;
    @FXML private Button navBankBtn;
    @FXML private Button navAiBtn;
    @FXML private Button permissionNavBtn;
    @FXML private Button navLogoutBtn;

    // ===== 课表卡片 (管理员隐藏) =====
    @FXML private VBox scheduleCard;

    // ===== 管理员“我的权限”卡片 =====
    @FXML private VBox adminPermissionCard;
    @FXML private Label myPermTagLabel;
    @FXML private Label permStatusAcademic;
    @FXML private Label permStatusLibrary;
    @FXML private Label permStatusCourse;
    @FXML private Label permStatusShop;
    @FXML private Label permStatusBank;
    @FXML private Label myPermDetailText;

    // ===== 学生与教师“学籍信息”卡片 =====
    @FXML private VBox studentAcademicCard;
    @FXML private Label academicCardTitle;
    @FXML private Label academicCardTag;
    @FXML private Label academicNationalityLabel;
    @FXML private Label academicPoliticalStatusLabel;
    @FXML private Label academicNativePlaceLabel;
    @FXML private Label academicStatusKeyLabel;
    @FXML private Label academicStatusValLabel;

    // ===== 个人信息卡片 =====
    @FXML private Label infoNameLabel;
    @FXML private Label infoUIDLabel;
    @FXML private Label infoGenderLabel;
    @FXML private Label infoCollegeTitle;
    @FXML private Label infoCollegeLabel;
    @FXML private Label infoMajorTitle;
    @FXML private Label infoMajorLabel;
    @FXML private Label infoPhoneLabel;
    @FXML private Label infoEmailLabel;

    // ===== 通知消息卡片 =====
    @FXML private Label libraryBorrowNoticeLabel;
    @FXML private Label libraryReservationNoticeLabel;
    @FXML private Label libraryFineNoticeLabel;

    // ===== 一卡通金额卡片 =====
    @FXML private Label walletBalanceLabel;
    @FXML private Label walletAccountLabel;

    @FXML
    public void initialize() {
        // 1. 读取并显示当前用户本地 Session 数据
        loadUserData();

        // 2. 异步向服务端查询最新用户信息（同步最新学籍与余额）
        fetchLatestUserInfo();

        // 3. 异步拉取图书馆相关通知消息
        loadLibraryNotices();
    }

    /**
     * 填充个人基本信息与侧边栏用户资料
     */
    private void loadUserData() {
        User user = ClientSession.getInstance().getCurrentUser();
        String roleStr = ClientSession.getInstance().getRole();
        if (roleStr == null || roleStr.isBlank()) roleStr = "学生";

        String uid = ClientSession.getInstance().getUsername();
        String name = user != null && user.getName() != null && !user.getName().isBlank() ? user.getName() : uid;

        // 侧边栏
        if (sidebarNameLabel != null) sidebarNameLabel.setText(name != null ? name : "用户");
        if (sidebarRoleLabel != null) {
            String college = (user != null && user.getCollege() != null && !user.getCollege().isBlank())
                    ? user.getCollege() : "";
            sidebarRoleLabel.setText(college.isEmpty() ? roleStr : (college + " · " + roleStr));
        }
        if (sidebarAvatarView != null && user != null && user.getAvatar() != null && !user.getAvatar().isBlank()) {
            showAvatar(sidebarAvatarView, user.getAvatar());
        }

        // 个人信息卡片
        if (infoNameLabel != null) infoNameLabel.setText(name != null ? name : "—");
        if (infoUIDLabel != null) infoUIDLabel.setText(uid != null ? uid : "—");
        if (infoGenderLabel != null) {
            infoGenderLabel.setText(user != null && user.getGender() != null ? user.getGender() : "男");
        }
        if (infoCollegeLabel != null) {
            infoCollegeLabel.setText(user != null && user.getCollege() != null ? user.getCollege() : "—");
        }
        if (infoMajorLabel != null) {
            infoMajorLabel.setText(user != null && user.getMajor() != null ? user.getMajor() : "—");
        }
        if (infoPhoneLabel != null) {
            infoPhoneLabel.setText(user != null && user.getPhone() != null && !user.getPhone().isBlank() ? user.getPhone() : "未绑定");
        }
        if (infoEmailLabel != null) {
            infoEmailLabel.setText(user != null && user.getEmail() != null && !user.getEmail().isBlank() ? user.getEmail() : "未绑定");
        }

        // 根据角色调整表格标签（专业/职称/职务，学院/部门）
        if (infoMajorTitle != null && infoCollegeTitle != null) {
            if ("TEACHER".equalsIgnoreCase(roleStr) || "教师".equals(roleStr)) {
                infoMajorTitle.setText("职称");
                infoCollegeTitle.setText("学院");
            } else if ("ADMIN".equalsIgnoreCase(roleStr) || "管理员".equals(roleStr)) {
                infoMajorTitle.setText("职务");
                infoCollegeTitle.setText("部门");
            } else {
                infoMajorTitle.setText("专业");
                infoCollegeTitle.setText("学院");
            }
        }

        // 一卡通金额卡片
        if (walletBalanceLabel != null) {
            walletBalanceLabel.setText("¥ " + (user != null && user.getBalance() != null
                    ? user.getBalance().setScale(2).toPlainString() : "0.00"));
        }
        if (walletAccountLabel != null) {
            walletAccountLabel.setText("一卡通号：" + (uid != null ? uid : "—"));
        }

        // 身份判断：管理员隐藏主页课表；仅 UID==admin 的主管理员显示权限管理入口
        boolean isAdmin = "ADMIN".equalsIgnoreCase(roleStr) || "管理员".equals(roleStr)
                || (user != null && user.getRole() == enums.Role.ADMIN);
        boolean isTeacher = "TEACHER".equalsIgnoreCase(roleStr) || "教师".equals(roleStr)
                || (user != null && user.getRole() == enums.Role.TEACHER);
        boolean isSuperAdmin = isAdmin && "admin".equalsIgnoreCase(uid);

        if (scheduleCard != null) {
            scheduleCard.setVisible(!isAdmin);
            scheduleCard.setManaged(!isAdmin);
        }
        if (permissionNavBtn != null) {
            permissionNavBtn.setVisible(isSuperAdmin);
            permissionNavBtn.setManaged(isSuperAdmin);
        }
        if (adminPermissionCard != null) {
            adminPermissionCard.setVisible(isAdmin);
            adminPermissionCard.setManaged(isAdmin);
        }
        if (studentAcademicCard != null) {
            studentAcademicCard.setVisible(!isAdmin);
            studentAcademicCard.setManaged(!isAdmin);
        }

        if (isAdmin) {
            updateMyPermissionDisplay();
            fetchMyPermissions();
        } else {
            fetchAcademicInfo(isTeacher);
        }
    }

    /**
     * 异步拉取最新用户信息
     */
    private void fetchLatestUserInfo() {
        String uid = ClientSession.getInstance().getUsername();
        if (uid == null) return;
        Message request = new Message(MessageType.REQUEST, "user", "getuserinfo");
        request.putData("cardNo", uid);
        SocketClient.getInstance().sendAsync(request).thenAccept(response -> {
            if (response.getCode() == MessageCode.SUCCESS) {
                Object userObj = response.getData("user");
                if (userObj != null) {
                    Gson gson = new Gson();
                    User latestUser = gson.fromJson(gson.toJson(userObj), User.class);
                    if (latestUser != null) {
                        ClientSession.getInstance().setCurrentUser(latestUser);
                        Platform.runLater(this::loadUserData);
                    }
                }
            }
        }).exceptionally(e -> null);
    }

    /**
     * 异步读取图书馆通知消息
     */
    private void loadLibraryNotices() {
        // 1. 查询借阅记录
        LibraryClientService.getInstance().getCurrentBorrow().thenAccept(borrows -> {
            Platform.runLater(() -> {
                if (libraryBorrowNoticeLabel != null) {
                    if (borrows == null || borrows.isEmpty()) {
                        libraryBorrowNoticeLabel.setText("当前无在借图书，欢迎借阅");
                    } else {
                        libraryBorrowNoticeLabel.setText("当前在借图书 " + borrows.size() + " 本，请注意归还期限");
                    }
                }
            });
        }).exceptionally(e -> {
            Platform.runLater(() -> {
                if (libraryBorrowNoticeLabel != null) libraryBorrowNoticeLabel.setText("图书借阅服务运行正常");
            });
            return null;
        });

        // 2. 查询预约记录（仅统计状态为 0 - 预约中的有效记录）
        LibraryClientService.getInstance().getReservations().thenAccept(reservations -> {
            Platform.runLater(() -> {
                if (libraryReservationNoticeLabel != null) {
                    long activeCount = reservations != null ? reservations.stream()
                            .filter(r -> r.getStatus() == ReservationStatus.RESERVING.getCode())
                            .count() : 0;
                    if (activeCount == 0) {
                        libraryReservationNoticeLabel.setText("暂无图书预约到馆提醒");
                    } else {
                        libraryReservationNoticeLabel.setText("您有 " + activeCount + " 本图书处于预约中");
                    }
                }
            });
        }).exceptionally(e -> {
            Platform.runLater(() -> {
                if (libraryReservationNoticeLabel != null) libraryReservationNoticeLabel.setText("图书预约服务运行正常");
            });
            return null;
        });

        // 3. 查询罚款记录
        LibraryClientService.getInstance().getFineRecords().thenAccept(fines -> {
            Platform.runLater(() -> {
                if (libraryFineNoticeLabel != null) {
                    long unpaid = fines != null ? fines.stream().filter(f -> f.getStatus() == 0).count() : 0;
                    if (unpaid == 0) {
                        libraryFineNoticeLabel.setText("暂无未缴逾期图书罚款");
                    } else {
                        libraryFineNoticeLabel.setText("您有 " + unpaid + " 笔图书罚款待缴纳，请及时处理");
                    }
                }
            });
        }).exceptionally(e -> {
            Platform.runLater(() -> {
                if (libraryFineNoticeLabel != null) libraryFineNoticeLabel.setText("暂无逾期欠款记录");
            });
            return null;
        });
    }

    private void showAvatar(ImageView view, String base64) {
        if (view == null) return;
        if (base64 == null || base64.isEmpty()) {
            view.setImage(null);
            return;
        }
        try {
            view.setImage(new Image(new ByteArrayInputStream(Base64.getDecoder().decode(base64))));
        } catch (Exception e) {
            view.setImage(null);
        }
    }

    // ===== 页面导航动作 =====

    @FXML
    public void handleNavigateHome(ActionEvent event) {
        // 当前已在主页，重新刷新数据
        loadUserData();
        fetchLatestUserInfo();
        loadLibraryNotices();
    }

    @FXML
    public void handleNavigateProfile(ActionEvent event) {
        ClientMain.switchScene("/resources/fxml/ProfileView.fxml");
    }

    @FXML
    public void openStudentAffairs(ActionEvent event) {
        String role = ClientSession.getInstance().getRole();
        if ("TEACHER".equalsIgnoreCase(role) || "教师".equals(role)) {
            ClientMain.switchScene("/resources/fxml/TeacherView.fxml");
        } else if ("ADMIN".equalsIgnoreCase(role) || "管理员".equals(role)) {
            if (!ClientSession.getInstance().hasAcademicPermission()) {
                AlertUtil.showWarning("权限不足", "您没有该模块的管理权限");
                return;
            }
            ClientMain.switchScene("/resources/fxml/InformationSelectView.fxml");
        } else {
            ClientMain.switchScene("/resources/fxml/StudentView.fxml");
        }
    }

    @FXML
    public void openLibrary(ActionEvent event) {
        ClientMain.switchScene("/resources/fxml/LibraryView.fxml");
    }

    @FXML
    public void openCourseSelection(ActionEvent event) {
        if (isAdminUser()) {
            if (!ClientSession.getInstance().hasCoursePermission()) {
                AlertUtil.showWarning("权限不足", "您没有该模块的管理权限");
                return;
            }
        }
        showSubsystemNotice("选课子系统");
    }

    @FXML
    public void openStore(ActionEvent event) {
        if (isAdminUser()) {
            if (!ClientSession.getInstance().hasShopPermission()) {
                AlertUtil.showWarning("权限不足", "您没有该模块的管理权限");
                return;
            }
        }
        ClientMain.switchScene("/resources/fxml/ShopView.fxml");
    }

    @FXML
    public void openBank(ActionEvent event) {
        if (isAdminUser()) {
            if (!ClientSession.getInstance().hasBankPermission()) {
                AlertUtil.showWarning("权限不足", "您没有该模块的管理权限");
                return;
            }
        }
        ClientMain.switchScene("/resources/fxml/BankView.fxml");
    }

    @FXML
    public void handleNavigatePermission(ActionEvent event) {
        ClientMain.switchScene("/resources/fxml/PermissionView.fxml");
    }

    @FXML
    public void openAI(ActionEvent event) {
        ClientMain.switchScene("/resources/fxml/AIview.fxml");
    }

    @FXML
    public void openHospital(ActionEvent event) {
        openAI(event);
    }

    @FXML
    public void handleLogout(ActionEvent event) {
        // 向服务端发送登出请求
        try {
            Message logoutMsg = new Message(MessageType.REQUEST, "user", "logout");
            SocketClient.getInstance().sendAsync(logoutMsg);
        } catch (Exception ignored) {}

        // 清除本地 Session
        ClientSession.getInstance().logout();

        // 返回登录页
        ClientMain.switchScene("/resources/fxml/LoginView.fxml");
    }

    private void showSubsystemNotice(String name) {
        AlertUtil.showInfo("系统提示", "正在载入 " + name + " 模块...");
    }

    private boolean isAdminUser() {
        String role = ClientSession.getInstance().getRole();
        User user = ClientSession.getInstance().getCurrentUser();
        return "ADMIN".equalsIgnoreCase(role) || "管理员".equals(role)
                || (user != null && user.getRole() == enums.Role.ADMIN);
    }

    private void fetchMyPermissions() {
        Message request = new Message(MessageType.REQUEST, "user", "get_my_permissions");
        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.getCode() == MessageCode.SUCCESS) {
                        Object obj = response.getData("adminPermission");
                        if (obj != null) {
                            Gson gson = new Gson();
                            AdminPermission perm = gson.fromJson(gson.toJson(obj), AdminPermission.class);
                            ClientSession.getInstance().setAdminPermission(perm);
                            updateMyPermissionDisplay();
                        }
                    }
                }));
    }

    /**
     * 刷新并展示当前管理员的权限信息（两行内容：第一行是模块名称，第二行是只读权限状态）
     */
    private void updateMyPermissionDisplay() {
        if (adminPermissionCard == null || !adminPermissionCard.isVisible()) {
            return;
        }
        ClientSession session = ClientSession.getInstance();
        User user = session.getCurrentUser();
        String uid = user != null && user.getUID() != null ? user.getUID() : session.getUsername();
        boolean isSuperAdmin = "admin".equalsIgnoreCase(uid);

        boolean academic = session.hasAcademicPermission();
        boolean library = session.hasLibraryPermission();
        boolean course = session.hasCoursePermission();
        boolean shop = session.hasShopPermission();
        boolean bank = session.hasBankPermission();

        // 第二行各个模块的只读状态指示
        setPermBadge(permStatusAcademic, academic);
        setPermBadge(permStatusLibrary, library);
        setPermBadge(permStatusCourse, course);
        setPermBadge(permStatusShop, shop);
        setPermBadge(permStatusBank, bank);

        // 文字总结
        List<String> authorized = new java.util.ArrayList<>();
        if (academic) authorized.add("学籍信息");
        if (library) authorized.add("图书馆");
        if (course) authorized.add("选课");
        if (shop) authorized.add("商店");
        if (bank) authorized.add("银行");

        if (isSuperAdmin) {
            if (authorized.isEmpty()) {
                if (myPermDetailText != null) {
                    myPermDetailText.setText("暂无业务子系统管理权限（可在左侧“权限管理”中随时按需分配）");
                }
                if (myPermTagLabel != null) {
                    myPermTagLabel.setText("主管理员");
                }
            } else {
                if (myPermDetailText != null) {
                    myPermDetailText.setText(String.join("、", authorized));
                }
                if (myPermTagLabel != null) {
                    myPermTagLabel.setText("主管理员 · 已授权 " + authorized.size() + " 个模块");
                }
            }
        } else if (authorized.isEmpty()) {
            if (myPermDetailText != null) {
                myPermDetailText.setText("暂无任何已授权业务模块，请联系主管理员分配权限");
            }
            if (myPermTagLabel != null) {
                myPermTagLabel.setText("未授权");
            }
        } else {
            if (myPermDetailText != null) {
                myPermDetailText.setText(String.join("、", authorized));
            }
            if (myPermTagLabel != null) {
                myPermTagLabel.setText("已授权 " + authorized.size() + " 个模块");
            }
        }
    }

    private void setPermBadge(Label label, boolean hasPerm) {
        if (label == null) return;
        if (hasPerm) {
            label.setText("✔ 有权限");
            label.setStyle("-fx-background-color: #dcfce7; -fx-text-fill: #166534; -fx-font-weight: bold; -fx-padding: 3 10 3 10; -fx-background-radius: 4px; -fx-font-size: 12px;");
        } else {
            label.setText("✖ 无权限");
            label.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #94a3b8; -fx-font-weight: bold; -fx-padding: 3 10 3 10; -fx-background-radius: 4px; -fx-font-size: 12px;");
        }
    }

    /**
     * 异步加载学生/教师的学籍档案信息（民族、籍贯、政治面貌等）
     */
    private void fetchAcademicInfo(boolean isTeacher) {
        if (studentAcademicCard == null || !studentAcademicCard.isVisible()) {
            return;
        }
        if (isTeacher) {
            if (academicCardTitle != null) academicCardTitle.setText("🎓  学籍信息");
            if (academicStatusKeyLabel != null) academicStatusKeyLabel.setText("在任状态");
            Message request = new Message(MessageType.TEACHER_OVERVIEW_QUERY, "teacher", "overview");
            SocketClient.getInstance().sendAsync(request).thenAccept(response -> {
                if (response.getCode() == MessageCode.SUCCESS) {
                    Object obj = response.getData("overview");
                    if (obj != null) {
                        Gson gson = new Gson();
                        TeacherOverviewVO vo = gson.fromJson(gson.toJson(obj), TeacherOverviewVO.class);
                        if (vo != null && vo.getTeacher() != null) {
                            Teacher t = vo.getTeacher();
                            Platform.runLater(() -> {
                                if (academicNationalityLabel != null) academicNationalityLabel.setText(strOrDefault(t.getNationality()));
                                if (academicNativePlaceLabel != null) academicNativePlaceLabel.setText(strOrDefault(t.getNativePlace()));
                                if (academicPoliticalStatusLabel != null) academicPoliticalStatusLabel.setText(strOrDefault(t.getPoliticalStatus()));
                                if (academicStatusValLabel != null) academicStatusValLabel.setText(strOrDefault(t.getEmploymentStatus(), "在职"));
                                if (academicCardTag != null) academicCardTag.setText(strOrDefault(t.getEmploymentStatus(), "在职"));
                            });
                        }
                    }
                }
            }).exceptionally(e -> null);
        } else {
            if (academicCardTitle != null) academicCardTitle.setText("🎓  学籍信息");
            if (academicStatusKeyLabel != null) academicStatusKeyLabel.setText("学籍状态");
            Message request = new Message(MessageType.STUDENT_OVERVIEW_QUERY, "student", "queryOverview");
            SocketClient.getInstance().sendAsync(request).thenAccept(response -> {
                if (response.getCode() == MessageCode.SUCCESS) {
                    Object obj = response.getData("overview");
                    if (obj != null) {
                        Gson gson = new Gson();
                        StudentOverviewVO vo = gson.fromJson(gson.toJson(obj), StudentOverviewVO.class);
                        if (vo != null && vo.getStudent() != null) {
                            Student s = vo.getStudent();
                            Platform.runLater(() -> {
                                if (academicNationalityLabel != null) academicNationalityLabel.setText(strOrDefault(s.getNationality()));
                                if (academicNativePlaceLabel != null) academicNativePlaceLabel.setText(strOrDefault(s.getNativePlace()));
                                if (academicPoliticalStatusLabel != null) academicPoliticalStatusLabel.setText(strOrDefault(s.getPoliticalStatus()));
                                if (academicStatusValLabel != null) academicStatusValLabel.setText(strOrDefault(s.getStudentStatus(), "在籍"));
                                if (academicCardTag != null) academicCardTag.setText(strOrDefault(s.getStudentStatus(), "在籍"));
                            });
                        }
                    }
                }
            }).exceptionally(e -> null);
        }
    }

    private String strOrDefault(String val) {
        return (val != null && !val.isBlank()) ? val : "—";
    }

    private String strOrDefault(String val, String defaultVal) {
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }
}