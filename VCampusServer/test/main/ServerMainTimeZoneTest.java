package main;

import java.util.TimeZone;

public final class ServerMainTimeZoneTest {
    private ServerMainTimeZoneTest() {
    }

    public static void main(String[] args) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            ServerMain.configureTimeZone();
            require("UTC".equals(TimeZone.getDefault().getID()),
                    "server startup must select UTC before database and scheduler work");
        } finally {
            TimeZone.setDefault(original);
        }
        System.out.println("Server main time-zone test passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
