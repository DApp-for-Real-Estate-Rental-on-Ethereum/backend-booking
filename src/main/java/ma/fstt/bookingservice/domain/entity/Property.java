package ma.fstt.bookingservice.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "properties")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Property {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id; // Changed from Long to String to support UUID from property-service

    @Column(name = "user_id", nullable = false) // Database column is user_id, not owner_id
    private Long ownerId; // Keep field name as ownerId for code compatibility

    @Column(name = "price", nullable = false)
    private Double price;

    // These fields are not stored in the database - they are fetched from property-service
    // Using @Transient to prevent JPA from trying to map them to database columns
    @Transient
    @Builder.Default
    private Boolean isNegotiable = false;

    @Transient
    @Builder.Default
    private Boolean discountEnabled = false;

    @Transient
    private Integer maxNegotiationPercent;
}

