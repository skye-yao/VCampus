package util;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Date constraints shared by information-management clients and the server. */
public final class InformationDateRules {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private InformationDateRules() {}

    public static LocalDate today() { return LocalDate.now(ZONE); }

    public static boolean isFuture(LocalDate value) {
        return value != null && value.isAfter(today());
    }

    public static void requireNotFuture(LocalDate value, String label) {
        if (isFuture(value)) throw new IllegalArgumentException(label + "不能晚于今天");
    }

    public static void requireNotFuture(Date value, String label) {
        if (value != null) requireNotFuture(value.toLocalDate(), label);
    }

    public static void requireNotFuture(String value, String label) {
        if (value == null || value.isBlank()) return;
        try {
            requireNotFuture(LocalDate.parse(value.trim()), label);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(label + "格式无效");
        }
    }
}
