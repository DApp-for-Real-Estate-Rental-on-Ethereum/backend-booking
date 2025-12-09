package ma.fstt.bookingservice.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropertyInfo {
    private String id; // Changed from Long to String to support UUID
    private Long ownerId; // Keep as Long (userId from user-service is Long)
    private BigDecimal pricePerNight;
    private Boolean isNegotiable;
    private Boolean discountEnabled;
    private Integer maxNegotiationPercent;
    private Double negotiationPercentage; // Negotiation percentage (used as nicotine percentage for price calculation)
}

