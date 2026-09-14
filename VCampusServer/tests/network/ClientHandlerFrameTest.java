package network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

/** 服务端拒绝超长请求行，避免在 JSON 解析前无界分配内存。 */
public class ClientHandlerFrameTest {
    public static void main(String[] args) throws Exception {
        BufferedReader normal = new BufferedReader(new StringReader("abc\r\nnext\n"));
        if (!"abc".equals(ClientHandler.readBoundedLine(normal, 3))) throw new AssertionError("CRLF 读取错误");
        if (!"next".equals(ClientHandler.readBoundedLine(normal, 4))) throw new AssertionError("第二行读取错误");
        if (ClientHandler.readBoundedLine(normal, 4) != null) throw new AssertionError("EOF 读取错误");
        try {
            ClientHandler.readBoundedLine(new BufferedReader(new StringReader("12345\n")), 4);
            throw new AssertionError("超长请求未被拒绝");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("长度")) throw expected;
        }
        System.out.println("ClientHandlerFrameTest PASS");
    }
}
