package controller;

import java.util.Objects;
import java.util.function.BiConsumer;
import app.ClientMain;
import com.google.gson.Gson;
import entity.ShopOrder;
import entity.User;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Circle;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import model.course.CourseTermView;
import model.course.GradeSummaryView;
import model.course.TrainingPlanGroupView;
import service.CourseService;
import service.CourseServices;
import service.TeacherCourseServices;
import java.util.Locale;
import entity.AdminPermission;
import entity.Student;
import entity.Teacher;
import enums.OrderStatus;
import enums.ReservationStatus;
import enums.StudentChangeStatus;
import vo.StudentOverviewVO;
import vo.TeacherOverviewVO;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.LibraryClientService;
import service.NotificationClientService;
import session.ClientSession;
import util.AlertUtil;
import util.FXMLUtil;
import util.PageLeaveGuard;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;

public class MainController {

    private static final Set<String> SHOWN_REJECTED_REQUESTS = ConcurrentHashMap.newKeySet();
    private static final Preferences NOTICE_PREFERENCES = Preferences.userNodeForPackage(MainController.class);

    static final String ADMIN_COURSE_VIEW = "/resources/fxml/AdminCourseManagementView.fxml";
    static final String STUDENT_COURSE_VIEW = "/resources/fxml/CourseManagementView.fxml";
    static final String TEACHER_COURSE_VIEW = "/resources/fxml/TeacherCourseManagementView.fxml";
    static final String COURSE_NOTICE_TITLE = "系统提示";
    static final String COURSE_NOTICE_MESSAGE = "当前身份无法进入教务模块";

    interface SceneSwitcher {
        void switchTo(String fxmlPath);
    }

    private SceneSwitcher sceneSwitcher = ClientMain::switchScene;
    private BiConsumer<String, String> infoReporter = AlertUtil::showInfo;
    private BiConsumer<String, String> warningReporter = AlertUtil::showWarning;

    @FXML private BorderPane rootMain;
    @FXML private ScrollPane homeScrollPane;

    // ===== 实例与 SPA 路由管理 =====
    private static MainController instance;

    public static MainController getInstance() {
        return instance;
    }

    // ===== 侧边栏控件 =====
    @FXML private ImageView sidebarAvatarView;
    @FXML private Label sidebarNameLabel;
    @FXML private Label sidebarRoleLabel;
    @FXML private Label sidebarCollegeLabel;
    @FXML private Label sidebarMajorLabel;

    @FXML private Button navHomeBtn;
    @FXML private Button navProfileBtn;
    @FXML private Button navStudentBtn;
    @FXML private Button navLibraryBtn;
    @FXML private Button navCourseBtn;
    @FXML private Button navStoreBtn;
    @FXML private Button navBankBtn;
    @FXML private Button navAiBtn;
    @FXML private Button userNavBtn;
    @FXML private Button permissionNavBtn;
    @FXML private Button navLogoutBtn;
    @FXML private Button navChatBtn;
    @FXML private Label navChatBadge;
    private ChatEntry chatEntry;

    // ===== 课表卡片 (管理员隐藏，学生/教师动态载入对应课表) =====
    @FXML private VBox scheduleCard;
    @FXML private Label scheduleTermSubtitle;
    @FXML private StackPane scheduleContainer;
    @FXML private Label courseCardTitle;

    private Node studentSchedulePage;
    private ScheduleController studentSchedulePageController;
    private Node teacherSchedulePage;
    private TeacherScheduleController teacherSchedulePageController;
    private String loadedScheduleRole;

    // ===== 学生“学业与成绩概况”卡片 =====
    @FXML private VBox studentAcademicSummaryCard;
    @FXML private Label termGpaLabel;
    @FXML private Label termAvgLabel;
    @FXML private Label cumulativeGpaLabel;
    @FXML private Label cumulativeAvgLabel;
    @FXML private Label earnedCreditsLabel;
    @FXML private Label requiredCreditsLabel;
    @FXML private ProgressBar creditProgressBar;
    @FXML private Label creditPercentLabel;

    // ===== 管理员“我的权限”卡片 =====
    @FXML private VBox adminPermissionCard;
    @FXML private Label myPermTagLabel;
    @FXML private Label permStatusAcademic;
    @FXML private Label permStatusLibrary;
    @FXML private Label permStatusCourse;
    @FXML private Label permStatusShop;
    @FXML private Label permStatusBank;
    @FXML private Label permStatusUser;
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
    @FXML private Label noticeBadgeOne;
    @FXML private Label noticeBadgeTwo;
    @FXML private Label noticeBadgeThree;
    @FXML private HBox noticeItemOne;
    @FXML private HBox noticeItemTwo;
    @FXML private HBox noticeItemThree;
    @FXML private Label chatNoticeLabel;
    @FXML private StackPane noticeEmptyPane;
    @FXML private Hyperlink studentReviewNoticeLink;
    @FXML private Hyperlink teacherReviewNoticeLink;
    @FXML private Hyperlink libraryTaskNoticeLink;
    @FXML private Hyperlink shopTaskNoticeLink;
    @FXML private Hyperlink libraryNoticeLink;
    @FXML private Label noticeUnreadCountLabel;
    @FXML private Hyperlink markAllReadLink;
    @FXML private Hyperlink openNoticeCenterLink;

    private long noticeOneCount = Long.MIN_VALUE;
    private long noticeTwoCount = Long.MIN_VALUE;
    private long chatNoticeCount = Long.MIN_VALUE;
    private final NotificationClientService notificationService = new NotificationClientService();

    // ===== 一卡通金额卡片 =====
    @FXML private Label walletBalanceLabel;
    @FXML private Label walletAccountLabel;

    @FXML
    public void initialize() {
        instance = this;
        if (navChatBtn != null) chatEntry = new ChatEntry(navChatBtn,this::openChat,this::updateChatSummary);

        // 1. 读取并显示当前用户本地 Session 数据
        loadUserData();

        // 2. 异步向服务端查询最新用户信息（同步最新学籍与余额）
        fetchLatestUserInfo();

        // 3. 管理员查看信息审核待办，其他用户查看图书馆消息
        loadNotices();
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

        // 侧边栏：第一行学院，第二行专业/职务
        if (sidebarNameLabel != null) sidebarNameLabel.setText(name != null ? name : "用户");
        String college = (user != null && user.getCollege() != null && !user.getCollege().isBlank())
                ? user.getCollege() : "—";
        String major = (user != null && user.getMajor() != null && !user.getMajor().isBlank())
                ? user.getMajor() : roleStr;
        if (sidebarCollegeLabel != null) {
            sidebarCollegeLabel.setText(college);
        }
        if (sidebarMajorLabel != null) {
            sidebarMajorLabel.setText(major);
        }
        if (sidebarRoleLabel != null) {
            sidebarRoleLabel.setText(college.equals("—") ? roleStr : (college + " · " + major));
        }
        if (sidebarAvatarView != null) {
            showAvatar(sidebarAvatarView, user != null ? user.getAvatar() : null);
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
        if (courseCardTitle != null) {
            courseCardTitle.setText(courseCardTitleText(roleStr));
        }

        if (scheduleContainer != null) {
            if (isAdmin) {
                scheduleContainer.getChildren().clear();
                loadedScheduleRole = null;
                studentSchedulePage = null;
                studentSchedulePageController = null;
                teacherSchedulePage = null;
                teacherSchedulePageController = null;
            } else if (isTeacher) {
                if (scheduleTermSubtitle != null) {
                    scheduleTermSubtitle.setText("教学工作课表");
                }
                if (!"TEACHER".equals(loadedScheduleRole)) {
                    try {
                        FXMLLoader loader = new FXMLLoader(getClass().getResource("/resources/fxml/TeacherScheduleView.fxml"));
                        teacherSchedulePage = loader.load();
                        teacherSchedulePageController = loader.getController();
                        scheduleContainer.getChildren().setAll(teacherSchedulePage);
                        loadedScheduleRole = "TEACHER";
                        studentSchedulePage = null;
                        studentSchedulePageController = null;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                if (teacherSchedulePageController != null) {
                    teacherSchedulePageController.activate();
                }
            } else {
                // 学生
                if (!"STUDENT".equals(loadedScheduleRole)) {
                    try {
                        FXMLLoader loader = new FXMLLoader(getClass().getResource("/resources/fxml/ScheduleView.fxml"));
                        studentSchedulePage = loader.load();
                        studentSchedulePageController = loader.getController();
                        scheduleContainer.getChildren().setAll(studentSchedulePage);
                        loadedScheduleRole = "STUDENT";
                        teacherSchedulePage = null;
                        teacherSchedulePageController = null;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else if (studentSchedulePageController != null) {
                    studentSchedulePageController.refresh();
                }
            }
        }

        if (studentAcademicSummaryCard != null) {
            boolean showAcademicSummary = !isAdmin && !isTeacher;
            studentAcademicSummaryCard.setVisible(showAcademicSummary);
            studentAcademicSummaryCard.setManaged(showAcademicSummary);
            if (showAcademicSummary) {
                loadStudentAcademicSummary();
            }
        }
        if (permissionNavBtn != null) {
            permissionNavBtn.setVisible(isSuperAdmin);
            permissionNavBtn.setManaged(isSuperAdmin);
        }
        if (userNavBtn != null) {
            userNavBtn.setVisible(isAdmin);
            userNavBtn.setManaged(isAdmin);
        }
        if (navStudentBtn != null) {
            navStudentBtn.setText(isAdmin
                    ? "🎓   信息管理"
                    : isTeacher ? "🎓   教职信息" : "🎓   学籍信息");
        }
        if (navCourseBtn != null) {
            navCourseBtn.setText("📝   " + courseCardTitleText(roleStr));
        }
        if (adminPermissionCard != null) {
            adminPermissionCard.setVisible(isAdmin);
            adminPermissionCard.setManaged(isAdmin);
        }
        if (studentAcademicCard != null) {
            studentAcademicCard.setVisible(!isAdmin);
            studentAcademicCard.setManaged(!isAdmin);
            if (academicCardTitle != null) {
                academicCardTitle.setText(isTeacher ? "🎓  教职信息" : "🎓  学籍信息");
            }
            if (academicStatusKeyLabel != null) {
                academicStatusKeyLabel.setText(isTeacher ? "在任状态" : "学籍状态");
            }
        }

        if (isAdmin) {
            updateMyPermissionDisplay();
            fetchMyPermissions();
        } else {
            fetchAcademicInfo(isTeacher);
        }
    }

    /**
     * 异步加载学生“学业与成绩概况”（本学期/累计 绩点与均分、培养方案学分进度）
     */
    private void loadStudentAcademicSummary() {
        CourseService courseService = CourseServices.current();
        courseService.loadTerms().thenAccept(terms -> {
            if (terms != null && !terms.isEmpty()) {
                CourseTermView currentTerm = terms.get(0);
                if (scheduleTermSubtitle != null && currentTerm.getDisplayName() != null) {
                    Platform.runLater(() -> scheduleTermSubtitle.setText(currentTerm.getDisplayName()));
                }
                courseService.loadGrades(currentTerm).thenAccept(summary -> {
                    if (summary != null) {
                        Platform.runLater(() -> {
                            if (termGpaLabel != null) termGpaLabel.setText(formatMetric(summary.getTermGpa()));
                            if (termAvgLabel != null) termAvgLabel.setText(formatMetric(summary.getTermAverage()));
                            if (cumulativeGpaLabel != null) cumulativeGpaLabel.setText(formatMetric(summary.getCumulativeGpa()));
                            if (cumulativeAvgLabel != null) cumulativeAvgLabel.setText(formatMetric(summary.getCumulativeAverage()));
                        });
                    }
                }).exceptionally(e -> null);
            }
        }).exceptionally(e -> null);

        courseService.loadTrainingPlan().thenAccept(groups -> {
            if (groups != null && !groups.isEmpty()) {
                TrainingPlanController.PlanTotals totals = TrainingPlanController.aggregate(groups);
                double earned = totals.getEarnedCredits();
                double required = totals.getRequiredCredits();
                double progress = totals.getProgress();
                Platform.runLater(() -> {
                    if (earnedCreditsLabel != null) earnedCreditsLabel.setText(String.format(Locale.ROOT, "%.1f", earned));
                    if (requiredCreditsLabel != null) requiredCreditsLabel.setText(String.format(Locale.ROOT, "%.1f", required));
                    if (creditProgressBar != null) creditProgressBar.setProgress(progress);
                    if (creditPercentLabel != null) creditPercentLabel.setText(String.format(Locale.ROOT, "(%.1f%%)", progress * 100.0));
                });
            }
        }).exceptionally(e -> null);
    }

    static String formatMetric(Double value) {
        if (value == null || Double.isNaN(value)) return "--";
        return String.format(Locale.ROOT, "%.2f", value);
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
    private void loadNotices() {
        refreshNotificationUnreadCount();
        if (isAdminUser()) loadInformationReviewNotices();
        else loadLibraryNotices();
    }

    private void refreshNotificationUnreadCount() {
        notificationService.unreadCount().thenAccept(count -> Platform.runLater(() -> {
            boolean hasUnread = count != null && count > 0;
            if (noticeUnreadCountLabel != null) noticeUnreadCountLabel.setText(String.valueOf(count == null ? 0 : count));
            setManagedVisible(noticeUnreadCountLabel, hasUnread);
            setManagedVisible(markAllReadLink, hasUnread);
        })).exceptionally(error -> {
            Platform.runLater(() -> {
                setManagedVisible(noticeUnreadCountLabel, false);
                setManagedVisible(markAllReadLink, false);
            });
            return null;
        });
    }

    @FXML
    private void handleMarkAllNotificationsRead() {
        notificationService.markAllRead().thenRun(() -> Platform.runLater(this::refreshNotificationUnreadCount))
                .exceptionally(error -> {
                    Platform.runLater(() -> warningReporter.accept("消息通知", "标记全部已读失败，请稍后重试"));
                    return null;
                });
    }

    @FXML
    private void openNotificationCenter(ActionEvent event) {
        javafx.stage.Window owner = rootMain == null || rootMain.getScene() == null
                ? null : rootMain.getScene().getWindow();
        new NotificationCenterDialog(this::navigateNotificationAction, this::refreshNotificationUnreadCount).show(owner);
    }

    private void navigateNotificationAction(String action) {
        if (action == null) return;
        switch (action.toUpperCase(Locale.ROOT)) {
            case "BANK" -> openBank(null);
            case "CHAT" -> openChat();
            case "STUDENT_STATUS" -> loadCenterView("/resources/fxml/StudentView.fxml");
            case "TEACHER_STATUS" -> loadCenterView("/resources/fxml/TeacherView.fxml");
            case "LIBRARY" -> openLibrary(null);
            case "SHOP" -> openStore(null);
            default -> showHome();
        }
    }

    private void loadInformationReviewNotices() {
        resetNoticeState();
        if (noticeBadgeOne != null) noticeBadgeOne.setText("学生信息");
        if (noticeBadgeTwo != null) noticeBadgeTwo.setText("教师信息");
        setManagedVisible(noticeItemThree, chatNoticeCount != 0 && chatNoticeCount != Long.MIN_VALUE);
        setManagedVisible(studentReviewNoticeLink, true);
        setManagedVisible(teacherReviewNoticeLink, true);
        setManagedVisible(libraryTaskNoticeLink, false);
        setManagedVisible(shopTaskNoticeLink, false);
        setManagedVisible(libraryNoticeLink, false);
        if (libraryBorrowNoticeLabel != null) libraryBorrowNoticeLabel.setText("正在读取学生信息审核待办...");
        if (libraryReservationNoticeLabel != null) libraryReservationNoticeLabel.setText("正在读取教师信息审核待办...");

        Message studentRequest = new Message(MessageType.STUDENT_REVIEW_LIST, "student", "listPendingRequests");
        SocketClient.getInstance().sendAsync(studentRequest).thenAccept(response -> Platform.runLater(() -> {
            long count = pendingReviewCount(response);
            updateReviewNotice(noticeItemOne, libraryBorrowNoticeLabel, count, "学生");
        })).exceptionally(error -> {
            Platform.runLater(() -> updateReviewNotice(
                    noticeItemOne, libraryBorrowNoticeLabel, -1, "学生"));
            return null;
        });

        Message teacherRequest = new Message(MessageType.TEACHER_REVIEW_LIST, "teacher", "reviews");
        SocketClient.getInstance().sendAsync(teacherRequest).thenAccept(response -> Platform.runLater(() -> {
            long count = pendingReviewCount(response);
            updateReviewNotice(noticeItemTwo, libraryReservationNoticeLabel, count, "教师");
        })).exceptionally(error -> {
            Platform.runLater(() -> updateReviewNotice(
                    noticeItemTwo, libraryReservationNoticeLabel, -1, "教师"));
            return null;
        });
    }

    private void updateReviewNotice(HBox item, Label label, long count, String applicantType) {
        setManagedVisible(item, count != 0);
        recordNoticeCount(item, count);
        if (label == null || count == 0) return;
        label.setText(count < 0
                ? applicantType + "信息审核待办加载失败"
                : "你有 " + count + " 条" + applicantType + "信息待审核");
    }

    private long pendingReviewCount(Message response) {
        if (response == null || response.getCode() != MessageCode.SUCCESS) return -1;
        Object requests = response.getData("requests");
        if (requests == null) return 0;
        try {
            long count = 0;
            for (var item : new Gson().toJsonTree(requests).getAsJsonArray()) {
                if (item.isJsonObject() && "PENDING".equalsIgnoreCase(
                        item.getAsJsonObject().has("status") ? item.getAsJsonObject().get("status").getAsString() : "")) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException error) {
            return -1;
        }
    }

    private static void setManagedVisible(Node node, boolean value) {
        if (node == null) return;
        node.setManaged(value);
        node.setVisible(value);
    }

    private void loadLibraryNotices() {
        resetNoticeState();
        if (noticeBadgeOne != null) noticeBadgeOne.setText("图书馆");
        if (noticeBadgeTwo != null) noticeBadgeTwo.setText("商店");
        setManagedVisible(noticeItemThree, chatNoticeCount != 0 && chatNoticeCount != Long.MIN_VALUE);
        setManagedVisible(studentReviewNoticeLink, false);
        setManagedVisible(teacherReviewNoticeLink, false);
        setManagedVisible(libraryTaskNoticeLink, true);
        setManagedVisible(shopTaskNoticeLink, true);
        setManagedVisible(libraryNoticeLink, false);

        if (libraryBorrowNoticeLabel != null) libraryBorrowNoticeLabel.setText("正在读取未归还图书...");
        if (libraryReservationNoticeLabel != null) libraryReservationNoticeLabel.setText("正在读取未支付订单...");

        // 1. 合并尚未归还的图书和仍在有效期内的预约，形成一条可处理的图书馆通知。
        LibraryClientService.getInstance().getCurrentBorrow()
                .thenCombine(LibraryClientService.getInstance().getReservations(), (borrows, reservations) -> {
                    long borrowed = borrows == null ? 0 : borrows.size();
                    long reserved = reservations == null ? 0 : reservations.stream()
                            .filter(reservation -> reservation != null
                                    && reservation.getStatus() == ReservationStatus.RESERVING.getCode())
                            .count();
                    return new long[]{borrowed, reserved};
                }).thenAccept(counts -> Platform.runLater(() -> {
                    long borrowed = counts[0], reserved = counts[1], total = borrowed + reserved;
                    setManagedVisible(noticeItemOne, total > 0);
                    recordNoticeCount(noticeItemOne, total);
                    if (libraryBorrowNoticeLabel == null || total == 0) return;
                    if (borrowed > 0 && reserved > 0) {
                        libraryBorrowNoticeLabel.setText("你有 " + borrowed + " 本图书未归还，"
                                + reserved + " 条预约待到馆办理");
                    } else if (reserved > 0) {
                        libraryBorrowNoticeLabel.setText("你有 " + reserved
                                + " 条图书预约待到馆办理（预约后 12 小时内）");
                    } else {
                        libraryBorrowNoticeLabel.setText("你有 " + borrowed + " 本图书未归还");
                    }
                })).exceptionally(e -> {
            Platform.runLater(() -> updateUserTaskNotice(
                    noticeItemOne, libraryBorrowNoticeLabel, -1, "", "图书馆"));
            return null;
        });

        // 2. 查询商店待支付订单。
        Message orderRequest = new Message(MessageType.REQUEST, "shop", MessageType.SHOP_ORDER_LIST.name());
        SocketClient.getInstance().sendAsync(orderRequest).thenAccept(response -> Platform.runLater(() -> {
            if (response == null || response.getCode() != MessageCode.SUCCESS) {
                updateUserTaskNotice(noticeItemTwo, libraryReservationNoticeLabel, -1, "", "商店");
                return;
            }
            ShopOrder[] orders = new Gson().fromJson(
                    new Gson().toJson((Object) response.getData("orders")), ShopOrder[].class);
            long unpaid = orders == null ? 0 : java.util.Arrays.stream(orders)
                    .filter(order -> order != null && order.getStatus() == OrderStatus.WAIT_PAY)
                    .count();
            updateUserTaskNotice(noticeItemTwo, libraryReservationNoticeLabel, unpaid,
                    "个订单未支付", "商店");
        })).exceptionally(error -> {
            Platform.runLater(() -> updateUserTaskNotice(
                    noticeItemTwo, libraryReservationNoticeLabel, -1, "", "商店"));
            return null;
        });
    }

    private void updateUserTaskNotice(HBox item, Label label, long count, String unitText, String module) {
        setManagedVisible(item, count != 0);
        recordNoticeCount(item, count);
        if (label == null || count == 0) return;
        label.setText(count < 0
                ? module + "消息加载失败"
                : "你有 " + count + " " + unitText);
    }

    private void resetNoticeState() {
        noticeOneCount = Long.MIN_VALUE;
        noticeTwoCount = Long.MIN_VALUE;
        setManagedVisible(noticeEmptyPane, false);
        setManagedVisible(noticeItemOne, true);
        setManagedVisible(noticeItemTwo, true);
    }

    private void updateChatSummary(Integer unreadValue, Integer pendingValue) {
        int unread = unreadValue == null ? -1 : unreadValue;
        int pending = pendingValue == null ? -1 : pendingValue;
        if (unread < 0 || pending < 0) {
            chatNoticeCount = -1;
            setManagedVisible(navChatBadge, false);
            setManagedVisible(noticeItemThree, true);
            if (chatNoticeLabel != null) chatNoticeLabel.setText("聊天消息加载失败");
            updateNoticeEmptyState();
            return;
        }

        int total = unread + pending;
        chatNoticeCount = total;
        if (navChatBadge != null) navChatBadge.setText(total > 99 ? "99+" : String.valueOf(total));
        setManagedVisible(navChatBadge, total > 0);
        setManagedVisible(noticeItemThree, total > 0);
        if (chatNoticeLabel != null && total > 0) {
            if (unread > 0 && pending > 0) {
                chatNoticeLabel.setText("你有 " + unread + " 条未读聊天消息，" + pending + " 条好友申请待处理");
            } else if (pending > 0) {
                chatNoticeLabel.setText("你有 " + pending + " 条好友申请待处理");
            } else {
                chatNoticeLabel.setText("你有 " + unread + " 条未读聊天消息");
            }
        }
        updateNoticeEmptyState();
    }

    private void recordNoticeCount(HBox item, long count) {
        if (item == noticeItemOne) noticeOneCount = count;
        if (item == noticeItemTwo) noticeTwoCount = count;
        updateNoticeEmptyState();
    }

    private void updateNoticeEmptyState() {
        boolean loaded = noticeOneCount != Long.MIN_VALUE
                && noticeTwoCount != Long.MIN_VALUE
                && chatNoticeCount != Long.MIN_VALUE;
        setManagedVisible(noticeEmptyPane, loaded
                && noticeOneCount == 0 && noticeTwoCount == 0 && chatNoticeCount == 0);
    }

    private void showAvatar(ImageView view, String base64) {
        if (view == null) return;
        if (base64 == null || base64.isBlank()) {
            view.setImage(null);
            view.setClip(null);
            return;
        }
        try {
            Image img = new Image(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            view.setImage(img);
            double w = view.getFitWidth();
            double h = view.getFitHeight();
            if (w <= 0 || h <= 0) {
                if (view.getParent() instanceof javafx.scene.layout.Region reg && reg.getPrefWidth() > 0 && reg.getPrefHeight() > 0) {
                    w = reg.getPrefWidth();
                    h = reg.getPrefHeight();
                } else {
                    w = 54;
                    h = 54;
                }
            }
            double r = Math.min(w, h) / 2.0;
            view.setClip(new Circle(w / 2.0, h / 2.0, r));
            view.toFront();
        } catch (Exception e) {
            view.setImage(null);
            view.setClip(null);
        }
    }

    // ===== SPA 单页容器路由与视图管理 =====

    /**
     * 判断当前 MainView 是否在主场景中呈现
     */
    public boolean isAttachedToScene() {
        return rootMain != null
                && rootMain.getScene() != null
                && ClientMain.getPrimaryStage() != null
                && ClientMain.getPrimaryStage().getScene() == rootMain.getScene()
                && rootMain.getScene().getRoot() == rootMain;
    }

    /**
     * 恢复右侧主界面内容为首页看板
     */
    public void showHome() {
        PageLeaveGuard previousGuard = PageLeaveGuard.active();
        if (previousGuard != null && !previousGuard.requestLeave()) return;
        if (previousGuard != null) previousGuard.onClosed();
        PageLeaveGuard.clear(previousGuard);
        ClientMain.cleanupPage();
        if (homeScrollPane != null && rootMain != null) {
            rootMain.setCenter(homeScrollPane);
        }
        updateActiveNavButton(navHomeBtn);
        loadUserData();
        fetchLatestUserInfo();
        loadNotices();
    }

    /**
     * 动态将子系统视图载入至右侧 center 区域
     */
    public void loadCenterView(String fxmlPath) {
        PageLeaveGuard previousGuard = PageLeaveGuard.active();
        if (previousGuard != null && !previousGuard.requestLeave()) return;
        try {
            // 必须先释放旧页：FXMLUtil.load 会先跑新页 initialize()，它会顶替清理器并看到旧守卫
            if (previousGuard != null) previousGuard.onClosed();
            PageLeaveGuard.clear(previousGuard);
            ClientMain.cleanupPage();
            Parent view = FXMLUtil.load(fxmlPath);
            if (rootMain != null) {
                rootMain.setCenter(view);
            }
            PageLeaveGuard.registerFrom(FXMLUtil.loadedController());
            updateActiveNavButton(mapFxmlToNavButton(fxmlPath));
        } catch (Exception e) {
            e.printStackTrace();
            AlertUtil.showError("界面加载失败", "无法加载模块界面: " + fxmlPath + "\n错误详情: " + e.getMessage());
        }
    }

    private Button mapFxmlToNavButton(String fxmlPath) {
        if (fxmlPath == null) return navHomeBtn;
        if (fxmlPath.contains("ProfileView")) return navProfileBtn;
        if (fxmlPath.contains("StudentView") || fxmlPath.contains("TeacherView") || fxmlPath.contains("InformationSelectView")) {
            return navStudentBtn;
        }
        if (fxmlPath.contains("LibraryView")) return navLibraryBtn;
        if (fxmlPath.contains("CourseManagementView")) return navCourseBtn;
        if (fxmlPath.contains("ShopView")) return navStoreBtn;
        if (fxmlPath.contains("BankView")) return navBankBtn;
        if (fxmlPath.contains("AIview")) return navAiBtn;
        if (fxmlPath.contains("UserView")) return userNavBtn;
        if (fxmlPath.contains("PermissionView")) return permissionNavBtn;
        return navHomeBtn;
    }

    private void updateActiveNavButton(Button activeBtn) {
        Button[] buttons = {
                navHomeBtn, navProfileBtn, navStudentBtn, navLibraryBtn,
                navCourseBtn, navStoreBtn, navBankBtn, navAiBtn,
                userNavBtn, permissionNavBtn, navChatBtn
        };
        for (Button btn : buttons) {
            if (btn != null) {
                btn.getStyleClass().remove("main-nav-btn-active");
                if (btn == activeBtn) {
                    if (!btn.getStyleClass().contains("main-nav-btn-active")) {
                        btn.getStyleClass().add("main-nav-btn-active");
                    }
                }
            }
        }
        if (navCourseBtn != null) {
            navCourseBtn.setText("📝   " + courseCardTitleText(ClientSession.getInstance().getRole()));
        }
    }

    /**
     * 教务入口卡片标题：管理员与教师进入教务管理，学生及其他角色保持选课。
     */
    static String courseCardTitleText(String role) {
        if (role == null) return "选课";
        if ("管理员".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role)
                || "教师".equalsIgnoreCase(role) || "TEACHER".equalsIgnoreCase(role)) {
            return "教务管理";
        }
        return "选课";
    }

    void setSceneSwitcher(SceneSwitcher switcher) {
        this.sceneSwitcher = Objects.requireNonNull(switcher, "Scene switcher is required");
    }

    void setInfoReporter(BiConsumer<String, String> reporter) {
        this.infoReporter = Objects.requireNonNull(reporter, "Info reporter is required");
    }

    void setWarningReporter(BiConsumer<String, String> reporter) {
        this.warningReporter = Objects.requireNonNull(reporter, "Warning reporter is required");
    }

    // ===== 页面导航动作 =====
    private ChatPane chatPane;
    public void openChatGroup(long groupId) {
        openChat();
        if(chatPane!=null && rootMain.getCenter()==chatPane.getView())chatPane.openGroup(groupId);
    }

    public void openChat() {
        if (chatPane != null && rootMain.getCenter() == chatPane.getView()) return;
        PageLeaveGuard previousGuard=PageLeaveGuard.active();
        if(previousGuard!=null&&!previousGuard.requestLeave())return;
        if(previousGuard!=null)previousGuard.onClosed();
        PageLeaveGuard.clear(previousGuard);
        ClientMain.cleanupPage();
        ChatPane page=new ChatPane();chatPane=page;
        rootMain.setCenter(page.getView());
        ClientMain.setPageCleanup(()->{page.close();if(chatPane==page)chatPane=null;});
        updateActiveNavButton(navChatBtn);
        page.start();
    }

    @FXML
    public void handleNavigateHome(ActionEvent event) {
        if (rootMain != null && rootMain.getCenter() != homeScrollPane) {
            showHome();
        } else {
            // 当前已在主页，重新刷新数据
            loadUserData();
            fetchLatestUserInfo();
            loadNotices();
        }
    }

    @FXML
    public void handleNavigateProfile(ActionEvent event) {
        loadCenterView("/resources/fxml/ProfileView.fxml");
    }

    @FXML
    public void openStudentAffairs(ActionEvent event) {
        String role = ClientSession.getInstance().getRole();
        if ("TEACHER".equalsIgnoreCase(role) || "教师".equals(role)) {
            loadCenterView("/resources/fxml/TeacherView.fxml");
        } else if ("ADMIN".equalsIgnoreCase(role) || "管理员".equals(role)) {
            if (!ClientSession.getInstance().hasAcademicPermission()) {
                AlertUtil.showWarning("权限不足", "您没有该模块的管理权限");
                return;
            }
            loadCenterView("/resources/fxml/InformationSelectView.fxml");
        } else {
            loadCenterView("/resources/fxml/StudentView.fxml");
        }
    }

    @FXML
    public void openStudentReviewNotifications(ActionEvent event) {
        loadCenterView("/resources/fxml/StudentView.fxml");
        Object controller = FXMLUtil.loadedController();
        if (controller instanceof StudentController studentController) {
            studentController.openReviewFromDashboard();
        }
    }

    @FXML
    public void openTeacherReviewNotifications(ActionEvent event) {
        loadCenterView("/resources/fxml/TeacherView.fxml");
        Object controller = FXMLUtil.loadedController();
        if (controller instanceof TeacherController teacherController) {
            teacherController.openReviewFromDashboard();
        }
    }

    @FXML
    public void openLibrary(ActionEvent event) {
        if (isAdminUser() && !ClientSession.getInstance().hasLibraryPermission()) {
            warningReporter.accept("权限不足", "您没有该模块的管理权限");
            return;
        }
        loadCenterView("/resources/fxml/LibraryView.fxml");
        if (event != null && event.getSource() == libraryTaskNoticeLink
                && FXMLUtil.loadedController() instanceof LibraryController controller) {
            controller.openMyLibraryFromDashboard();
        }
    }

    @FXML
    public void openCourseSelection(ActionEvent event) {
        String role = ClientSession.getInstance().getRole();
        if (isAdminUser()) {
            if (!ClientSession.getInstance().hasCoursePermission()) {
                warningReporter.accept("权限不足", "您没有该模块的管理权限");
                return;
            }
            sceneSwitcher.switchTo(ADMIN_COURSE_VIEW);
        } else if ("STUDENT".equalsIgnoreCase(role) || "学生".equals(role)) {
            sceneSwitcher.switchTo(STUDENT_COURSE_VIEW);
        } else if ("TEACHER".equalsIgnoreCase(role) || "教师".equals(role)) {
            sceneSwitcher.switchTo(TEACHER_COURSE_VIEW);
        } else {
            infoReporter.accept(COURSE_NOTICE_TITLE, COURSE_NOTICE_MESSAGE);
        }
    }

    @FXML
    public void openStore(ActionEvent event) {
        if (isAdminUser()) {
            if (!ClientSession.getInstance().hasShopPermission()) {
                AlertUtil.showWarning("权限不足", "您没有该模块的管理权限");
                return;
            }
        }
        loadCenterView("/resources/fxml/ShopView.fxml");
    }

    @FXML
    public void openBank(ActionEvent event) {
        if (isAdminUser()) {
            if (!ClientSession.getInstance().hasBankPermission()) {
                AlertUtil.showWarning("权限不足", "您没有该模块的管理权限");
                return;
            }
        }
        loadCenterView("/resources/fxml/BankView.fxml");
    }

    @FXML
    public void handleNavigatePermission(ActionEvent event) {
        loadCenterView("/resources/fxml/PermissionView.fxml");
    }

    @FXML
    public void handleNavigateUserManage(ActionEvent event) {
        if (!ClientSession.getInstance().hasUserPermission()) {
            AlertUtil.showWarning("权限不足", "您没有用户管理权限");
            return;
        }
        loadCenterView("/resources/fxml/UserView.fxml");
    }

    @FXML
    public void openAI(ActionEvent event) {
        loadCenterView("/resources/fxml/AIview.fxml");
    }

    @FXML
    public void openHospital(ActionEvent event) {
        openAI(event);
    }

    @FXML
    public void handleLogout(ActionEvent event) {
        PageLeaveGuard guard = PageLeaveGuard.active();
        if (guard != null && !guard.requestLeave()) return;
        if (guard != null) guard.onClosed();
        PageLeaveGuard.clear(guard);
        ClientMain.cleanupPage();

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

    public void fetchMyPermissions() {
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
    public void updateMyPermissionDisplay() {
        if (adminPermissionCard == null) {
            return;
        }
        boolean isAdmin = isAdminUser();
        adminPermissionCard.setVisible(isAdmin);
        adminPermissionCard.setManaged(isAdmin);
        if (!isAdmin) {
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
        boolean userPerm = session.hasUserPermission();

        // 第二行各个模块的只读状态指示
        setPermBadge(permStatusAcademic, academic);
        setPermBadge(permStatusLibrary, library);
        setPermBadge(permStatusCourse, course);
        setPermBadge(permStatusShop, shop);
        setPermBadge(permStatusBank, bank);
        setPermBadge(permStatusUser, userPerm);

        // 文字总结
        List<String> authorized = new java.util.ArrayList<>();
        if (academic) authorized.add("学籍信息");
        if (library) authorized.add("图书馆");
        if (course) authorized.add("选课");
        if (shop) authorized.add("商店");
        if (bank) authorized.add("银行");
        if (userPerm) authorized.add("用户管理");

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
            if (academicCardTitle != null) academicCardTitle.setText("🎓  教职信息");
            if (academicStatusKeyLabel != null) academicStatusKeyLabel.setText("在任状态");
            Message request = new Message(MessageType.TEACHER_OVERVIEW_QUERY, "teacher", "overview");
            SocketClient.getInstance().sendAsync(request).thenAccept(response -> {
                if (response.getCode() == MessageCode.SUCCESS) {
                    Object obj = response.getData("overview");
                    if (obj != null) {
                        Gson gson = new Gson();
                        TeacherOverviewVO vo = gson.fromJson(gson.toJson(obj), TeacherOverviewVO.class);
                        if (vo != null && vo.getLatestRequest() != null) {
                            showRejectedRequestNotice(
                                    "teacher",
                                    vo.getLatestRequest().getRequestId(),
                                    vo.getLatestRequest().getStatus());
                        }
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
                        if (vo != null && vo.getLatestRequest() != null) {
                            showRejectedRequestNotice(
                                    "student",
                                    vo.getLatestRequest().getRequestId(),
                                    vo.getLatestRequest().getStatus());
                        }
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

    private void showRejectedRequestNotice(String applicantType, Long requestId, StudentChangeStatus status) {
        if (status != StudentChangeStatus.REJECTED) return;

        String token = ClientSession.getInstance().getToken();
        if (token == null || token.isBlank()) return;

        String username = ClientSession.getInstance().getUsername();
        if (username == null || username.isBlank()) return;

        String noticeKey = "rejected." + applicantType + "." + username + "." + String.valueOf(requestId);
        if (NOTICE_PREFERENCES.getBoolean(noticeKey, false)) return;
        if (!SHOWN_REJECTED_REQUESTS.add(noticeKey)) return;

        Platform.runLater(() -> {
            if (Objects.equals(token, ClientSession.getInstance().getToken())) {
                NOTICE_PREFERENCES.putBoolean(noticeKey, true);
                warningReporter.accept("审核信息提醒", "审核信息被退回，请重新修改");
            } else {
                SHOWN_REJECTED_REQUESTS.remove(noticeKey);
            }
        });
    }

    private String strOrDefault(String val) {
        return (val != null && !val.isBlank()) ? val : "—";
    }

    private String strOrDefault(String val, String defaultVal) {
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }
}
