package controller;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * 课次块正文与调课角标的共用装配：两端（学生端 {@code ScheduleController.createCourseBlock} 与
 * 教师端 {@code TeacherScheduleController.createCard}）的角标都竖排在课次块<b>右侧</b>，
 * 不再占标题上方的一行，因此不再改变课表行高。
 *
 * <p>竖排是「三个字纵向排成一列」，不是整体旋转 90°：角标仍然是一个 {@link Label}，{@code getText()}
 * 返回完整的 {@code 原安排}／{@code 调课后}（冒烟测试按 CSS 类取文本，拆成三个标签就取不到了）。
 * 竖排靠 {@code wrapText} + 一个字宽的固定宽度让 CJK 逐字折行；宽度在这里而不是各自的样式表里，
 * 两端因此不会一个竖着、一个横着。样式类只负责颜色与内边距。
 *
 * <p>没有角标的普通课次拿到的是原来的正文节点本身，渲染一字不变（连节点结构都不变）。
 */
final class AdjustmentBadge {
    /**
     * 角标宽度：正好放下一个 CJK 字 + 左右内边距（两端样式里的 {@code -fx-padding} 都是
     * {@code 1px 3px}，即左右共 6px；9px 字号的一个全角字宽 9px，10px 的内容宽度因此放得下且
     * 只放得下一个字），三个字于是竖着排成一列。整条角标只占课次块 16px 宽，正文仍拿走其余宽度。
     * 角标高度约 38px（三行 9px 文字），且不随课次块拉伸（{@code LabeledSkinBase} 给 Labeled 的最大
     * 高度就是它的偏好高度）；参与课次块最小高度的只有一行文字（约 13px），比两端节次行的最小高度
     * （教师端 60px、学生端 30px）都小，所以不会把课次块撑高。
     */
    private static final double BADGE_WIDTH = 16.0;
    /** 正文与角标之间的间隔；角标已经很窄，间隔再大就真的挤到课程名了。 */
    private static final double BADGE_GAP = 3.0;

    private AdjustmentBadge() {
    }

    /**
     * 课次块要挂的 graphic：有角标时是一行 {@code [正文 | 角标]}，没有角标时就是正文本身。
     * 正文用 {@code hgrow} 吃掉剩余宽度，角标保持自己那一个字宽；整行允许被拉伸到格子大小，
     * 课次块因此仍然跟着窗口铺满。
     *
     * @param content    课次块正文（标题 + 地点），两端各自组装
     * @param badgeText  角标文案；{@code null} 表示普通课次，返回正文本身
     * @param styleClass 该端的角标样式类（学生端 {@code course-adjustment-badge}、
     *                   教师端 {@code teacher-schedule-badge}）
     */
    static Node badged(Node content, String badgeText, String styleClass) {
        if (badgeText == null) {
            return content;
        }
        Label badge = new Label(badgeText);
        badge.getStyleClass().add(styleClass);
        badge.setWrapText(true);
        badge.setMinWidth(BADGE_WIDTH);
        badge.setPrefWidth(BADGE_WIDTH);
        badge.setMaxWidth(BADGE_WIDTH);
        badge.setAlignment(Pos.CENTER);

        HBox row = new HBox(BADGE_GAP, content, badge);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinSize(0.0, 0.0);
        row.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(content, Priority.ALWAYS);
        return row;
    }
}
