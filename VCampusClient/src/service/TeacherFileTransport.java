package service;

import dto.course.teacher.TeacherFileTicketDTO;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * 教师成绩 Excel 的短连接传输抽象：票据来自业务 TCP，文件字节走独立端口。
 *
 * <p>真实实现按票据上的端口开一条短连接，传完即关；测试用假实现离线驱动，不需要真实服务器。
 * 两个方法都在后台线程上执行，返回的 Future 可以取消——离开上传页时取消即关闭短连接。
 */
public interface TeacherFileTransport {

    /**
     * 按票据上传本地文件；完成即代表服务端已收齐并核对通过。
     */
    CompletableFuture<Void> upload(TeacherFileTicketDTO ticket, Path file);

    /**
     * 按票据下载到指定目标文件：先写同目录临时文件，长度与摘要都核对成功后才移动到目标。
     */
    CompletableFuture<Void> download(TeacherFileTicketDTO ticket, Path file);
}
