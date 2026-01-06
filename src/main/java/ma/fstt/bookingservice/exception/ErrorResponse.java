package ma.fstt.bookingservice.exception;

import java.time.LocalDateTime;

public record ErrorResponse(
        int status,
        String message,
        String timestamp) {
    public ErrorResponse(int status, String message) {
        this(status, message, LocalDateTime.now().toString());
    }
}
