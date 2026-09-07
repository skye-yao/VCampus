package protocol;

import java.io.Serializable;

public record LockResponse(boolean success, String lockToken, long expiresAt,
                           String message) implements Serializable {}
