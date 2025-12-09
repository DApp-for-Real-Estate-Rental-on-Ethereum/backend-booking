package ma.fstt.bookingservice.core.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.fstt.bookingservice.api.dto.BookingRequest;
import ma.fstt.bookingservice.core.service.BookingService;
import ma.fstt.bookingservice.domain.entity.Booking;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingConsumer {

    private final BookingService bookingService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "booking")
    public void handleBooking(BookingRequest request) {
        log.info("Received booking request: userId={}, propertyId={}, checkIn={}, checkOut={}, guests={}", 
                request.getUserId(), request.getPropertyId(), 
                request.getCheckInDate(), request.getCheckOutDate(), request.getNumberOfGuests());
        
        try {
            Booking booking = bookingService.createBooking(request);
            log.info("Booking created successfully with id: {}", booking.getId());
            
            sendBookingCreatedMessage(booking, request);
        } catch (Exception e) {
            log.error("Error creating booking", e);
            throw e;
        }
    }
    
    private void sendBookingCreatedMessage(Booking booking, BookingRequest request) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("bookingId", booking.getId());
            message.put("tenantId", booking.getUserId());
            message.put("propertyId", booking.getPropertyId());
            message.put("finalRentAmount", BigDecimal.valueOf(booking.getTotalPrice()));
            message.put("status", booking.getStatus());
            message.put("ownerId", null);
            message.put("depositAmount", null);
            
            rabbitTemplate.convertAndSend("booking.created", message);
            
            log.info("Sent booking.created message to RabbitMQ: bookingId={}, tenantId={}, propertyId={}, finalRentAmount={}, status={}",
                    booking.getId(), booking.getUserId(), booking.getPropertyId(), booking.getTotalPrice(), booking.getStatus());
        } catch (Exception e) {
            log.error("Error sending booking.created message", e);
        }
    }
}

