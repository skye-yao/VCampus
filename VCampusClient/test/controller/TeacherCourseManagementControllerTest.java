package controller;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import service.MockTeacherCourseService;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 无 JavaFX 工具包依赖的教师工作台外壳测试。
 *
 * <p>不启动 toolkit：外壳契约靠注入的服务、回退动作与 {@code null} 节点断言；视图契约改为用
 * {@link DocumentBuilderFactory} 结构化解析 FXML，而不是匹配原始文本——原始文本断言会被注释或
 * 被注释掉的元素块骗过（既能造成假红，也能造成假绿），且只有结构化检查才能在真实元素上确认
 * {@code disable} 这类属性存在。静态检查仍无法证明任意属性名是可写属性，那只有真正的
 * {@code FXMLLoader} 加载能证明，属于 T6 的 {@code ui.*} 冒烟范围。
 */
public final class TeacherCourseManagementControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherCourseManagementView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";
    private static final String STAGING_NOTICE = "该功能将在后续阶段接入";
    private static final String HOME_VIEW = "/resources/fxml/MainView.fxml";
    private static final String LONG_OFFERING_ID = "9007199254740993";
    private static final String APP_STYLESHEET = "@../css/style.css";
    private static final String VIEW_STYLESHEET = "@../css/teacher-course.css";
    private static final List<String> ENTRIES =
            List.of("教学课程表", "教学班", "成绩录入", "我的申请");

    private TeacherCourseManagementControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        Document view = parseView();

        defaultConstructorUsesTheSharedTeacherService();
        injectedServiceIsRetainedForLaterSubpages();
        returningHomeTargetsTheMainView();
        returningHomeRunsTheInjectedActionSafely();
        stagedEntriesAreSafeWithoutNodes();

        viewIsTheTeacherWorkspaceShell(view);
        stagedEntriesAreRealDisabledElements(view);
        noElementUsesTheReadOnlyDisabledAttribute(view);
        everyFxIdAndOnActionResolvesOnTheController(view);
        everyStyleClassExistsInTheStylesheet(view, readResource(CSS));
        System.out.println("TeacherCourseManagementControllerTest: PASS");
    }

    private static void defaultConstructorUsesTheSharedTeacherService() {
        TeacherCourseService previous = TeacherCourseServices.current();
        MockTeacherCourseService mock = new MockTeacherCourseService();
        TeacherCourseServices.install(mock);
        try {
            TeacherCourseManagementController controller = new TeacherCourseManagementController();
            require(controller.service() == mock,
                    "the default constructor must take the shared teacher service");
        } finally {
            TeacherCourseServices.install(previous);
        }
    }

    private static void injectedServiceIsRetainedForLaterSubpages() {
        MockTeacherCourseService mock = new MockTeacherCourseService();
        TeacherCourseManagementController controller = new TeacherCourseManagementController(mock);
        require(controller.service() == mock,
                "the injected teacher service must be retained for later subpages");
    }

    private static void returningHomeTargetsTheMainView() {
        require(HOME_VIEW.equals(TeacherCourseManagementController.HOME_VIEW),
                "the workspace must return to the main view, saw "
                        + TeacherCourseManagementController.HOME_VIEW);
    }

    private static void returningHomeRunsTheInjectedActionSafely() {
        TeacherCourseManagementController controller = controller();
        List<Integer> backs = new ArrayList<>();
        controller.setBackAction(() -> backs.add(backs.size()));
        controller.handleBack();
        controller.handleBack();
        require(backs.size() == 2, "every 返回首页 must run the back action, saw " + backs);

        controller.setBackAction(null);
        controller.handleBack();
    }

    private static void stagedEntriesAreSafeWithoutNodes() {
        TeacherCourseManagementController controller = controller();
        controller.initialize();
        controller.openOfferings();
        controller.openTimetable();
        controller.openGrades(LONG_OFFERING_ID);
        controller.openGrades(null);
        controller.openApplications();
    }

    private static void viewIsTheTeacherWorkspaceShell(Document view) {
        Element root = view.getDocumentElement();
        require("BorderPane".equals(root.getTagName()),
                "the workspace root must be a BorderPane, saw " + root.getTagName());
        require("controller.TeacherCourseManagementController"
                        .equals(root.getAttribute("fx:controller")),
                "the view must be controlled by TeacherCourseManagementController, saw "
                        + root.getAttribute("fx:controller"));
        String stylesheets = root.getAttribute("stylesheets");
        require(stylesheets.contains(APP_STYLESHEET),
                "the view must link the app-wide style.css like every other view, saw "
                        + stylesheets);
        require(stylesheets.contains(VIEW_STYLESHEET),
                "the view must link teacher-course.css, saw " + stylesheets);
        require("860.0".equals(root.getAttribute("prefWidth"))
                        && "580.0".equals(root.getAttribute("prefHeight")),
                "the workspace must reuse the 860x580 main window, saw "
                        + root.getAttribute("prefWidth") + "x" + root.getAttribute("prefHeight"));

        Element back = buttonWithText(view, "返回首页");
        require(back != null, "the top-left entry must be a 返回首页 button");
        require("#handleBack".equals(back.getAttribute("onAction")),
                "返回首页 must be bound to #handleBack, saw " + back.getAttribute("onAction"));

        Element statusLabel = elementWithId(view, "statusLabel");
        require(statusLabel != null,
                "the view must expose the status label the staged entries report into");
        require(STAGING_NOTICE.equals(statusLabel.getAttribute("text")),
                "the status label must default to the staging notice, saw "
                        + statusLabel.getAttribute("text"));
    }

    /**
     * 四个入口必须在真实 {@code Button} 元素上带 {@code disable="true"}。按 {@code text} 定位元素，
     * 因此注释或被注释掉的元素块都无法满足断言。
     */
    private static void stagedEntriesAreRealDisabledElements(Document view) {
        for (String entry : ENTRIES) {
            Element button = buttonWithText(view, entry);
            require(button != null, "the top-right entries must include " + entry);
            require("true".equals(button.getAttribute("disable")),
                    "entry " + entry + " must carry disable=\"true\", saw \""
                            + button.getAttribute("disable") + "\"");
        }
    }

    /**
     * 只读属性陷阱：{@code disabled} 在 {@code Node} 上只读，写进 FXML 要到加载期才抛
     * {@code PropertyNotFoundException}，所以这里逐元素确认它没有出现在文档的任何地方。
     */
    private static void noElementUsesTheReadOnlyDisabledAttribute(Document view) {
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            require(!element.hasAttribute("disabled"),
                    "<" + element.getTagName()
                            + "> must not use the read-only disabled attribute; use disable");
        }
    }

    private static void everyFxIdAndOnActionResolvesOnTheController(Document view) {
        Class<?> controller = TeacherCourseManagementController.class;
        NodeList elements = view.getElementsByTagName("*");
        int ids = 0;
        int actions = 0;
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);

            String id = element.getAttribute("fx:id");
            if (!id.isEmpty()) {
                ids++;
                require(findField(controller, id) != null,
                        "<" + element.getTagName() + "> fx:id=\"" + id
                                + "\" has no field on " + controller.getSimpleName());
            }

            String action = element.getAttribute("onAction");
            if (action.startsWith("#")) action = action.substring(1);
            if (!action.isEmpty()) {
                actions++;
                require(hasNoArgMethod(controller, action),
                        "<" + element.getTagName() + "> onAction=\"#" + action
                                + "\" has no no-arg method on " + controller.getSimpleName());
            }
        }
        require(ids > 0 && actions > 0,
                "the view must actually declare fx:id and onAction bindings, saw "
                        + ids + " ids and " + actions + " actions");
    }

    private static void everyStyleClassExistsInTheStylesheet(Document view, String css) {
        Set<String> styleClasses = new LinkedHashSet<>();
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            String value = ((Element) elements.item(index)).getAttribute("styleClass");
            if (value.isEmpty()) continue;
            for (String token : value.split(",")) {
                String name = token.trim();
                if (!name.isEmpty()) styleClasses.add(name);
            }
        }
        require(!styleClasses.isEmpty(), "the view must use style classes from teacher-course.css");
        for (String name : styleClasses) {
            require(css.contains("." + name),
                    "styleClass " + name + " has no ." + name
                            + " selector in teacher-course.css");
        }
    }

    private static TeacherCourseManagementController controller() {
        return new TeacherCourseManagementController(new MockTeacherCourseService());
    }

    private static Document parseView() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream =
                TeacherCourseManagementControllerTest.class.getResourceAsStream(VIEW)) {
            if (stream == null) throw new IOException("Missing resource: " + VIEW);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = TeacherCourseManagementControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Element buttonWithText(Document view, String text) {
        NodeList buttons = view.getElementsByTagName("Button");
        for (int index = 0; index < buttons.getLength(); index++) {
            Element button = (Element) buttons.item(index);
            if (text.equals(button.getAttribute("text"))) return button;
        }
        return null;
    }

    private static Element elementWithId(Document view, String id) {
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttribute("fx:id"))) return element;
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) return field;
            }
        }
        return null;
    }

    private static boolean hasNoArgMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
