package ma.fstt.bookingservice.domain.entity;

/**
 * Booking Status Enum
 * 
 * Defines all possible states of a booking:
 * 
 * 1. PENDING_PAYMENT - Booking created, waiting for payment (مكروي - يحتاج إلى الدفع فقط)
 * 2. CONFIRMED - Payment completed, booking confirmed (تم الدفع)
 * 3. PENDING_NEGOTIATION - Negotiation in progress, waiting for host approval (في تفاوض)
 * 4. CANCELLED_BY_HOST - Cancelled by property owner (ملغي من طرف الهوست)
 * 5. CANCELLED_BY_TENANT - Cancelled by tenant (ملغي من طرف المستأجر)
 * 6. NEGOTIATION_REJECTED - Negotiation price rejected by host (رفض السعر المقترح)
 * 7. TENANT_CHECKED_OUT - Tenant checked out, waiting for host confirmation (المستأجر سجل الخروج)
 * 8. COMPLETED - Booking completed after host confirmation (اكتمل)
 * 9. IN_DISPUTE - Booking has an active reclamation/dispute (في نزاع/ريكلاماسيون)
 */
public enum BookingStatus {
    /**
     * Booking created, waiting for payment
     * مكروي - يحتاج إلى الدفع فقط
     */
    PENDING_PAYMENT,
    
    /**
     * Payment completed, booking confirmed
     * تم الدفع
     */
    CONFIRMED,
    
    /**
     * Negotiation in progress, waiting for host approval
     * في تفاوض - ينتظر موافقة صاحب البيت
     */
    PENDING_NEGOTIATION,
    
    /**
     * Cancelled by property owner
     * ملغي من طرف الهوست
     */
    CANCELLED_BY_HOST,
    
    /**
     * Cancelled by tenant
     * ملغي من طرف المستأجر
     */
    CANCELLED_BY_TENANT,
    
    /**
     * Negotiation price rejected by host
     * رفض السعر المقترح
     */
    NEGOTIATION_REJECTED,
    
    /**
     * Tenant checked out, waiting for host confirmation
     * المستأجر سجل الخروج - ينتظر تأكيد الهوست
     */
    TENANT_CHECKED_OUT,
    
    /**
     * Booking completed after host confirmation
     * اكتمل - بعد تأكيد الهوست
     */
    COMPLETED,
    
    /**
     * Booking has an active reclamation/dispute
     * في نزاع/ريكلاماسيون
     */
    IN_DISPUTE
}
