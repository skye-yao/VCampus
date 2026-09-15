package service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Optional proxy for paper requests only; leaves campus socket traffic unchanged. */
final class PaperNetworkConfig {
    private PaperNetworkConfig() {}

    static Proxy load() throws IOException {
        Path path = Path.of(System.getProperty("vcampus.paper.config", "config/paper-network.properties"));
        if (!Files.exists(path)) return null;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
        return parse(properties);
    }

    static Proxy parse(Properties properties) throws IOException {
        String type = properties.getProperty("proxy.type", "system").trim().toLowerCase(java.util.Locale.ROOT);
        if (type.equals("system")) return null;
        if (type.equals("direct")) return Proxy.NO_PROXY;
        if (!type.equals("http") && !type.equals("socks"))
            throw new IOException("论文代理类型应为 system、direct、http 或 socks");
        String host = properties.getProperty("proxy.host", "").trim();
        if (host.isEmpty() || host.contains("://") || host.contains("/") || host.contains("@"))
            throw new IOException("论文代理地址应仅填写主机名或 IP");
        try {
            int port = Integer.parseInt(properties.getProperty("proxy.port", "").trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return new Proxy(type.equals("http") ? Proxy.Type.HTTP : Proxy.Type.SOCKS,
                    new InetSocketAddress(host, port));
        } catch (NumberFormatException e) { throw new IOException("论文代理端口应在 1–65535 之间", e); }
    }
}
