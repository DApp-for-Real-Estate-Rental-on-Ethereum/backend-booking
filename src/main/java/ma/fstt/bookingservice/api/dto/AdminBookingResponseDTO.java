package ma.fstt.bookingservice.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminBookingResponseDTO {
    private Long id;
    private Long userId;
    private String propertyId;
    
    // Property info
    private String propertyTitle;
    private String propertyAddress;
    private Long ownerId;
    
    // User info (tenant)
    private String tenantName;
    private String tenantEmail;
    
    // Owner info (host)
    private String hostName;
    private String hostEmail;
    
    // Booking dates
    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private Integer numberOfNights;
    
    // Pricing
    private Double totalPrice;
    private Integer longStayDiscountPercent;
    private Integer requestedNegotiationPercent;
    
    // Status and payment
    private String status;
    private String onChainTxHash;
    private Instant negotiationExpiresAt;
    
    // Timestamps
    private Instant createdAt;
    private Instant updatedAt;
}

