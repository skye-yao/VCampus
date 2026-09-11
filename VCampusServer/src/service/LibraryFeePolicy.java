package service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

public final class LibraryFeePolicy {
    private LibraryFeePolicy() { }
    public static BigDecimal overdue(LocalDateTime due, LocalDateTime end) {
        if (end == null || !end.isAfter(due)) return new BigDecimal("0.00");
        Duration elapsed = Duration.between(due, end);
        long seconds = elapsed.getSeconds();
        long days = seconds / 86400 + (seconds % 86400 != 0 || elapsed.getNano() != 0 ? 1 : 0);
        return new BigDecimal("0.50").multiply(BigDecimal.valueOf(days));
    }
}
