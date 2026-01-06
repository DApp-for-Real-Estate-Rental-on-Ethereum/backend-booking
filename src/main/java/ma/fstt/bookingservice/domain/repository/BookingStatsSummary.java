package ma.fstt.bookingservice.domain.repository;

public interface BookingStatsSummary {
    Long getTotal();

    Long getCompleted();

    Long getCancelled();

    Double getAvgPrice();

    Double getAvgStayDays();

    Long getRecent();
}
