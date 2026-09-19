package controller;

import dto.course.admin.schedule.ScheduleSlotDTO;

/**
 * 排课对话框中的一个动态时间段行。
 *
 * 只保存该行的数值状态与本地校验，不保存任何服务端权威状态（安排标识、版本、方案修订），
 * 也不持有 JavaFX 控件，因此可以脱离 JavaFX 单独测试。
 */
/** 排课编辑器中单个星期与节次区间的可变 View 模型。 */
public final class ScheduleSlotEditor {
    static final int MIN_WEEKDAY = 1;
    static final int MAX_WEEKDAY = 7;
    static final int MIN_PERIOD = 1;
    static final int MAX_PERIOD = 13;

    private Runnable onChange = () -> { };
    private int dayOfWeek;
    private int startPeriod;
    private int endPeriod;

    /** 创建默认的排课节次编辑值。 */
    public ScheduleSlotEditor() {
    }

    /** 从服务端排课节次 DTO 创建可编辑副本。 */
    public ScheduleSlotEditor(ScheduleSlotDTO slot) {
        if (slot != null) {
            this.dayOfWeek = slot.getDayOfWeek();
            this.startPeriod = slot.getStartPeriod();
            this.endPeriod = slot.getEndPeriod();
        }
    }

    void setOnChange(Runnable handler) {
        this.onChange = handler == null ? () -> { } : handler;
    }

    public void setDayOfWeek(int dayOfWeek) {
        if (this.dayOfWeek == dayOfWeek) return;
        this.dayOfWeek = dayOfWeek;
        onChange.run();
    }

    public void setStartPeriod(int startPeriod) {
        if (this.startPeriod == startPeriod) return;
        this.startPeriod = startPeriod;
        onChange.run();
    }

    public void setEndPeriod(int endPeriod) {
        if (this.endPeriod == endPeriod) return;
        this.endPeriod = endPeriod;
        onChange.run();
    }

    public int dayOfWeek() {
        return dayOfWeek;
    }

    public int startPeriod() {
        return startPeriod;
    }

    public int endPeriod() {
        return endPeriod;
    }

    /** 将当前编辑值转换为提交排课服务的 DTO。 */
    public ScheduleSlotDTO value() {
        return new ScheduleSlotDTO(dayOfWeek, startPeriod, endPeriod);
    }

    /**
     * 校验失败时返回原因，合法时返回 {@code null}。
     */
    String validationMessage() {
        if (dayOfWeek < MIN_WEEKDAY || dayOfWeek > MAX_WEEKDAY) return "请选择星期";
        if (startPeriod < MIN_PERIOD || startPeriod > MAX_PERIOD) return "请选择开始节次";
        if (endPeriod < MIN_PERIOD || endPeriod > MAX_PERIOD) return "请选择结束节次";
        if (startPeriod > endPeriod) return "结束节次不能早于开始节次";
        return null;
    }

    boolean isComplete() {
        return validationMessage() == null;
    }

    /**
     * 同一星期且节次区间有交集才算重叠；边界相接不算。
     */
    boolean overlaps(ScheduleSlotEditor other) {
        if (other == null) return false;
        if (dayOfWeek != other.dayOfWeek) return false;
        return startPeriod <= other.endPeriod && other.startPeriod <= endPeriod;
    }

    String summary() {
        return weekdayLabel(dayOfWeek) + " 第" + periodLabel(startPeriod, endPeriod) + "节";
    }

    static String weekdayLabel(int dayOfWeek) {
        String[] labels = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return dayOfWeek >= MIN_WEEKDAY && dayOfWeek <= MAX_WEEKDAY
                ? labels[dayOfWeek] : "星期未定";
    }

    static String periodLabel(int startPeriod, int endPeriod) {
        if (startPeriod <= 0 || endPeriod <= 0) return "节次未定";
        return startPeriod == endPeriod
                ? String.valueOf(startPeriod) : startPeriod + "-" + endPeriod;
    }
}
