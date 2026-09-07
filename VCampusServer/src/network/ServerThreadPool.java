package network;

import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端线程池管理器
 *
 * <p>管理处理客户端请求的线程池。
 *
 * <p><b>使用方式：</b>
 * <pre>
 * ServerThreadPool pool = ServerThreadPool.getInstance();
 * pool.execute(() -> {
 *     // 处理客户端请求
 * });
 * </pre>
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
public class ServerThreadPool {

    /** 单例实例 */
    private static ServerThreadPool instance;

    /** 线程池 */
    private ExecutorService executorService;

    /** 最大线程数（从配置文件读取，默认32） */
    public static final int DEFAULT_MAX_CLIENTS = 32;
    private int maxThreads = DEFAULT_MAX_CLIENTS;

    /**
     * 私有构造方法（单例模式）
     */
    private ServerThreadPool() {
        loadConfig();
        this.executorService = new java.util.concurrent.ThreadPoolExecutor(
                maxThreads,maxThreads,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.SynchronousQueue<>(),new ServerThreadFactory(),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 获取单例实例
     */
    public static synchronized ServerThreadPool getInstance() {
        if (instance == null) {
            instance = new ServerThreadPool();
        }
        return instance;
    }

    /**
     * 提交任务到线程池
     *
     * @param task 待执行的任务
     */
    public void execute(Runnable task) {
        executorService.execute(task);
    }

    /**
     * 关闭线程池
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }

    /**
     * 加载配置文件
     */
    private void loadConfig() {
        try (InputStream is = ServerThreadPool.class.getClassLoader()
                .getResourceAsStream("resources/server.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String maxThreadsStr = props.getProperty("server.maxThreads");
                if (maxThreadsStr != null && !maxThreadsStr.trim().isEmpty()) {
                    maxThreads = Math.max(1,Math.min(128,Integer.parseInt(maxThreadsStr.trim())));
                }
            }
        } catch (Exception e) {
            // 配置文件不存在或读取失败，使用默认值
            System.out.println("使用默认线程池配置: maxThreads=" + maxThreads);
        }
    }

    /**
     * 自定义线程工厂，用于命名线程
     */
    private static class ServerThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ServerWorker-" + threadNumber.getAndIncrement());
            t.setDaemon(false);
            return t;
        }
    }
}
