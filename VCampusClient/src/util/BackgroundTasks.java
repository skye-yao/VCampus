package util;
import javafx.concurrent.Task;
import java.util.concurrent.*;
/** 导出任务有界，拒绝时沿用 Task 的失败处理。 */
public final class BackgroundTasks {
    private static final ExecutorService EXPORTS=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"file-export");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public static void export(Task<?> task){
        try{EXPORTS.execute(task);}catch(RejectedExecutionException e){
            javafx.application.Platform.runLater(()->{task.cancel();util.AlertUtil.showWarning("导出繁忙","导出任务已满，请稍后重试。");});
        }
    }
    public static void shutdown(){EXPORTS.shutdownNow();}
}
