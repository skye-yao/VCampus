package controller;

import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextFormatter;
import javafx.util.StringConverter;

/**
 * 周次 Spinner 的共用装配：学生端课表（{@link ScheduleController}）与教师端课表
 * （{@link TeacherScheduleController}）的周次控件同构，三条约定都放在这里，两端不会各自漂移。
 *
 * <p><b>上下箭头方向与 JavaFX 默认相反</b>：向上（{@code increment}）＝ 周数 −1，向下
 * （{@code decrement}）＝ 周数 +1。箭头按钮与键盘 ↑/↓ 最后都调用 value factory 的
 * {@code increment}/{@code decrement}（{@code SpinnerBehavior} 与 {@code SpinnerSkin} 的箭头按钮
 * 都走 {@code Spinner.increment/decrement}），因此在这里反向一次就同时覆盖两条路径；越界由 value
 * factory 自己的 {@code [min, max]} 夹取。手输数字的语义不变：输入的数就是那一周。
 *
 * <p><b>输入框只放行数字</b>：非数字按键与粘贴在 {@link TextFormatter} 那层就被丢弃，转换器因此只
 * 需要面对「数字或空」。转换器的 {@code fromString} 既不能抛异常、也不能给出越界值：JavaFX 默认的
 * {@code IntegerStringConverter} 遇到字母会抛 {@code NumberFormatException}，遇到空文本会返回 null，
 * 值一旦变成 null，上下箭头取值时就会 NPE。失焦时 Spinner 会自己拿这个转换器提交（JavaFX 内建行为）。
 *
 * <p><b>提交先夹取再回写</b>：回车与失焦都走 {@link #commitTyped(Spinner)}，输入框里不会留下一个模型
 * 没有接受的值。
 */
final class WeekSpinner {
    private WeekSpinner() {
    }

    /**
     * 反向箭头的周次值工厂：范围与初值由调用方给定（学生端是固定范围，教师端每次加载后同步）。
     * 转换器在这里一并装好，调用方直接 {@code spinner.setValueFactory(...)} 即可。
     */
    static SpinnerValueFactory.IntegerSpinnerValueFactory valueFactory(int min, int max, int value) {
        SpinnerValueFactory.IntegerSpinnerValueFactory factory =
                new BackwardWeekValueFactory(min, max, value);
        factory.setConverter(new StringConverter<Integer>() {
            @Override
            public Integer fromString(String text) {
                return commitWeek(text, factory.getValue(), factory.getMin(), factory.getMax());
            }

            @Override
            public String toString(Integer value) {
                return value == null ? "" : value.toString();
            }
        });
        return factory;
    }

    /**
     * 把可编辑输入框接上：数字过滤、回车与失焦提交并回写。
     * value factory 可以为 {@code null}（范围还没从服务端回来），此时提交什么都不做。
     */
    static void installEditor(Spinner<Integer> spinner) {
        // 非数字按键与粘贴在这层就被丢弃，于是转换器只需要面对“数字或空”
        spinner.getEditor().setTextFormatter(new TextFormatter<Object>(change ->
                change.getControlNewText().chars().allMatch(Character::isDigit) ? change : null));
        // 回车换成自己的提交：Spinner 自带的提交不回写文本，输入 99 会显示 99 而周次其实是 20
        spinner.getEditor().setOnAction(event -> commitTyped(spinner));
        // 失焦提交是 Spinner 的内建行为，这里刻意沿用它（敲完直接点别处也该生效），只是补一次提交与回写
        spinner.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitTyped(spinner);
            }
        });
    }

    /** 提交输入框内容并回写文本；周次没变就不写回 value factory，免得重敲同一个数字也发一次请求。 */
    static void commitTyped(Spinner<Integer> spinner) {
        SpinnerValueFactory<Integer> factory = spinner.getValueFactory();
        Integer currentWeek = spinner.getValue();
        if (factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory weekFactory) {
            int committedWeek = commitWeek(spinner.getEditor().getText(),
                    currentWeek == null ? weekFactory.getMin() : currentWeek,
                    weekFactory.getMin(), weekFactory.getMax());
            if (currentWeek == null || currentWeek != committedWeek) {
                factory.setValue(committedWeek);
            }
        }
        // 越界数字被夹取、空文本被忽略之后，输入框不能继续显示用户敲进去的原样
        spinner.cancelEdit();
    }

    /**
     * 输入框文本 → 周次：空白或非数字视为“不改动”，越界数字夹取到 [minWeek, maxWeek]。
     * 夹取放在写进 value factory 之前，而不是留给工厂自带的越界回调：那条回调会先把越界值写进属性、
     * 再改回边界值，一次输入会放走两次刷新请求（其中一次还是请求一个不存在的周次）。
     */
    static int commitWeek(String text, int currentWeek, int minWeek, int maxWeek) {
        if (text == null) {
            return currentWeek;
        }
        String digits = text.trim();
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return currentWeek;
        }
        try {
            return Math.max(minWeek, Math.min(maxWeek, Integer.parseInt(digits)));
        } catch (NumberFormatException failure) {
            return maxWeek; // 位数多到 int 装不下，等同于超过上界
        }
    }

    /**
     * 箭头反过来的整数值工厂：只交换两个方向，范围和夹取仍旧交给父类。
     * 包装（{@code wrapAround}）默认关闭，因此两端的箭头都不会绕回另一头。
     */
    private static final class BackwardWeekValueFactory
            extends SpinnerValueFactory.IntegerSpinnerValueFactory {
        private BackwardWeekValueFactory(int min, int max, int value) {
            super(min, max, value);
        }

        /** 向上箭头 / ↑：往前的周。 */
        @Override
        public void increment(int steps) {
            super.decrement(steps);
        }

        /** 向下箭头 / ↓：往后的周。 */
        @Override
        public void decrement(int steps) {
            super.increment(steps);
        }
    }
}
