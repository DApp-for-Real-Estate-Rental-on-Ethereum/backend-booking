package ma.fstt.bookingservice.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookingRequest {
    private Long userId;
    private String propertyId; // Changed from Long to String to support UUID from property-service
    private String checkInDate;
    private String checkOutDate;
    private Integer numberOfGuests;
    private BigDecimal requestedPrice;
}
