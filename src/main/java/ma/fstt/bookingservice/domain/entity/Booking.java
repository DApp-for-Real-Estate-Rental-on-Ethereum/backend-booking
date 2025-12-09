package ma.fstt.bookingservice.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "bookings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "property_id")
    private String propertyId; // Changed from Long to String to support UUID from property-service

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    @Column(name = "check_out_date", nullable = false)
    private LocalDate checkOutDate;

    @Column(name = "total_price")
    private Double totalPrice;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "on_chain_tx_hash")
    private String onChainTxHash;

    @Column(name = "long_stay_discount_percent")
    private Integer longStayDiscountPercent;

    @Column(name = "requested_negotiation_percent")
    private Integer requestedNegotiationPercent;

    @Column(name = "negotiation_expires_at")
    private Instant negotiationExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
