package util;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.util.Duration;

/**
 * 一条会自己消退的状态提示：新文本一到达就显示，原样留 {@link #VISIBLE_DURATION} 之后在
 * {@link #FADE_DURATION} 内线性淡出，收尾时隐藏标签并把不透明度复位（下一条提示因此从全不透明
 * 开始，不会继承上一轮淡出到 0 的那一帧）。文本被置空（{@code null}）则只是取消在跑的那次消退。
 *
 * <p><b>先后顺序由调用方决定，工具只管状态。</b>{@link #show(String)} 只记录「有一条新提示到达」，
 * 真正的显示、计时与隐藏都发生在 {@link #render()} 里——页面因此可以把渲染保持在它自己唯一的
 * 出口上，一次页面重画不会重置计时。计时触发条件是「到达」而不是「文本变化」：连续两次
 * 「成绩草稿已保存」是同一个字符串，按文本比较会漏掉第二次到达，上一次消退的收尾就会把这条新
 * 提示连着一起清掉，用户从此看不到任何提示。
 *
 * <p><b>标签可以缺失。</b>构造时传入 {@code null}（无 JavaFX 工具包的控制器测试就是这种形态）时
 * 工具退化成纯状态机：文本照旧读得到、{@link #isFaded()} 照旧回答，只是没有任何标签可写，
 * 也不会去构造 {@link Timeline}——计时只在真的有标签时才存在。
 *
 * <p>换页纪律：离开页面用 {@link #dispose()} 停表（同一个控制器实例会被反复进出，计时器不能跨页
 * 泄漏）；要把提示也一并清掉用 {@link #reset()}，它到下一次 {@link #render()} 才落成「标签隐藏」。
 */
public final class FadingNotice {

    /** 提示原样留在界面上的时长，以及随后渐变淡出的时长（3 秒后消退，不是瞬间隐藏）。 */
    public static final Duration VISIBLE_DURATION = Duration.seconds(3);
    public static final Duration FADE_DURATION = Duration.millis(400);

    private final Label label;
    /** 当前那句话；{@code null} 表示没有提示。 */
    private String text;
    /** 提示到达计数：每次 {@link #show} 前进一格，{@link #render()} 据此决定要不要重新计时。 */
    private long revision;
    /** 已经渲染过的到达计数：两者不等就是有新提示要显示并重新计时。 */
    private long renderedRevision;
    /** 提示是否已经随渐变消退并隐藏；没有新文本到达时 {@link #render()} 不把它显示回来。 */
    private boolean faded;
    /** 正在跑的那次消退（{@code null} 表示没有在跑的计时）；换新提示时取消旧的。 */
    private Timeline fade;
    /** 消退收尾时的通知（默认什么都不做）：需要连带重画别的东西的页面在这里接。 */
    private Runnable onFaded = () -> { };

    /** 绑定标签；{@code null} 表示这个页面没有标签可写（见类注释里的退化形态）。 */
    public FadingNotice(Label label) {
        this.label = label;
    }

    /**
     * 记下一条新提示（{@code null} 表示清空）。它不碰标签：显示与计时都在 {@link #render()} 里。
     */
    public void show(String text) {
        this.text = text;
        revision++;
    }

    /** 当前那句话；没有提示时为 {@code null}。标签缺失时它仍然是这条提示的唯一事实来源。 */
    public String text() {
        return text;
    }

    /** 当前提示是否已经消退隐藏（没有提示、或标签缺失时都是 {@code false}）。 */
    public boolean isFaded() {
        return faded;
    }

    /** 提示此刻是否应当可见：有文本且还没消退。 */
    public boolean visible() {
        return text != null && !faded;
    }

    /**
     * 消退收尾时的回调（在 FX 线程上、标签已经隐藏之后调用）。默认不做事。
     */
    public void onFaded(Runnable action) {
        this.onFaded = action == null ? () -> { } : action;
    }

    /**
     * 把当前状态落到标签上：文本、可见性，以及「有新提示到达就重新计时」。
     *
     * <p>只认“有新提示到达”（{@link #revision} 前进），不认“这次渲染和上次不一样”：页面上的任何
     * 一次重画都会走到这里来，按渲染次数重新计时会让提示永远等不到消退。标签缺失时什么都不写。
     */
    public void render() {
        if (label == null) return;
        label.setText(text == null ? "" : text);
        if (revision != renderedRevision) {
            renderedRevision = revision;
            faded = false;
            restart();
        }
        setActive(label, visible());
    }

    /**
     * 清空提示并停表（换教学班/离开页面用）。它<b>不</b>直接隐藏标签：渲染出口只有一个，
     * 调用方随后的 {@link #render()} 才把标签落到隐藏态。
     */
    public void reset() {
        show(null);
        faded = false;
        dispose();
    }

    /**
     * 取消在跑的消退，但保留当前那句话（页面卸下时用：界面不再更新，也不需要再计时）。
     */
    public void dispose() {
        Timeline running = fade;
        fade = null;
        if (running != null) {
            running.stop();
        }
    }

    /**
     * 按当前提示重新计时：非空则先原样留 {@link #VISIBLE_DURATION}，再 {@link #FADE_DURATION}
     * 之内淡出；提示为空则只是取消在跑的那次消退。
     *
     * <p>旧计时一律先取消：新提示到家时，上一次的收尾既不能把它隐藏，也不能把不透明度留成 0。
     * 收尾自身还有一道“我还是当前这次消退吗”的检查，因此即使某个实现会在 {@code stop()} 里同步
     * 触发 {@code onFinished}，也清不掉刚落地的提示。
     */
    private void restart() {
        dispose();
        if (label == null || text == null) return;
        label.setOpacity(1);
        Timeline next = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(label.opacityProperty(), 1)),
                new KeyFrame(VISIBLE_DURATION, new KeyValue(label.opacityProperty(), 1)),
                new KeyFrame(VISIBLE_DURATION.add(FADE_DURATION),
                        new KeyValue(label.opacityProperty(), 0)));
        next.setOnFinished(event -> {
            if (fade != next) return;
            fade = null;
            faded = true;
            // 复位不透明度：下一次提示从全不透明开始，不能继承上一轮淡出到 0 的那一帧。
            label.setOpacity(1);
            render();
            onFaded.run();
        });
        fade = next;
        next.play();
    }

    private static void setActive(Label target, boolean active) {
        target.setVisible(active);
        target.setManaged(active);
    }
}
