package network;

import com.google.gson.Gson;
import protocol.Message;
import session.ClientSession;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** JSON 长连接；单发送队列、每连接独立接收器和 pending 表。 */
public class SocketClient {
    public static final int REQUEST_TIMEOUT_SECONDS=10, CONNECT_TIMEOUT_MILLIS=5000, MAX_QUEUED_REQUESTS=128;
    private static final SocketClient INSTANCE=new SocketClient();
    private final Gson gson=new Gson();
    private final Object lifecycleLock=new Object();
    private final Object sendLock=new Object();
    private final ExecutorService io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_REQUESTS),r->{Thread t=new Thread(r,"network-send");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final CopyOnWriteArrayList<Runnable> disconnectListeners=new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String,CompletableFuture<Message>> activeRequests=new ConcurrentHashMap<>();
    private volatile Connection current;
    private volatile String host="127.0.0.1";
    private volatile int port=8888;
    private volatile boolean closed;
    private long generation;
    private static final class Connection {
        final Socket socket;final PrintWriter writer;final MessageDispatcher dispatcher=new MessageDispatcher();
        MessageReceiver receiver;
        Connection(Socket s)throws IOException {socket=s;writer=new PrintWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8),true);}
    }
    private SocketClient() {}
    public static SocketClient getInstance(){return INSTANCE;}
    public void init(String host,int port){this.host=host;this.port=port;}
    public void addDisconnectListener(Runnable listener){disconnectListeners.add(listener);}
    public void removeDisconnectListener(Runnable listener){disconnectListeners.remove(listener);}
    public CompletableFuture<Void> connectAsync() {
        CompletableFuture<Void> result=new CompletableFuture<>();long epoch;
        synchronized(lifecycleLock){epoch=generation;}
        try{io.execute(()->{try{connection(epoch);result.complete(null);}catch(Exception e){result.completeExceptionally(e);}});}
        catch(RejectedExecutionException e){result.completeExceptionally(e);}
        return result;
    }
    /** 保留同步 API，禁止界面线程阻塞调用。 */
    public void connect()throws IOException {
        if(javafx.application.Platform.isFxApplicationThread())throw new IllegalStateException("界面线程请使用 connectAsync");
        try{connectAsync().get(CONNECT_TIMEOUT_MILLIS+1000L,TimeUnit.MILLISECONDS);}
        catch(Exception e){throw new IOException("连接失败",e);}
    }
    private Connection connection(long epoch)throws IOException {
        synchronized(lifecycleLock){
            if(closed || epoch!=generation)throw new IOException("连接已关闭，请重新操作");
            if(current!=null)return current;
        }
        Socket socket=new Socket();Connection made;
        try {
            socket.connect(new InetSocketAddress(host,port),CONNECT_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);made=new Connection(socket);
            made.receiver=new MessageReceiver(new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)),gson,made.dispatcher,
                    ()->connectionLost(made,new IOException("与服务器的连接已断开")));
            synchronized(lifecycleLock){
                if(closed || epoch!=generation)throw new IOException("连接已取消");
                current=made;
            }
            Thread reader=new Thread(made.receiver,"network-reader-"+epoch);reader.setDaemon(true);reader.start();return made;
        }catch(IOException e){try{socket.close();}catch(IOException ignored){}throw e;}
    }
    public CompletableFuture<Message> sendAsync(Message original) {
        // 在调用线程截取消息和身份，后续 UI 修改不会改变已排队请求。
        Message request=gson.fromJson(gson.toJson(original),Message.class);
        request.setRequestId(UUID.randomUUID().toString());
        ClientSession session=ClientSession.getInstance();
        synchronized(session){if(session.isLoggedIn()){request.setSender(session.getUsername());request.setToken(session.getToken());}}
        CompletableFuture<Message> wire=new CompletableFuture<>(), result=new CompletableFuture<>();
        AtomicReference<Connection> used=new AtomicReference<>();AtomicBoolean attempted=new AtomicBoolean();long epoch;
        synchronized(lifecycleLock){epoch=generation;activeRequests.put(request.getRequestId(),wire);}
        int timeout="ai".equalsIgnoreCase(request.getModule())?60:REQUEST_TIMEOUT_SECONDS;
        wire.orTimeout(timeout,TimeUnit.SECONDS).whenComplete((response,error)->{
            activeRequests.remove(request.getRequestId(),wire);
            Connection c=used.get();if(c!=null)c.dispatcher.removePendingRequest(request.getRequestId());
            if(error!=null){
                if(error instanceof TimeoutException && attempted.get() && c!=null)connectionLost(c,error);
                result.completeExceptionally(new RequestFailure(error,attempted.get()));
            }else result.complete(response);
        });
        try {io.execute(()->{
            if(wire.isDone())return;
            try {
                Connection c=connection(epoch);used.set(c);
                synchronized(lifecycleLock){
                    if(c!=current || epoch!=generation)throw new IOException("连接已断开");
                    if(wire.isDone())return;
                    c.dispatcher.registerPendingRequest(request.getRequestId(),wire);
                    if(wire.isDone()){c.dispatcher.removePendingRequest(request.getRequestId());return;}
                }
                synchronized(sendLock){
                    synchronized(lifecycleLock){if(c!=current || epoch!=generation)throw new IOException("连接已断开");}
                    attempted.set(true);
                    if(wire.isDone()){c.dispatcher.removePendingRequest(request.getRequestId());return;}
                    c.writer.println(gson.toJson(request));
                    if(c.writer.checkError())throw new IOException("消息发送中断");
                }
            }catch(Exception e){wire.completeExceptionally(e);Connection c=used.get();if(c!=null)connectionLost(c,e);}
        });}catch(RejectedExecutionException e){wire.completeExceptionally(e);}
        result.whenComplete((r,e)->{if(result.isCancelled())wire.cancel(false);});
        return result;
    }
    public static final class RequestFailure extends IOException {
        private final boolean possiblySent;
        RequestFailure(Throwable cause,boolean possiblySent){super(cause.getMessage(),cause);this.possiblySent=possiblySent;}
        public boolean possiblySent(){return possiblySent;}
    }
    public static boolean possiblySent(Throwable error){
        while(error!=null){if(error instanceof RequestFailure f)return f.possiblySent();error=error.getCause();}return false;
    }
    public Message sendSync(Message request,long timeoutSeconds)throws Exception {
        if(javafx.application.Platform.isFxApplicationThread())throw new IllegalStateException("界面线程不能同步等待网络");
        return sendAsync(request).get(timeoutSeconds,TimeUnit.SECONDS);
    }
    private void connectionLost(Connection c,Throwable cause){
        boolean notify;CompletableFuture<?>[] pending;
        synchronized(lifecycleLock){notify=current==c;pending=notify?activeRequests.values().toArray(CompletableFuture[]::new):new CompletableFuture<?>[0];if(notify){current=null;generation++;}}
        try{c.socket.close();}catch(IOException ignored){}
        c.receiver.stop();c.dispatcher.failAllPending(cause);
        for(CompletableFuture<?> future:pending)future.completeExceptionally(cause);
        if(notify)for(Runnable listener:disconnectListeners){try{listener.run();}catch(RuntimeException ignored){}}
    }
    public void disconnect(){
        Connection old;CompletableFuture<?>[] pending;
        synchronized(lifecycleLock){old=current;current=null;generation++;pending=activeRequests.values().toArray(CompletableFuture[]::new);}
        IOException cause=new IOException("连接已关闭");
        if(old!=null){try{old.socket.close();}catch(IOException ignored){}old.receiver.stop();old.dispatcher.failAllPending(cause);}
        for(CompletableFuture<?> f:pending)f.completeExceptionally(cause);
        for(Runnable listener:disconnectListeners){try{listener.run();}catch(RuntimeException ignored){}}
    }
    public void shutdown(){closed=true;disconnect();io.shutdownNow();}
    public boolean isConnected(){Connection c=current;return c!=null&&!c.socket.isClosed();}
}
