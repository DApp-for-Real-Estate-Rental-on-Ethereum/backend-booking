package ma.fstt.bookingservice.api.dto;

public record BookingStatsDTO(
        long total,
        long completed,
        long cancelled,
        Double avgPrice,
        Double avgStayDays,
        long recentLast6Months) {
}
