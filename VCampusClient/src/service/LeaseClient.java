package service;

import com.google.gson.Gson;
import protocol.*;
import network.SocketClient;
import util.Fx;
import java.util.UUID;
import java.util.concurrent.*;

/** 一个实例对应一个编辑或审核页面；状态只在 FX 线程访问。 */
public final class LeaseClient implements AutoCloseable {
    public static final int HEARTBEAT_SECONDS=30, LEASE_SECONDS=90;
    public static final String LOST="当前编辑占用已失效，请重新进入编辑页面获取最新数据";
    private static final String CLIENT_ID=UUID.randomUUID().toString();
    private static final ScheduledThreadPoolExecutor HEARTBEATS=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"lease-heartbeat");t.setDaemon(true);return t;});
    static {HEARTBEATS.setRemoveOnCancelPolicy(true);}
    private final SocketClient socket=SocketClient.getInstance();
    private final Gson gson=new Gson();
    private LockRequest active;
    private ScheduledFuture<?> heartbeat;
    private long epoch;
    private long deadlineNanos;
    private boolean acquiring,renewing;
    private Runnable onLost=()->{};
    private final Runnable disconnected=()->Fx.run(this::invalidate);
    private boolean listening;
    public boolean valid(){return active!=null && System.nanoTime()<deadlineNanos;}
    public boolean owns(LockRequest proof){return active!=null && active.equals(proof);}
    public void closeIfOwned(LockRequest proof){if(owns(proof))close();}
    public void invalidateIfOwned(LockRequest proof){if(owns(proof))invalidate();}
    public boolean busy(){return acquiring || active!=null;}
    public LockRequest proof(){if(!valid()){invalidate();return null;}return active;}
    public void onLost(Runnable action){onLost=action;}
    private CompletableFuture<Message> send(MessageType type,LockRequest proof){
        Message q=new Message(type,"lock",type.name());q.setLock(proof);return socket.sendAsync(q);
    }
    public void acquire(String type,String id,Runnable success,java.util.function.Consumer<String> failure){
        close();long expected=epoch;acquiring=true;
        listening=true;socket.addDisconnectListener(disconnected);
        LockRequest requested=new LockRequest(type,id,CLIENT_ID,UUID.randomUUID().toString(),null);
        long started=System.nanoTime();
        send(MessageType.LOCK_ACQUIRE,requested).whenComplete((m,e)->Fx.run(()->{
            LockResponse response=e==null&&m.getCode()==MessageCode.SUCCESS?gson.fromJson(gson.toJson(m.getData().get("lock")),LockResponse.class):null;
            LockRequest obtained=response!=null&&response.success()?requested.withToken(response.lockToken()):null;
            if(expected!=epoch){if(obtained!=null)send(MessageType.LOCK_RELEASE,obtained);return;}
            acquiring=false;
            if(obtained==null){close();failure.accept(e!=null?"无法确认占用，请刷新后重试":m.getMessage());return;}
            active=obtained;deadlineNanos=started+TimeUnit.SECONDS.toNanos(LEASE_SECONDS);
            heartbeat=HEARTBEATS.scheduleAtFixedRate(()->Fx.run(()->renew(expected)),HEARTBEAT_SECONDS,HEARTBEAT_SECONDS,TimeUnit.SECONDS);
            success.run();
        }));
    }
    private void renew(long expected){
        if(expected!=epoch)return;
        if(!valid()){invalidate();return;}
        if(renewing)return;renewing=true;LockRequest proof=active;long started=System.nanoTime();
        send(MessageType.LOCK_RENEW,proof).whenComplete((m,e)->Fx.run(()->{
            if(expected!=epoch)return;renewing=false;
            if(e!=null || m.getCode()!=MessageCode.SUCCESS){invalidate();return;}
            deadlineNanos=started+TimeUnit.SECONDS.toNanos(LEASE_SECONDS);
        }));
    }
    public void invalidate(){boolean notify=active!=null||acquiring;close();if(notify)onLost.run();}
    @Override public void close(){
        epoch++;acquiring=false;renewing=false;
        if(heartbeat!=null){heartbeat.cancel(false);heartbeat=null;}
        if(listening){socket.removeDisconnectListener(disconnected);listening=false;}
        LockRequest old=active;active=null;
        if(old!=null && socket.isConnected())send(MessageType.LOCK_RELEASE,old);
    }
    public static void shutdown(){HEARTBEATS.shutdownNow();}
}
