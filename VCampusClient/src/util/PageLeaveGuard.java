package util;

/**
 * 离开守卫：页面在“关闭窗体 / 离开本页”真正发生之前拦截一次（设计 §5.4）。
 *
 * <p>契约只有两个方法：{@link #requestLeave()} 回答“这次离开是否被允许”，返回 false 时调用方必须
 * 取消这次离开（关闭事件要被 {@code consume}）；{@link #onClosed()} 只在真正离开之后调用一次，
 * 页面在它里面取消在途请求、注销自己，并且此后不得再更新控件。
 *
 * <p>注册表里同一时刻只保留一个活动守卫，并且只由当前页面/工作台注册：页面成为当前页时
 * {@link #install(PageLeaveGuard)}，离开时 {@link #clear(PageLeaveGuard)} 注销自己的那一个。
 * 于是旧页面的钩子不可能残留下来拦下新页面的关闭——这正是本类用“唯一活动守卫”而不是可累加的
 * 监听器列表的原因。没有注册守卫的页面（登录页、首页、只读列表页）行为与引入本类之前完全一致：
 * {@link #active()} 为 null，关闭窗体直接照旧断开连接。
 *
 * <p>注册表只在 FX 线程读写（关闭事件、工作台导航与 FXML 加载都在 FX 线程）；测试在同一线程里
 * 顺序驱动控制器，因此这里不加锁，避免把工具包线程模型带进无工具包的控制器测试。
 */
public interface PageLeaveGuard {

    /**
     * 是否允许离开。返回 false 表示调用方必须取消这次离开：关闭窗体的路径要 {@code consume}
     * 关闭事件并保持窗口打开，导航路径要保持当前页面不动。
     */
    boolean requestLeave();

    /**
     * 真正离开之后调用一次：取消在途请求、注销本守卫，并保证此后的响应不再写界面。
     * 被拒绝的离开不能调用它。
     */
    void onClosed();

    // ------------------------------------------------------------------ 注册表

    /** 当前活动守卫；没有页面注册守卫时是 null。 */
    static PageLeaveGuard active() {
        return Registry.active;
    }

    /** 页面成为当前页/工作台装载时注册；同一时刻至多一个，新的守卫顶替旧的。 */
    static void install(PageLeaveGuard guard) {
        Registry.active = guard;
    }

    /** 页面离开时注销；只注销自己的那一个，绝不误清后来者注册的守卫。 */
    static void clear(PageLeaveGuard guard) {
        if (Registry.active == guard) {
            Registry.active = null;
        }
    }

    /** 场景整体替换前的兜底：任何守卫都不应该跨场景存活。 */
    static void clear() {
        Registry.active = null;
    }

    /**
     * 新页面加载完成后登记它自带的守卫（{@code ClientMain.switchScene} 用）。
     *
     * <p>不是守卫的页面（只读页、首页）不改变注册表：它们加载期间本来就没有注册守卫，
     * 因此这条调用对它们是无操作，旧行为不受影响。
     */
    static void registerFrom(Object controller) {
        if (controller instanceof PageLeaveGuard guard) {
            install(guard);
        }
    }

    /** 注册表的持有者：接口字段只能是常量，可变状态放在这里，避免把 active 暴露成公开字段。 */
    final class Registry {
        private static PageLeaveGuard active;

        private Registry() {
        }
    }
}
