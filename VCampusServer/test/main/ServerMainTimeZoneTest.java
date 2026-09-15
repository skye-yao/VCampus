package main;

import java.util.TimeZone;

public final class ServerMainTimeZoneTest {
    private ServerMainTimeZoneTest() {
    }

    public static void main(String[] args) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            ServerMain.configureRuntime();
            require("Asia/Shanghai".equals(TimeZone.getDefault().getID()),
                    "server startup must preserve the JVM default time zone for non-course modules");
            require("true".equals(System.getProperty("java.awt.headless")),
                    "server startup must keep headless image processing enabled");
        } finally {
            TimeZone.setDefault(original);
        }
        System.out.println("Server runtime configuration test passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
