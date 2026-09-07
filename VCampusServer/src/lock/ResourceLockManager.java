package lock;

import protocol.LockRequest;
import protocol.LockResponse;
import session.UserSession;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** 单服务器租约。短期租约负责页面独占，Guard 防止提交期间被过期接管。 */
public final class ResourceLockManager {
    public static final long LEASE_MILLIS = 90_000;
    public static final String LOST = "编辑占用已失效，请刷新后重新操作";
    private static final ResourceLockManager INSTANCE = new ResourceLockManager(System::currentTimeMillis);
    private static final Set<String> TYPES = Set.of("STUDENT", "TEACHER", "STUDENT_RECORDS", "TEACHER_RECORDS", "STUDENT_CHANGE_REQUEST", "TEACHER_CHANGE_REQUEST");
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final ReentrantLock[] guards = new ReentrantLock[128];
    private final LongSupplier clock;
    public record Lease(String userId, String clientId, String editSessionId, String lockToken, long expiresAt) {}

    public ResourceLockManager(LongSupplier clock) {
        this.clock = clock;
        for (int i=0;i<guards.length;i++) guards[i] = new ReentrantLock();
    }
    public static ResourceLockManager getInstance() { return INSTANCE; }

    public Guard guard(String key) {
        ReentrantLock lock = guards[Math.floorMod(key.hashCode(), guards.length)];
        lock.lock();
        return new Guard(lock);
    }
    public static final class Guard implements AutoCloseable {
        private final ReentrantLock lock;
        private Guard(ReentrantLock lock) { this.lock = lock; }
        @Override public void close() { lock.unlock(); }
    }
    private String key(UserSession user, LockRequest q) {
        if (user == null) throw new SecurityException("请先登录");
        if (q == null || q.resourceType() == null || !TYPES.contains(q.resourceType())
                || blank(q.resourceId()) || blank(q.clientId()) || blank(q.editSessionId())
                || q.resourceId().length()>128 || q.clientId().length()>128 || q.editSessionId().length()>128)
            throw new IllegalArgumentException("占用参数无效");
        return q.resourceKey();
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private boolean owner(Lease lease, UserSession user, LockRequest q) {
        return lease != null && lease.userId().equals(user.getUsername())
                && lease.clientId().equals(q.clientId()) && lease.editSessionId().equals(q.editSessionId());
    }
    private boolean matches(Lease lease, UserSession user, LockRequest q) {
        return owner(lease,user,q) && Objects.equals(lease.lockToken(),q.lockToken());
    }
    public LockResponse acquire(UserSession user, LockRequest q) {
        String key = key(user,q);
        try (Guard ignored=guard(key)) {
            long now=clock.getAsLong();
            Lease lease=leases.compute(key,(k,old)-> {
                if(old!=null && old.expiresAt()>now && !owner(old,user,q))
                    throw new IllegalStateException("该记录正在被其他编辑或审核会话占用，请稍后再试");
                String token=old!=null && old.expiresAt()>now ? old.lockToken() : UUID.randomUUID().toString();
                return new Lease(user.getUsername(),q.clientId(),q.editSessionId(),token,now+LEASE_MILLIS);
            });
            return new LockResponse(true,lease.lockToken(),lease.expiresAt(),"已取得占用");
        }
    }
    public LockResponse renew(UserSession user, LockRequest q) {
        String key=key(user,q);
        try (Guard ignored=guard(key)) {
            validate(user,q,key);
            Lease lease=leases.computeIfPresent(key,(k,old)->new Lease(old.userId(),old.clientId(),old.editSessionId(),old.lockToken(),clock.getAsLong()+LEASE_MILLIS));
            return new LockResponse(true,lease.lockToken(),lease.expiresAt(),"已续期");
        }
    }
    public void release(UserSession user, LockRequest q) {
        String key=key(user,q);
        try (Guard ignored=guard(key)) {
            Lease lease=leases.get(key);
            if(lease!=null && !matches(lease,user,q)) throw new IllegalStateException(LOST);
            if(lease!=null) leases.remove(key,lease);
        }
    }
    /** 业务应在同一 Guard 内完成验证及事务，不能验证后立即释放 Guard。 */
    public void validate(UserSession user, LockRequest q, String expectedKey) {
        if(q==null)throw new IllegalStateException(LOST);
        String key=key(user,q);
        if(!key.equals(expectedKey)) throw new IllegalStateException(LOST);
        try (Guard ignored=guard(key)) {
            Lease lease=leases.get(key);
            if(!matches(lease,user,q) || lease.expiresAt()<=clock.getAsLong()) throw new IllegalStateException(LOST);
        }
    }
    public void removeExpired() {
        long now=clock.getAsLong();
        leases.forEach((key,lease)-> {
            ReentrantLock lock=guards[Math.floorMod(key.hashCode(),guards.length)];
            if(lock.tryLock()) try { if(lease.expiresAt()<=now) leases.remove(key,lease); } finally { lock.unlock(); }
        });
    }
}
