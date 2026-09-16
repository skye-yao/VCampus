package util;

/**
 * 评价等公开场合展示用户时的脱敏规则。
 *
 * <p>一卡通号属于可定位到具体人的标识，且本项目的一卡通号是连号的，
 * 因此不做“中间打星”这类保留首尾的掩码，而是用账号的稳定哈希取后四位生成昵称：
 * 既能区分不同用户，又无法反推出账号本身。
 */
public final class UserDisplay {

    private UserDisplay() { }

    /** 取展示名：优先用服务端给的 displayName，次之回落到账号或“匿名用户”。 */
    public static String label(String displayName, String userId) {
        if (displayName != null && !displayName.isBlank()) return displayName;
        return userId == null || userId.isBlank() ? "匿名用户" : userId;
    }

    /** 生成脱敏昵称，例如 {@code 用户8291}。 */
    public static String masked(String userId) {
        if (userId == null || userId.isBlank()) return "匿名用户";
        return "用户" + String.format("%04d", Math.floorMod(userId.hashCode(), 10000));
    }
}
