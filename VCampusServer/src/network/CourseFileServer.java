package network;

import service.TeacherFileTicketService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 教师成绩 Excel 的文件短连接监听器。
 *
 * <p>与业务服务器完全分开：业务连接是长连接、按行传 JSON；这里每一张文件票据换一条短连接，
 * 传完原始字节立即关闭，绝不在业务 readLine 连接里混入二进制，也不把文件塞进业务 JSON。
 *
 * <p>端口默认 {@value #DEFAULT_FILE_PORT}，由系统属性 {@value #PORT_PROPERTY} 覆盖；测试用 0 让
 * 操作系统分配，再通过 {@link #getPort()} 读回真实端口。绑定成功后端口回填给票据服务，
 * 客户端拿到的票据因此始终指向真正在监听的端口。
 *
 * <p>停止是幂等的：关闭监听器、关闭所有活跃短连接、停止连接线程池，并让票据服务回收临时目录，
 * 所以停服后既没有活动传输线程，也不会留下半截上传或生成的表格。
 */
public final class CourseFileServer implements AutoCloseable {

    /** 文件短连接默认端口。 */
    public static final int DEFAULT_FILE_PORT = 8889;

    /** 覆盖文件端口的系统属性名。 */
    public static final String PORT_PROPERTY = "vcampus.courseFilePort";

    /** 停服时等待连接线程退出的上限（秒）：临时目录必须在它们收工之后才删。 */
    private static final long WORKER_JOIN_SECONDS = 2;

    private final TeacherFileTicketService tickets;
    private final ServerSocket serverSocket;
    private final ExecutorService connections;
    private final Set<Socket> liveSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile boolean running;

    public CourseFileServer(TeacherFileTicketService tickets) {
        this(tickets, configuredPort());
    }

    /**
     * 绑定文件端口并回填给票据服务；端口被占用时抛出运行时异常，由启动流程决定如何收尾。
     */
    public CourseFileServer(TeacherFileTicketService tickets, int port) {
        if (tickets == null) {
            throw new IllegalArgumentException("文件票据服务不能为空");
        }
        this.tickets = tickets;
        try {
            this.serverSocket = new ServerSocket(port);
        } catch (IOException failure) {
            throw new RuntimeException("创建文件服务 Socket 失败，端口：" + port, failure);
        }
        this.tickets.bindPort(this.serverSocket.getLocalPort());
        this.connections = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "course-file-connection");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 读取配置的文件端口；缺省为 {@value #DEFAULT_FILE_PORT}，非法值在启动时就拒绝而不是退化成默认端口。
     */
    public static int configuredPort() {
        String raw = System.getProperty(PORT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_FILE_PORT;
        }
        int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(PORT_PROPERTY + " 必须为端口号，实际为：" + raw);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(PORT_PROPERTY + " 必须为 1 至 65535 的端口号，实际为：" + port);
        }
        return port;
    }

    /**
     * 启动监听：在工作线程上接受短连接，不阻塞调用方（业务服务器自己占着主线程的接受循环）。
     */
    public synchronized void start() {
        if (running || stopped.get()) {
            return;
        }
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "course-file-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * 幂等停止：监听器、活跃短连接、连接线程池与临时文件一次性收干净。
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        running = false;
        try {
            serverSocket.close();
        } catch (IOException failure) {
            System.err.println("关闭文件服务 Socket 失败：" + failure.getMessage());
        }
        for (Socket socket : liveSockets) {
            closeQuietly(socket);
        }
        liveSockets.clear();
        connections.shutdownNow();
        // 等连接线程真正退出再删临时文件：还在写 .part 的线程持有该文件的句柄，Windows 上会让
        // 目录删除失败，于是清理被静默吞掉、临时目录活过停服。等待有上限，某个无视中断的线程
        // 最多拖慢停服 WORKER_JOIN_SECONDS 秒，绝不会让 stop() 挂死。
        try {
            if (!connections.awaitTermination(WORKER_JOIN_SECONDS, TimeUnit.SECONDS)) {
                System.err.println("文件连接线程未在 " + WORKER_JOIN_SECONDS + " 秒内退出，继续停止。");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // 票据服务持有临时目录；文件服务停止即代表不再有文件传输，未消费的票据与临时文件一并回收。
        tickets.close();
        System.out.println("VCampus 文件服务已停止。");
    }

    @Override
    public void close() {
        stop();
    }

    /** 文件监听端口；绑定后即为真实端口（测试用 0 绑定时也返回操作系统分配的那个）。 */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        System.out.println("VCampus 文件服务启动，端口：" + getPort());
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                liveSockets.add(socket);
                try {
                    connections.execute(() -> {
                        try {
                            new CourseFileConnection(socket, tickets).run();
                        } finally {
                            liveSockets.remove(socket);
                        }
                    });
                } catch (RejectedExecutionException busy) {
                    // 停服瞬间到达的连接：不能接就立刻关掉，绝不留下无人处理的 Socket。
                    liveSockets.remove(socket);
                    closeQuietly(socket);
                }
            } catch (IOException failure) {
                if (running) {
                    System.err.println("文件服务运行异常：" + failure.getMessage());
                }
                return;
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败不改变停止流程的结果。
        }
    }
}
