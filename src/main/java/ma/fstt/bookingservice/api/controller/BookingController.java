package ma.fstt.bookingservice.api.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.fstt.bookingservice.api.dto.BookingRequest;
import ma.fstt.bookingservice.api.dto.PropertyInfo;
import ma.fstt.bookingservice.api.dto.UpdateBookingRequest;
import ma.fstt.bookingservice.domain.entity.Booking;
import ma.fstt.bookingservice.domain.entity.Property;
import ma.fstt.bookingservice.domain.repository.BookingRepository;
import ma.fstt.bookingservice.domain.repository.PropertyRepository;
import ma.fstt.bookingservice.core.service.BookingService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import ma.fstt.bookingservice.api.dto.AdminBookingResponseDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

    private final BookingService bookingService;
    private final RabbitTemplate rabbitTemplate;
    private final PropertyRepository propertyRepository;
    private final BookingRepository bookingRepository;

    private boolean isAdmin(String rolesHeader) {
        return rolesHeader != null && rolesHeader.contains("ADMIN");
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
    }

    @GetMapping("/init")
    public ResponseEntity<Map<String, Object>> getInitData() {
        Map<String, Object> response = new HashMap<>();
        response.put("userId", 1);
        response.put("propertyId", 1);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> receiveInitData(@RequestBody Map<String, Object> initData) {
        Map<String, Object> response = new HashMap<>();
        try {
            Long userId = initData.get("userId") instanceof Number 
                ? ((Number) initData.get("userId")).longValue() 
                : Long.parseLong(initData.get("userId").toString());
            String propertyId = initData.get("propertyId").toString();
            
            log.info("Received init data: userId={}, propertyId={}", userId, propertyId);
            
            response.put("status", "received");
            response.put("message", "Init data received successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing init data", e);
            response.put("status", "error");
            response.put("message", "Failed to process init data: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> createBookingRequest(
            @RequestHeader(value = "X-User-Id", required = false) String requesterId,
            @RequestHeader(value = "X-User-Roles", required = false) String requesterRoles,
            @RequestBody BookingRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (requesterId == null) return unauthorized();
            // enforce authenticated user
            request.setUserId(Long.parseLong(requesterId));

            log.info("Received booking request: userId={}, propertyId={}",
                    request.getUserId(), request.getPropertyId());
            
            // Validate price before sending to RabbitMQ
            if (request.getRequestedPrice() != null) {
                String validationError = bookingService.validateRequestedPrice(request);
                if (validationError != null) {
                    response.put("status", "rejected");
                    response.put("message", validationError);
                    response.put("error", "PRICE_TOO_LOW");
                    return ResponseEntity.badRequest().body(response);
                }
            }
            
            rabbitTemplate.convertAndSend("booking", request);
            
            response.put("status", "accepted");
            response.put("message", "Booking request sent to queue");
            return ResponseEntity.accepted().body(response);
        } catch (Exception e) {
            log.error("Error sending booking request", e);
            response.put("status", "error");
            response.put("message", "Failed to send booking request: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/property/{id}")
    public ResponseEntity<PropertyInfo> getPropertyInfo(@PathVariable String id) {
        try {
            log.info("📥 Request to get property info for id: {}", id);
            PropertyInfo property = bookingService.getPropertyInfo(id);
            if (property == null) {
                log.warn("Property info not found for id: {}", id);
                return ResponseEntity.notFound().build();
            }
            log.info("✅ Property info retrieved successfully for id: {}", id);
            return ResponseEntity.ok(property);
        } catch (Exception e) {
            log.error("Error fetching property info for id: {}", id, e);
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/booking-id")
    public ResponseEntity<Map<String, Object>> getBookingId() {
        Map<String, Object> response = new HashMap<>();
        try {
            Long bookingId = bookingService.getLastBookingId();
            if (bookingId != null) {
                response.put("bookingId", bookingId);
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.noContent().build();
            }
        } catch (Exception e) {
            log.error("Error fetching booking ID", e);
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getBookings(
            @RequestHeader(value = "X-User-Id", required = false) String requesterId,
            @RequestHeader(value = "X-User-Roles", required = false) String requesterRoles,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long ownerId) {
        try {
            if (requesterId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            boolean admin = isAdmin(requesterRoles);
            List<Booking> bookings;
            if (tenantId != null) {
                if (!admin && !requesterId.equals(tenantId.toString())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                bookings = bookingService.getBookingsByUserId(tenantId);
            } else if (ownerId != null) {
                if (!admin && !requesterId.equals(ownerId.toString())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                bookings = bookingService.getBookingsByOwnerId(ownerId);
            } else {
                return ResponseEntity.ok(List.of());
            }
            
            List<Map<String, Object>> result = bookings.stream().map(booking -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", booking.getId());
                map.put("userId", booking.getUserId());
                map.put("propertyId", booking.getPropertyId());
                map.put("checkInDate", booking.getCheckInDate());
                map.put("checkOutDate", booking.getCheckOutDate());
                map.put("totalPrice", booking.getTotalPrice());
                map.put("status", booking.getStatus());
                map.put("longStayDiscountPercent", booking.getLongStayDiscountPercent());
                map.put("requestedNegotiationPercent", booking.getRequestedNegotiationPercent());
                map.put("negotiationExpiresAt", booking.getNegotiationExpiresAt());
                map.put("onChainTxHash", booking.getOnChainTxHash());
                return map;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching bookings", e);
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/pending")
    public ResponseEntity<List<Map<String, Object>>> getPendingBookings(
            @RequestHeader(value = "X-User-Id", required = false) String requesterId,
            @RequestHeader(value = "X-User-Roles", required = false) String requesterRoles,
            @RequestParam Long userId) {
        try {
            if (requesterId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            if (!isAdmin(requesterRoles) && !requesterId.equals(userId.toString())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            List<Booking> bookings = bookingService.getPendingBookingsByUserId(userId);
            List<Map<String, Object>> result = bookings.stream().map(booking -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", booking.getId());
                map.put("userId", booking.getUserId());
                map.put("propertyId", booking.getPropertyId());
                map.put("checkInDate", booking.getCheckInDate());
                map.put("checkOutDate", booking.getCheckOutDate());
                map.put("totalPrice", booking.getTotalPrice());
                map.put("status", booking.getStatus());
                map.put("longStayDiscountPercent", booking.getLongStayDiscountPercent());
                map.put("requestedNegotiationPercent", booking.getRequestedNegotiationPercent());
                map.put("negotiationExpiresAt", booking.getNegotiationExpiresAt());
                map.put("onChainTxHash", booking.getOnChainTxHash());
                return map;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching pending bookings", e);
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/payment")
    public ResponseEntity<List<Map<String, Object>>> getAwaitingPaymentBookings(
            @RequestHeader(value = "X-User-Id", required = false) String requesterId,
            @RequestHeader(value = "X-User-Roles", required = false) String requesterRoles,
            @RequestParam Long userId) {
        try {
            if (requesterId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            if (!isAdmin(requesterRoles) && !requesterId.equals(userId.toString())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            List<Booking> bookings = bookingService.getAwaitingPaymentBookingsByUserId(userId);
            List<Map<String, Object>> result = bookings.stream().map(booking -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", booking.getId());
                map.put("userId", booking.getUserId());
                map.put("propertyId", booking.getPropertyId());
                map.put("checkInDate", booking.getCheckInDate());
                map.put("checkOutDate", booking.getCheckOutDate());
                map.put("totalPrice", booking.getTotalPrice());
                map.put("status", booking.getStatus());
                map.put("longStayDiscountPercent", booking.getLongStayDiscountPercent());
                map.put("requestedNegotiationPercent", booking.getRequestedNegotiationPercent());
                map.put("negotiationExpiresAt", booking.getNegotiationExpiresAt());
                map.put("onChainTxHash", booking.getOnChainTxHash());
                return map;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching awaiting payment bookings", e);
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/negotiations")
    public ResponseEntity<List<Map<String, Object>>> getPendingNegotiations(
            @RequestHeader(value = "X-User-Id", required = false) String requesterId,
            @RequestHeader(value = "X-User-Roles", required = false) String requesterRoles,
            @RequestParam Long ownerId) {
        try {
            if (requesterId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            if (!isAdmin(requesterRoles) && !requesterId.equals(ownerId.toString())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            List<Booking> bookings = bookingService.getPendingNegotiationsByOwnerId(ownerId);
            List<Map<String, Object>> result = bookings.stream().map(booking -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", booking.getId());
                map.put("userId", booking.getUserId());
                map.put("propertyId", booking.getPropertyId());
                map.put("checkInDate", booking.getCheckInDate());
                map.put("checkOutDate", booking.getCheckOutDate());
                map.put("totalPrice", booking.getTotalPrice());
                map.put("status", booking.getStatus());
                map.put("longStayDiscountPercent", booking.getLongStayDiscountPercent());
                map.put("requestedNegotiationPercent", booking.getRequestedNegotiationPercent());
                map.put("negotiationExpiresAt", booking.getNegotiationExpiresAt());
                map.put("onChainTxHash", booking.getOnChainTxHash());
                return map;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching pending negotiations", e);
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Map<String, Object>> acceptNegotiation(
            @PathVariable Long id,
            @RequestParam Long ownerId,
            @RequestHeader(value = "X-User-Id", required = false) String requesterId,
            @RequestHeader(value = "X-User-Roles", required = false) String requesterRoles) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (requesterId == null) return unauthorized();
            if (!isAdmin(requesterRoles) && !requesterId.equals(ownerId.toString())) return forbidden();
            Booking booking = bookingService.acceptNegotiation(id, ownerId);
            Map<String, Object> map = new HashMap<>();
            map.put("id", booking.getId());
            map.put("userId", booking.getUserId());
            map.put("propertyId", booking.getPropertyId());
            map.put("checkInDate", booking.getCheckInDate());
            map.put("checkOutDate", booking.getCheckOutDate());
            map.put("totalPrice", booking.getTotalPrice());
            map.put("status", booking.getStatus());
            map.put("longStayDiscountPercent", booking.getLongStayDiscountPercent());
            map.put("requestedNegotiationPercent", booking.getRequestedNegotiationPercent());
            map.put("negotiationExpiresAt", booking.getNegotiationExpiresAt());
            response.put("booking", map);
            response.put("message", "Negotiation accepted successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error accepting negotiation for booking id: {}", id, e);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectNegotiation(
            @PathVariable Long id,
            @RequestParam Long ownerId,
            @RequestHeader(value = "X-User-Id", required = false) String requesterId,
            @RequestHeader(value = "X-User-Roles", required = false) String requesterRoles) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (requesterId == null) return unauthorized();
            if (!isAdmin(requesterRoles) && !requesterId.equals(ownerId.toString())) return forbidden();
            Booking booking = bookingService.rejectNegotiation(id, ownerId);
            Map<String, Object> map = new HashMap<>();
            map.put("id", booking.getId());
            map.put("userId", booking.getUserId());
            map.put("propertyId", booking.getPropertyId());
            map.put("checkInDate", booking.getCheckInDate());
            map.put("checkOutDate", booking.getCheckOutDate());
            map.put("totalPrice", booking.getTotalPrice());
            map.put("status", booking.getStatus());
            map.put("longStayDiscountPercent", booking.getLongStayDiscountPercent());
            map.put("requestedNegotiationPercent", booking.getRequestedNegotiationPercent());
            map.put("negotiationExpiresAt", booking.getNegotiationExpiresAt());
            response.put("booking", map);
            response.put("message", "Negotiation rejected. The tenant can change the price.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error rejecting negotiation for booking id: {}", id, e);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getBookingById(@PathVariable Long id) {
        try {
            Booking booking = bookingService.getBookingById(id);
            Map<String, Object> map = new HashMap<>();
            map.put("id", booking.getId());
            map.put("userId", booking.getUserId());
            map.put("propertyId", booking.getPropertyId());
            map.put("checkInDate", booking.getCheckInDate());
            map.put("checkOutDate", booking.getCheckOutDate());
            map.put("totalPrice", booking.getTotalPrice());
            map.put("status", booking.getStatus());
            map.put("longStayDiscountPercent", booking.getLongStayDiscountPercent());
            map.put("requestedNegotiationPercent", booking.getRequestedNegotiationPercent());
            map.put("negotiationExpiresAt", booking.getNegotiationExpiresAt());
            map.put("onChainTxHash", booking.getOnChainTxHash());
            map.put("createdAt", booking.getCreatedAt());
            map.put("updatedAt", booking.getUpdatedAt());
            return ResponseEntity.ok(map);
        } catch (Exception e) {
            log.error("Error fetching booking with id: {}", id, e);
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateBooking(
            @PathVariable Long id,
            @RequestBody UpdateBookingRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String requesterId,
            @RequestHeader(value = "X-User-Roles", required = false) String requesterRoles) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (requesterId == null) return unauthorized();
            Booking existing = bookingService.getBookingById(id);
            if (!isAdmin(requesterRoles) && !requesterId.equals(existing.getUserId().toString())) {
                return forbidden();
            }
            Booking updatedBooking = bookingService.updateBooking(id, request);
            
            Map<String, Object> map = new HashMap<>();
            map.put("id", updatedBooking.getId());
            map.put("userId", updatedBooking.getUserId());
            map.put("propertyId", updatedBooking.getPropertyId());
            map.put("checkInDate", updatedBooking.getCheckInDate());
            map.put("checkOutDate", updatedBooking.getCheckOutDate());
            map.put("totalPrice", updatedBooking.getTotalPrice());
            map.put("status", updatedBooking.getStatus());
            map.put("longStayDiscountPercent", updatedBooking.getLongStayDiscountPercent());
            map.put("requestedNegotiationPercent", updatedBooking.getRequestedNegotiationPercent());
            map.put("negotiationExpiresAt", updatedBooking.getNegotiationExpiresAt());
            response.put("booking", map);
            response.put("hasNegotiation", updatedBooking.getRequestedNegotiationPercent() != null);
            response.put("message", "Booking updated successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error updating booking with id: {}", id, e);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> updateBookingStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "X-User-Id", required = false) String requesterId,
            @RequestHeader(value = "X-User-Roles", required = false) String requesterRoles) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (requesterId == null) return unauthorized();
            String status = request.get("status");
            if (status == null || status.trim().isEmpty()) {
                response.put("error", "Status is required");
                return ResponseEntity.badRequest().body(response);
            }

            Booking booking = bookingService.getBookingById(id);
            if (!isAdmin(requesterRoles) && !requesterId.equals(booking.getUserId().toString())) {
                return forbidden();
            }
            String previousStatus = booking.getStatus();
            log.info("📋 Updating booking status: id={}, previousStatus={}, newStatus={}", id, previousStatus, status);
            
            booking.setStatus(status);
            booking = bookingRepository.save(booking);
            
            log.info("✅ Booking status saved: id={}, status={}", booking.getId(), booking.getStatus());
            
            if ("CONFIRMED".equals(status) && !"CONFIRMED".equals(previousStatus)) {
                log.info("🔄 Booking {} status changed to CONFIRMED (from {}), cancelling overlapping bookings...", id, previousStatus);
                try {
                    bookingService.cancelOverlappingBookings(id);
                    log.info("✅ cancelOverlappingBookings completed for booking {}", id);
                } catch (Exception e) {
                    log.error("❌ Error in cancelOverlappingBookings for booking {}: {}", id, e.getMessage(), e);
                }
            } else {
                log.info("ℹ️ Not cancelling overlapping bookings: status={}, previousStatus={}", status, previousStatus);
            }
            
            Map<String, Object> map = new HashMap<>();
            map.put("id", booking.getId());
            map.put("status", booking.getStatus());
            response.put("booking", map);
            response.put("message", "Booking status updated successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error updating booking status with id: {}", id, e);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> cancelBooking(
            @PathVariable Long id,
            @RequestParam Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Booking cancelledBooking = bookingService.cancelBookingByTenant(id, userId);
            Map<String, Object> map = new HashMap<>();
            map.put("id", cancelledBooking.getId());
            map.put("userId", cancelledBooking.getUserId());
            map.put("propertyId", cancelledBooking.getPropertyId());
            map.put("checkInDate", cancelledBooking.getCheckInDate());
            map.put("checkOutDate", cancelledBooking.getCheckOutDate());
            map.put("totalPrice", cancelledBooking.getTotalPrice());
            map.put("status", cancelledBooking.getStatus());
            response.put("booking", map);
            response.put("message", "Booking cancelled successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error cancelling booking with id: {}", id, e);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> getCurrentBooking(@RequestParam Long userId) {
        try {
            Optional<Booking> bookingOpt = bookingService.getCurrentBookingByUserId(userId);
            if (bookingOpt.isPresent()) {
                Booking booking = bookingOpt.get();
                Map<String, Object> map = new HashMap<>();
                map.put("id", booking.getId());
                map.put("userId", booking.getUserId());
                map.put("propertyId", booking.getPropertyId());
                map.put("checkInDate", booking.getCheckInDate());
                map.put("checkOutDate", booking.getCheckOutDate());
                map.put("totalPrice", booking.getTotalPrice());
                map.put("status", booking.getStatus());
                map.put("longStayDiscountPercent", booking.getLongStayDiscountPercent());
                map.put("requestedNegotiationPercent", booking.getRequestedNegotiationPercent());
                map.put("negotiationExpiresAt", booking.getNegotiationExpiresAt());
                map.put("onChainTxHash", booking.getOnChainTxHash());
                return ResponseEntity.ok(map);
            } else {
                return ResponseEntity.noContent().build();
            }
        } catch (Exception e) {
            log.error("Error fetching current booking", e);
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/current/owner")
    public ResponseEntity<List<Map<String, Object>>> getCurrentBookingsByOwner(@RequestParam Long ownerId) {
        try {
            List<Booking> bookings = bookingService.getCurrentBookingsByOwnerId(ownerId);
            List<Map<String, Object>> result = bookings.stream().map(booking -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", booking.getId());
                map.put("userId", booking.getUserId());
                map.put("propertyId", booking.getPropertyId());
                map.put("checkInDate", booking.getCheckInDate());
                map.put("checkOutDate", booking.getCheckOutDate());
                map.put("totalPrice", booking.getTotalPrice());
                map.put("status", booking.getStatus());
                map.put("longStayDiscountPercent", booking.getLongStayDiscountPercent());
                map.put("requestedNegotiationPercent", booking.getRequestedNegotiationPercent());
                map.put("negotiationExpiresAt", booking.getNegotiationExpiresAt());
                map.put("onChainTxHash", booking.getOnChainTxHash());
                return map;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching current bookings by owner", e);
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/confirmed/owner")
    public ResponseEntity<List<Map<String, Object>>> getConfirmedBookingsByOwner(@RequestParam Long ownerId) {
        try {
            List<Booking> bookings = bookingService.getConfirmedBookingsByOwnerId(ownerId);
            List<Map<String, Object>> result = bookings.stream().map(booking -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", booking.getId());
                map.put("userId", booking.getUserId());
                map.put("propertyId", booking.getPropertyId());
                map.put("checkInDate", booking.getCheckInDate());
                map.put("checkOutDate", booking.getCheckOutDate());
                map.put("totalPrice", booking.getTotalPrice());
                map.put("status", booking.getStatus());
                map.put("longStayDiscountPercent", booking.getLongStayDiscountPercent());
                map.put("requestedNegotiationPercent", booking.getRequestedNegotiationPercent());
                map.put("negotiationExpiresAt", booking.getNegotiationExpiresAt());
                map.put("onChainTxHash", booking.getOnChainTxHash());
                return map;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching confirmed bookings by owner", e);
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/confirmed/property/{propertyId}")
    public ResponseEntity<List<Map<String, Object>>> getConfirmedBookingsByProperty(@PathVariable String propertyId) {
        try {
            List<Booking> bookings = bookingService.getConfirmedBookingsByPropertyId(propertyId);
            List<Map<String, Object>> result = bookings.stream().map(booking -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", booking.getId());
                map.put("userId", booking.getUserId());
                map.put("propertyId", booking.getPropertyId());
                map.put("checkInDate", booking.getCheckInDate());
                map.put("checkOutDate", booking.getCheckOutDate());
                map.put("totalPrice", booking.getTotalPrice());
                map.put("status", booking.getStatus());
                return map;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching confirmed bookings by property", e);
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/{id}/dispute")
    public ResponseEntity<Map<String, Object>> reportDispute(
            @PathVariable Long id,
            @RequestParam Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Booking booking = bookingService.reportDispute(id, userId);
            Map<String, Object> map = new HashMap<>();
            map.put("id", booking.getId());
            map.put("userId", booking.getUserId());
            map.put("propertyId", booking.getPropertyId());
            map.put("checkInDate", booking.getCheckInDate());
            map.put("checkOutDate", booking.getCheckOutDate());
            map.put("totalPrice", booking.getTotalPrice());
            map.put("status", booking.getStatus());
            response.put("booking", map);
            response.put("message", "Dispute reported successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error reporting dispute for booking id: {}", id, e);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Tenant checkout endpoint (changes status to TENANT_CHECKED_OUT)
     */
    @PostMapping("/{id}/checkout/tenant")
    public ResponseEntity<Map<String, Object>> tenantCheckout(
            @PathVariable Long id,
            @RequestParam Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Booking booking = bookingService.tenantCheckout(id, userId);
            Map<String, Object> map = new HashMap<>();
            map.put("id", booking.getId());
            map.put("userId", booking.getUserId());
            map.put("propertyId", booking.getPropertyId());
            map.put("checkInDate", booking.getCheckInDate());
            map.put("checkOutDate", booking.getCheckOutDate());
            map.put("totalPrice", booking.getTotalPrice());
            map.put("status", booking.getStatus());
            response.put("booking", map);
            response.put("message", "Tenant checked out successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error in tenant checkout for booking id: {}", id, e);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Owner confirm checkout endpoint (changes status to COMPLETED)
     */
    @PostMapping("/{id}/checkout/owner")
    public ResponseEntity<Map<String, Object>> ownerConfirmCheckout(
            @PathVariable Long id,
            @RequestParam Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Booking booking = bookingService.ownerConfirmCheckout(id, userId);
            Map<String, Object> map = new HashMap<>();
            map.put("id", booking.getId());
            map.put("userId", booking.getUserId());
            map.put("propertyId", booking.getPropertyId());
            map.put("checkInDate", booking.getCheckInDate());
            map.put("checkOutDate", booking.getCheckOutDate());
            map.put("totalPrice", booking.getTotalPrice());
            map.put("status", booking.getStatus());
            response.put("booking", map);
            response.put("message", "Owner confirmed checkout successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error in owner confirm checkout for booking id: {}", id, e);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * @deprecated Use /checkout/tenant or /checkout/owner instead
     */
    @Deprecated
    @PostMapping("/{id}/checkout")
    public ResponseEntity<Map<String, Object>> markAsCheckedOut(
            @PathVariable Long id,
            @RequestParam Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Booking booking = bookingService.markAsCheckedOut(id, userId);
            Map<String, Object> map = new HashMap<>();
            map.put("id", booking.getId());
            map.put("userId", booking.getUserId());
            map.put("propertyId", booking.getPropertyId());
            map.put("checkInDate", booking.getCheckInDate());
            map.put("checkOutDate", booking.getCheckOutDate());
            map.put("totalPrice", booking.getTotalPrice());
            map.put("status", booking.getStatus());
            response.put("booking", map);
            response.put("message", "Booking marked as checked out successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error marking booking as checked out with id: {}", id, e);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping(value = "/{id}/reclamation", consumes = {"multipart/form-data"})
    public ResponseEntity<Map<String, Object>> createReclamation(
            @PathVariable Long id,
            @RequestParam("userId") Long userId,
            @RequestParam("complainantRole") String complainantRole,
            @RequestParam("reclamationType") String reclamationType,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "images", required = false) MultipartFile[] images) {
        Map<String, Object> response = new HashMap<>();
        try {
            // Validate images count (max 3)
            if (images != null && images.length > 3) {
                throw new RuntimeException("Maximum 3 images allowed");
            }

            // Get booking to verify user role
            Booking booking = bookingService.getBookingById(id);
            Property property = null;
            String propertyId = booking.getPropertyId();
            if (propertyId != null) {
                property = propertyRepository.findById(propertyId).orElse(null);
            }

            // Verify role matches
            boolean isTenant = booking.getUserId().equals(userId);
            boolean isOwner = property != null && property.getOwnerId().equals(userId);
            
            if (!isTenant && !isOwner) {
                throw new RuntimeException("User is not associated with this booking");
            }

            // Auto-detect role if not provided or incorrect
            if (isTenant && !"GUEST".equals(complainantRole)) {
                complainantRole = "GUEST";
            } else if (isOwner && !"HOST".equals(complainantRole)) {
                complainantRole = "HOST";
            }

            Map<String, Object> reclamationMessage = new HashMap<>();
            reclamationMessage.put("bookingId", id);
            reclamationMessage.put("userId", userId);
            reclamationMessage.put("complainantRole", complainantRole);
            reclamationMessage.put("reclamationType", reclamationType);
            reclamationMessage.put("title", title != null ? title : "");
            reclamationMessage.put("description", description != null ? description : "");
            
            // Add image info (file names and sizes) - actual file storage will be handled by reclamation service
            if (images != null && images.length > 0) {
                java.util.List<Map<String, Object>> imageInfo = new java.util.ArrayList<>();
                for (MultipartFile image : images) {
                    if (!image.isEmpty()) {
                        Map<String, Object> imgInfo = new HashMap<>();
                        imgInfo.put("originalFilename", image.getOriginalFilename());
                        imgInfo.put("size", image.getSize());
                        imgInfo.put("contentType", image.getContentType());
                        imageInfo.add(imgInfo);
                    }
                }
                reclamationMessage.put("images", imageInfo);
            }

            rabbitTemplate.convertAndSend("reclamation", reclamationMessage);

            response.put("status", "success");
            response.put("message", "Reclamation request sent successfully");
            response.put("reclamationId", "pending"); // Will be created async, frontend will need to poll or use webhook
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error creating reclamation for booking id: {}", id, e);
            response.put("status", "error");
            response.put("message", "Failed to create reclamation: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/admin/all")
    public ResponseEntity<List<AdminBookingResponseDTO>> getAllBookingsForAdmin(
            @RequestHeader(value = "X-User-Roles", required = false) String userRoles
    ) {
        try {
            // Check if user has ADMIN role
            if (userRoles == null || !userRoles.contains("ADMIN")) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
            }
            
            List<AdminBookingResponseDTO> bookings = bookingService.getAllBookingsForAdmin();
            return ResponseEntity.ok(bookings);
        } catch (Exception e) {
            log.error("Error fetching all bookings for admin", e);
            return ResponseEntity.status(500).build();
        }
    }
}

