package ma.fstt.bookingservice.domain.repository;

import ma.fstt.bookingservice.domain.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Find all bookings for a specific property
     */
    List<Booking> findByPropertyId(String propertyId);

    List<Booking> findByUserId(Long userId);

    /**
     * Find bookings that overlap with the given date range for a specific property
     * A booking overlaps if:
     * - Its check-in date is before or on the given check-out date AND
     * - Its check-out date is after or on the given check-in date
     * 
     * We exclude only COMPLETED and CANCELLED statuses, but include:
     * - PENDING_PAYMENT (should be cancelled)
     * - PENDING_NEGOTIATION (should be cancelled)
     * - NEGOTIATION_REJECTED (should be cancelled)
     * - CONFIRMED (should be cancelled if overlapping - though this shouldn't
     * happen)
     * - TENANT_CHECKED_OUT (should be cancelled if overlapping)
     */
    @Query("SELECT b FROM Booking b WHERE b.propertyId = :propertyId " +
            "AND b.id != :excludeBookingId " +
            "AND b.checkInDate <= :checkOutDate " +
            "AND b.checkOutDate >= :checkInDate " +
            "AND b.status NOT IN ('COMPLETED', 'CANCELLED')")
    List<Booking> findOverlappingBookings(
            @Param("propertyId") String propertyId,
            @Param("excludeBookingId") Long excludeBookingId,
            @Param("checkInDate") LocalDate checkInDate,
            @Param("checkOutDate") LocalDate checkOutDate);

    @Query(value = """
            SELECT
                COUNT(*) as total,
                COUNT(CASE WHEN status IN ('COMPLETED', 'TENANT_CHECKED_OUT', 'CONFIRMED') THEN 1 END) as completed,
                COUNT(CASE WHEN status LIKE 'CANCELLED%' THEN 1 END) as cancelled,
                AVG(total_price) as avgPrice,
                AVG(check_out_date - check_in_date) as avgStayDays,
                COUNT(CASE WHEN created_at > :sixMonthsAgo THEN 1 END) as recent
            FROM bookings
            WHERE user_id = :userId
            """, nativeQuery = true)
    BookingStatsSummary getBookingStats(@Param("userId") Long userId,
            @Param("sixMonthsAgo") java.time.Instant sixMonthsAgo);
}
