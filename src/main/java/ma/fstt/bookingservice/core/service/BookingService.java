package ma.fstt.bookingservice.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.fstt.bookingservice.api.dto.BookingRequest;
import ma.fstt.bookingservice.api.dto.PropertyInfo;
import ma.fstt.bookingservice.api.dto.UpdateBookingRequest;
import ma.fstt.bookingservice.domain.entity.Booking;
import ma.fstt.bookingservice.domain.entity.Property;
import ma.fstt.bookingservice.domain.repository.BookingRepository;
import ma.fstt.bookingservice.domain.repository.PropertyRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final PropertyRepository propertyRepository;
    private final org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
    
    @org.springframework.beans.factory.annotation.Value("${property.service.url:http://localhost:8081}")
    private String propertyServiceUrl;
    
    @org.springframework.beans.factory.annotation.Value("${user.service.url:http://localhost:8082}")
    private String userServiceUrl;

    @Transactional
    public Booking createBooking(BookingRequest request) {
        log.info("Creating booking for userId={}, propertyId={}", request.getUserId(), request.getPropertyId());

        PropertyInfo propertyInfo = getPropertyInfoFromPropertyService(request.getPropertyId());
        BigDecimal pricePerNight = propertyInfo.getPricePerNight();

        LocalDate checkIn = LocalDate.parse(request.getCheckInDate());
        LocalDate checkOut = LocalDate.parse(request.getCheckOutDate());
        int nights = (int) ChronoUnit.DAYS.between(checkIn, checkOut);

        BigDecimal baseRent = pricePerNight.multiply(BigDecimal.valueOf(nights))
                .setScale(2, RoundingMode.HALF_UP);

        Integer discountPercent = calculateDiscount(nights, propertyInfo.getDiscountEnabled());
        BigDecimal discountAmount = baseRent.multiply(BigDecimal.valueOf(discountPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal finalRent = baseRent.subtract(discountAmount);

        String status = "PENDING_PAYMENT";
        Integer requestedNegotiationPercent = null;
        Instant negotiationExpiresAt = null;

        if (request.getRequestedPrice() != null) {
            // Get negotiation percentage from property
            Double negotiationPercentage = propertyInfo.getNegotiationPercentage();
            
            if (negotiationPercentage != null && negotiationPercentage > 0) {
                BigDecimal percentageDiscount = BigDecimal.valueOf(negotiationPercentage)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                BigDecimal minPrice = finalRent.multiply(BigDecimal.ONE.subtract(percentageDiscount))
                        .setScale(2, RoundingMode.HALF_UP);
                
                // Check if requested price is acceptable
                if (request.getRequestedPrice().compareTo(minPrice) >= 0 && 
                    request.getRequestedPrice().compareTo(finalRent) <= 0) {
                    // Price is acceptable - set status to PENDING_NEGOTIATION
                    status = "PENDING_NEGOTIATION";
                    finalRent = request.getRequestedPrice();
                    BigDecimal discount = baseRent.subtract(request.getRequestedPrice());
                    requestedNegotiationPercent = discount.divide(baseRent, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(0, RoundingMode.HALF_UP)
                            .intValue();
                    negotiationExpiresAt = Instant.now().plusSeconds(24 * 60 * 60);
                    log.info("Price accepted! Negotiation: {}%, Status set to: PENDING_NEGOTIATION", requestedNegotiationPercent);
                } else if (request.getRequestedPrice().compareTo(minPrice) < 0) {
                    log.warn("Requested price is too low, using final rent instead. Minimum required: {}", minPrice);
                    status = "PENDING_PAYMENT";
                    requestedNegotiationPercent = null;
                    negotiationExpiresAt = null;
                } else {
                    status = "PENDING_PAYMENT";
                    requestedNegotiationPercent = null;
                    negotiationExpiresAt = null;
                    log.info("Price is higher than final rent, using final rent.");
                }
            } else {
                BigDecimal minPrice = finalRent.multiply(BigDecimal.valueOf(0.80))
                        .setScale(2, RoundingMode.HALF_UP);
                if (request.getRequestedPrice().compareTo(minPrice) >= 0 && 
                    request.getRequestedPrice().compareTo(finalRent) <= 0) {
                    // Price is acceptable - set status to PENDING_NEGOTIATION
                    status = "PENDING_NEGOTIATION";
                    finalRent = request.getRequestedPrice();
                    BigDecimal discount = baseRent.subtract(request.getRequestedPrice());
                    requestedNegotiationPercent = discount.divide(baseRent, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(0, RoundingMode.HALF_UP)
                            .intValue();
                    negotiationExpiresAt = Instant.now().plusSeconds(24 * 60 * 60);
                    log.info("Price accepted! Negotiation: {}%, Status set to: PENDING_NEGOTIATION", requestedNegotiationPercent);
                }
            }
        }

        Booking booking = Booking.builder()
                .userId(request.getUserId())
                .propertyId(request.getPropertyId())
                .checkInDate(checkIn)
                .checkOutDate(checkOut)
                .totalPrice(finalRent.doubleValue())
                .status(status)
                .longStayDiscountPercent(discountPercent)
                .requestedNegotiationPercent(requestedNegotiationPercent)
                .negotiationExpiresAt(negotiationExpiresAt)
                .build();

        bookingRepository.save(booking);
        log.info("Booking saved with id: {}", booking.getId());
        
        return booking;
    }

    public String validateRequestedPrice(BookingRequest request) {
        try {
            PropertyInfo propertyInfo = getPropertyInfoFromPropertyService(request.getPropertyId());
            BigDecimal pricePerNight = propertyInfo.getPricePerNight();
            
            LocalDate checkIn = LocalDate.parse(request.getCheckInDate());
            LocalDate checkOut = LocalDate.parse(request.getCheckOutDate());
            int nights = (int) ChronoUnit.DAYS.between(checkIn, checkOut);
            
            BigDecimal baseRent = pricePerNight.multiply(BigDecimal.valueOf(nights))
                    .setScale(2, RoundingMode.HALF_UP);
            
            Integer discountPercent = calculateDiscount(nights, propertyInfo.getDiscountEnabled());
            BigDecimal discountAmount = baseRent.multiply(BigDecimal.valueOf(discountPercent))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            
            BigDecimal finalRent = baseRent.subtract(discountAmount);
            
            Double negotiationPercentage = propertyInfo.getNegotiationPercentage();
            
            if (negotiationPercentage != null && negotiationPercentage > 0) {
                BigDecimal percentageDiscount = BigDecimal.valueOf(negotiationPercentage)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                BigDecimal minPrice = finalRent.multiply(BigDecimal.ONE.subtract(percentageDiscount))
                        .setScale(2, RoundingMode.HALF_UP);
                
                if (request.getRequestedPrice().compareTo(minPrice) < 0) {
                    return "Price is not acceptable. Please increase it.";
                }
                
                if (request.getRequestedPrice().compareTo(finalRent) > 0) {
                    return "Price is not acceptable. Please increase it.";
                }
            } else {
                BigDecimal minPrice = finalRent.multiply(BigDecimal.valueOf(0.80))
                        .setScale(2, RoundingMode.HALF_UP);
                
                if (request.getRequestedPrice().compareTo(minPrice) < 0) {
                    return "Price is not acceptable. Please increase it.";
                }
            }
            
            return null;
        } catch (Exception e) {
            log.error("Error validating requested price", e);
            return "Price is not acceptable. Please increase it.";
        }
    }

    private Integer calculateDiscount(int nights, Boolean discountEnabled) {
        if (discountEnabled == null || !discountEnabled) {
            return 0;
        }
        if (nights > 30) return 20;
        if (nights > 15) return 15;
        if (nights > 5) return 10;
        return 0;
    }

    private PropertyInfo getPropertyInfoFromPropertyService(String propertyId) {
        try {
            String url = propertyServiceUrl + "/api/v1/properties/" + propertyId + "/booking-info";
            log.info("Calling property-service for property info: {}", url);
            
            PropertyInfo propertyInfo = restTemplate.getForObject(url, PropertyInfo.class);
            
            if (propertyInfo == null) {
                log.error("Property info is null from property-service");
                throw new RuntimeException("Property not found in property-service: " + propertyId);
            }
            
            log.debug("Property info received: id={}, ownerId={}, pricePerNight={}, isNegotiable={}, discountEnabled={}",
                    propertyInfo.getId(), propertyInfo.getOwnerId(), propertyInfo.getPricePerNight(),
                    propertyInfo.getIsNegotiable(), propertyInfo.getDiscountEnabled());
            
            return propertyInfo;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("HTTP Error fetching property info from property-service: {} - {}", e.getStatusCode(), e.getMessage());
            throw new RuntimeException("Failed to fetch property info from property-service: " + e.getStatusCode() + " - " + e.getMessage());
        } catch (Exception e) {
            log.error("Error fetching property info from property-service: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch property info from property-service: " + e.getMessage());
        }
    }

    public PropertyInfo getPropertyInfo(String propertyId) {
        return getPropertyInfoFromPropertyService(propertyId);
    }

    public Long getLastBookingId() {
        List<Booking> bookings = bookingRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        if (bookings.isEmpty()) {
            return null;
        }
        return bookings.get(0).getId();
    }

    public List<Booking> getBookingsByUserId(Long userId) {
        return bookingRepository.findAll().stream()
                .filter(booking -> booking.getUserId().equals(userId))
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .toList();
    }

    public List<Booking> getPendingBookingsByUserId(Long userId) {
        return bookingRepository.findAll().stream()
                .filter(booking -> booking.getUserId().equals(userId) &&
                        (booking.getStatus().equals("PENDING_NEGOTIATION") ||
                         // Support old status: PENDING with requestedNegotiationPercent
                         (booking.getStatus().equals("PENDING") && booking.getRequestedNegotiationPercent() != null)))
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .toList();
    }

    public List<Booking> getAwaitingPaymentBookingsByUserId(Long userId) {
        return bookingRepository.findAll().stream()
                .filter(booking -> booking.getUserId().equals(userId) &&
                        (booking.getStatus().equals("PENDING_PAYMENT") ||
                         // Support old status: PENDING without requestedNegotiationPercent
                         (booking.getStatus().equals("PENDING") && 
                          (booking.getRequestedNegotiationPercent() == null || booking.getRequestedNegotiationPercent() == 0))))
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .toList();
    }

    public List<Booking> getBookingsByOwnerId(Long ownerId) {
        return bookingRepository.findAll().stream()
                .filter(booking -> {
                    Property property = propertyRepository.findById(booking.getPropertyId()).orElse(null);
                    return property != null && property.getOwnerId().equals(ownerId);
                })
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .toList();
    }

    public List<Booking> getPendingNegotiationsByOwnerId(Long ownerId) {
        return bookingRepository.findAll().stream()
                .filter(booking -> {
                    Property property = propertyRepository.findById(booking.getPropertyId()).orElse(null);
                    return property != null && 
                           property.getOwnerId().equals(ownerId) &&
                           booking.getStatus().equals("PENDING_NEGOTIATION");
                })
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .toList();
    }

    @Transactional
    public Booking acceptNegotiation(Long bookingId, Long ownerId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));

        Property property = propertyRepository.findById(booking.getPropertyId())
                .orElseThrow(() -> new RuntimeException("Property not found: " + booking.getPropertyId()));

        if (!property.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Only property owner can accept negotiation");
        }

        if (booking.getRequestedNegotiationPercent() == null) {
            throw new RuntimeException("This booking does not have a negotiation request");
        }

        booking.setRequestedNegotiationPercent(null);
        booking.setNegotiationExpiresAt(null);
        booking.setStatus("PENDING_PAYMENT");

        return bookingRepository.save(booking);
    }

    @Transactional
    public Booking rejectNegotiation(Long bookingId, Long ownerId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));

        Property property = propertyRepository.findById(booking.getPropertyId())
                .orElseThrow(() -> new RuntimeException("Property not found: " + booking.getPropertyId()));

        if (!property.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Only property owner can reject negotiation");
        }

        if (booking.getRequestedNegotiationPercent() == null) {
            throw new RuntimeException("This booking does not have a negotiation request");
        }

        booking.setStatus("NEGOTIATION_REJECTED");
        booking.setNegotiationExpiresAt(null);

        return bookingRepository.save(booking);
    }

    public List<Booking> getConfirmedBookingsByOwnerId(Long ownerId) {
        return bookingRepository.findAll().stream()
                .filter(booking -> {
                    Property property = propertyRepository.findById(booking.getPropertyId()).orElse(null);
                    return property != null && 
                           property.getOwnerId().equals(ownerId) &&
                           booking.getStatus().equals("CONFIRMED");
                })
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .toList();
    }

    public List<Booking> getConfirmedBookingsByPropertyId(String propertyId) {
        return bookingRepository.findAll().stream()
                .filter(booking -> booking.getPropertyId().equals(propertyId) &&
                           booking.getStatus().equals("CONFIRMED"))
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .toList();
    }

    @Transactional
    public Booking reportDispute(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));

        boolean isTenant = booking.getUserId().equals(userId);
        boolean isOwner = false;
        
        if (!isTenant && booking.getPropertyId() != null) {
            Property property = propertyRepository.findById(booking.getPropertyId()).orElse(null);
            isOwner = property != null && property.getOwnerId().equals(userId);
        }

        if (!isTenant && !isOwner) {
            throw new RuntimeException("Only booking tenant or property owner can report a dispute");
        }

        boolean canReportDispute = booking.getStatus().equals("CONFIRMED") ||
                booking.getStatus().equals("TENANT_CHECKED_OUT");
        
        if (!canReportDispute) {
            throw new RuntimeException("Only CONFIRMED or TENANT_CHECKED_OUT bookings can be reported as dispute");
        }

        booking.setStatus("IN_DISPUTE");
        return bookingRepository.save(booking);
    }

    public Booking getBookingById(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + id));
    }

    @Transactional
    public Booking updateBooking(Long id, UpdateBookingRequest request) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + id));

        Property property = propertyRepository.findById(booking.getPropertyId())
                .orElseThrow(() -> new RuntimeException("Property not found: " + booking.getPropertyId()));

        if (request.getCheckInDate() != null) {
            booking.setCheckInDate(LocalDate.parse(request.getCheckInDate()));
        }
        if (request.getCheckOutDate() != null) {
            booking.setCheckOutDate(LocalDate.parse(request.getCheckOutDate()));
        }

        int nights = (int) ChronoUnit.DAYS.between(booking.getCheckInDate(), booking.getCheckOutDate());

        BigDecimal pricePerNight = BigDecimal.valueOf(property.getPrice());
        BigDecimal baseRent = pricePerNight.multiply(BigDecimal.valueOf(nights))
                .setScale(2, RoundingMode.HALF_UP);

        PropertyInfo propertyInfo = getPropertyInfoFromPropertyService(booking.getPropertyId());
        
        Integer discountPercent = calculateDiscount(nights, propertyInfo != null ? propertyInfo.getDiscountEnabled() : false);
        BigDecimal discountAmount = baseRent.multiply(BigDecimal.valueOf(discountPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal finalRent = baseRent.subtract(discountAmount);

        String status;
        Integer requestedNegotiationPercent = null;
        Instant negotiationExpiresAt = null;

        boolean isRejectedNegotiation = booking.getStatus().equals("NEGOTIATION_REJECTED");

        if (request.getRequestedPrice() != null) {
            Double negotiationPercentage = propertyInfo != null ? propertyInfo.getNegotiationPercentage() : null;
            
            if (negotiationPercentage != null && negotiationPercentage > 0) {
                BigDecimal percentageDiscount = BigDecimal.valueOf(negotiationPercentage)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                BigDecimal minPrice = finalRent.multiply(BigDecimal.ONE.subtract(percentageDiscount))
                        .setScale(2, RoundingMode.HALF_UP);
                
                // Check if requested price is acceptable
                if (request.getRequestedPrice().compareTo(minPrice) >= 0 && 
                    request.getRequestedPrice().compareTo(finalRent) <= 0) {
                    // Price is acceptable - set status to PENDING_NEGOTIATION
                    status = "PENDING_NEGOTIATION";
                    finalRent = request.getRequestedPrice();
                    BigDecimal discount = baseRent.subtract(request.getRequestedPrice());
                    requestedNegotiationPercent = discount.divide(baseRent, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(0, RoundingMode.HALF_UP)
                            .intValue();
                    negotiationExpiresAt = Instant.now().plusSeconds(24 * 60 * 60);
                    log.info("Price accepted! Negotiation: {}%, Status set to: PENDING_NEGOTIATION", requestedNegotiationPercent);
                } else if (request.getRequestedPrice().compareTo(minPrice) < 0) {
                    log.warn("Requested price is too low. Minimum required: {}, Requested: {}", minPrice, request.getRequestedPrice());
                    throw new RuntimeException("Price is not acceptable. Please increase it. Minimum: " + minPrice);
                } else {
                    status = "PENDING_PAYMENT";
                    requestedNegotiationPercent = null;
                    negotiationExpiresAt = null;
                    log.info("Price is higher than final rent, using final rent.");
                }
            } else {
                BigDecimal minPrice = finalRent.multiply(BigDecimal.valueOf(0.80))
                        .setScale(2, RoundingMode.HALF_UP);
                
                if (request.getRequestedPrice().compareTo(minPrice) >= 0 && 
                    request.getRequestedPrice().compareTo(finalRent) <= 0) {
                    status = "PENDING_NEGOTIATION";
                    finalRent = request.getRequestedPrice();
                    BigDecimal discount = baseRent.subtract(request.getRequestedPrice());
                    requestedNegotiationPercent = discount.divide(baseRent, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(0, RoundingMode.HALF_UP)
                            .intValue();
                    negotiationExpiresAt = Instant.now().plusSeconds(24 * 60 * 60);
                    log.info("Price accepted! Negotiation: {}%, Status set to: PENDING_NEGOTIATION", requestedNegotiationPercent);
                } else if (request.getRequestedPrice().compareTo(minPrice) < 0) {
                    throw new RuntimeException("Price is not acceptable. Please increase it. Minimum: " + minPrice);
                } else {
                    status = "PENDING_PAYMENT";
                    requestedNegotiationPercent = null;
                    negotiationExpiresAt = null;
                }
            }
        } else {
            if (isRejectedNegotiation) {
                status = "NEGOTIATION_REJECTED";
            } else {
                status = "PENDING_PAYMENT";
            }
            requestedNegotiationPercent = null;
            negotiationExpiresAt = null;
        }

        booking.setTotalPrice(finalRent.doubleValue());
        booking.setStatus(status);
        booking.setLongStayDiscountPercent(discountPercent);
        booking.setRequestedNegotiationPercent(requestedNegotiationPercent);
        booking.setNegotiationExpiresAt(negotiationExpiresAt);

        return bookingRepository.save(booking);
    }

    @Transactional
    public Booking cancelBookingByTenant(Long id, Long userId) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + id));

        if (!booking.getUserId().equals(userId)) {
            throw new RuntimeException("Only the booking tenant can cancel this booking");
        }

        boolean canCancel = booking.getStatus().equals("PENDING_PAYMENT") ||
                booking.getStatus().equals("PENDING_NEGOTIATION") ||
                booking.getStatus().equals("NEGOTIATION_REJECTED");

        if (!canCancel) {
            throw new RuntimeException("This booking cannot be cancelled. Status: " + booking.getStatus());
        }

        booking.setStatus("CANCELLED_BY_TENANT");
        booking.setRequestedNegotiationPercent(null);
        booking.setNegotiationExpiresAt(null);

        log.info("Booking cancelled by tenant with id: {}", id);
        return bookingRepository.save(booking);
    }

    public Optional<Booking> getCurrentBookingByUserId(Long userId) {
        LocalDate today = LocalDate.now();
        return bookingRepository.findAll().stream()
                .filter(booking -> booking.getUserId().equals(userId) &&
                        !booking.getCheckInDate().isAfter(today) &&
                        !booking.getCheckOutDate().isBefore(today) &&
                        (booking.getStatus().equals("CONFIRMED") ||
                         booking.getStatus().equals("TENANT_CHECKED_OUT") ||
                         booking.getStatus().equals("PENDING_PAYMENT")))
                .findFirst();
    }

    public List<Booking> getCurrentBookingsByOwnerId(Long ownerId) {
        LocalDate today = LocalDate.now();
        return bookingRepository.findAll().stream()
                .filter(booking -> {
                    Property property = propertyRepository.findById(booking.getPropertyId()).orElse(null);
                    return property != null &&
                           property.getOwnerId().equals(ownerId) &&
                           !booking.getCheckInDate().isAfter(today) &&
                           !booking.getCheckOutDate().isBefore(today) &&
                           (booking.getStatus().equals("CONFIRMED") ||
                            booking.getStatus().equals("TENANT_CHECKED_OUT") ||
                            booking.getStatus().equals("PENDING_PAYMENT") ||
                            // Support old status: PENDING without negotiation
                            (booking.getStatus().equals("PENDING") && 
                             (booking.getRequestedNegotiationPercent() == null || booking.getRequestedNegotiationPercent() == 0)));
                })
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .toList();
    }

    @Transactional
    public Booking tenantCheckout(Long bookingId, Long tenantId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));

        log.info("tenantCheckout: bookingId={}, tenantId={}, bookingStatus={}, bookingUserId={}",
                bookingId, tenantId, booking.getStatus(), booking.getUserId());

        if (!booking.getUserId().equals(tenantId)) {
            throw new RuntimeException("Only the booking tenant can checkout. Booking tenantId: " + booking.getUserId() + ", provided userId: " + tenantId);
        }

        boolean canCheckout = booking.getStatus().equals("CONFIRMED") ||
                booking.getStatus().equals("PENDING_PAYMENT");
        
        if (!canCheckout) {
            log.error("Tenant cannot checkout: bookingStatus={}, expected=CONFIRMED or PENDING_PAYMENT", 
                    booking.getStatus());
            throw new RuntimeException("Only CONFIRMED or PENDING_PAYMENT bookings can be checked out by tenant. Current status: " + booking.getStatus());
        }
        
        booking.setStatus("TENANT_CHECKED_OUT");
        log.info("Tenant checked out: bookingId={}, status changed to TENANT_CHECKED_OUT", bookingId);
        
        return bookingRepository.save(booking);
    }

    @Transactional
    public Booking ownerConfirmCheckout(Long bookingId, Long ownerId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));

        log.info("ownerConfirmCheckout: bookingId={}, ownerId={}, bookingStatus={}, propertyId={}",
                bookingId, ownerId, booking.getStatus(), booking.getPropertyId());

        boolean isOwner = false;
        if (booking.getPropertyId() != null) {
            try {
                Property localProperty = propertyRepository.findById(booking.getPropertyId()).orElse(null);
                if (localProperty != null && localProperty.getOwnerId() != null) {
                    isOwner = localProperty.getOwnerId().equals(ownerId);
                    log.info("Owner check from local DB: isOwner={}, propertyOwnerId={}, userId={}", 
                            isOwner, localProperty.getOwnerId(), ownerId);
                }
                
                if (!isOwner) {
                    PropertyInfo propertyInfo = getPropertyInfoFromPropertyService(booking.getPropertyId());
                    if (propertyInfo != null && propertyInfo.getOwnerId() != null) {
                        isOwner = propertyInfo.getOwnerId().equals(ownerId);
                        log.info("Owner check from property-service: isOwner={}, propertyOwnerId={}, userId={}", 
                                isOwner, propertyInfo.getOwnerId(), ownerId);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch property info from property-service for propertyId={}: {}", 
                        booking.getPropertyId(), e.getMessage());
            }
        }

        if (!isOwner) {
            throw new RuntimeException("Only the property owner can confirm checkout. Provided userId: " + ownerId);
        }

        if (!booking.getStatus().equals("TENANT_CHECKED_OUT")) {
            log.error("Owner cannot confirm checkout: bookingStatus={}, expected=TENANT_CHECKED_OUT", 
                    booking.getStatus());
            throw new RuntimeException("Only TENANT_CHECKED_OUT bookings can be confirmed by owner. Current status: " + booking.getStatus());
        }
        
        booking.setStatus("COMPLETED");
        log.info("Owner confirmed checkout: bookingId={}, status changed to COMPLETED", bookingId);
        
        return bookingRepository.save(booking);
    }

    @Deprecated
    @Transactional
    public Booking markAsCheckedOut(Long bookingId, Long userId) {
        try {
            return ownerConfirmCheckout(bookingId, userId);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Only the property owner")) {
                return tenantCheckout(bookingId, userId);
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getUserInfoFromUserService(Long userId) {
        try {
            String url = userServiceUrl + "/api/v1/users/" + userId;
            log.info("🌐 Calling user-service for user info: {}", url);
            
            Object response = restTemplate.getForObject(url, Object.class);
            if (response == null) {
                return Map.of("firstName", "Unknown", "lastName", "User", "email", "unknown@example.com");
            }
            return (Map<String, Object>) response;
        } catch (Exception e) {
            log.error("Error fetching user info from user-service: {}", e.getMessage());
            return Map.of("firstName", "Unknown", "lastName", "User", "email", "unknown@example.com");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPropertyDetailsFromPropertyService(String propertyId) {
        try {
            String url = propertyServiceUrl + "/api/v1/properties/" + propertyId;
            log.info("🌐 Calling property-service for property details: {}", url);
            
            Object response = restTemplate.getForObject(url, Object.class);
            if (response == null) {
                return Map.of("title", "Unknown Property", "address", Map.of("address", "Unknown", "city", "Unknown"));
            }
            return (Map<String, Object>) response;
        } catch (Exception e) {
            log.error("Error fetching property details from property-service: {}", e.getMessage());
            return Map.of("title", "Unknown Property", "address", Map.of("address", "Unknown", "city", "Unknown"));
        }
    }

    @Transactional
    public void cancelOverlappingBookings(Long confirmedBookingId) {
        log.info("🚀 cancelOverlappingBookings called for bookingId={}", confirmedBookingId);
        try {
            Booking confirmedBooking = bookingRepository.findById(confirmedBookingId)
                    .orElse(null);
            
            if (confirmedBooking == null) {
                log.error("❌ Cannot delete overlapping bookings: confirmed booking not found: {}", confirmedBookingId);
                return;
            }
            
            log.info("📋 Confirmed booking details: id={}, status={}, propertyId={}, checkIn={}, checkOut={}", 
                    confirmedBooking.getId(), confirmedBooking.getStatus(), 
                    confirmedBooking.getPropertyId(), confirmedBooking.getCheckInDate(), 
                    confirmedBooking.getCheckOutDate());
            
            if (!"CONFIRMED".equals(confirmedBooking.getStatus())) {
                log.warn("⚠️ Booking {} is not CONFIRMED (status: {}), skipping overlap deletion", 
                        confirmedBookingId, confirmedBooking.getStatus());
                return;
            }
            
            String propertyId = confirmedBooking.getPropertyId();
            LocalDate checkIn = confirmedBooking.getCheckInDate();
            LocalDate checkOut = confirmedBooking.getCheckOutDate();
            
            if (propertyId == null || checkIn == null || checkOut == null) {
                log.warn("⚠️ Cannot delete overlapping bookings: missing propertyId or dates for booking {}", confirmedBookingId);
                return;
            }
            
            log.info("Looking for overlapping bookings to DELETE: propertyId={}, checkIn={}, checkOut={}, excludeBookingId={}", 
                    propertyId, checkIn, checkOut, confirmedBookingId);
            
            List<Booking> allPropertyBookings = bookingRepository.findByPropertyId(propertyId);
            log.info("📊 Total bookings for property {}: {}", propertyId, allPropertyBookings.size());
            for (Booking b : allPropertyBookings) {
                log.info("   - Booking {}: status={}, checkIn={}, checkOut={}", 
                        b.getId(), b.getStatus(), b.getCheckInDate(), b.getCheckOutDate());
            }
            
            List<Booking> overlappingBookings = bookingRepository.findOverlappingBookings(
                    propertyId, 
                    confirmedBookingId, 
                    checkIn, 
                    checkOut
            );
            
            log.info("SQL query returned {} overlapping booking(s) before filtering", overlappingBookings.size());
            
            overlappingBookings.removeIf(b -> {
                boolean isConfirmed = b.getId().equals(confirmedBookingId);
                if (isConfirmed) {
                    log.error("CRITICAL: Confirmed booking {} found in overlapping list! Removing it.", confirmedBookingId);
                }
                return isConfirmed;
            });
            
            log.info("After filtering, {} overlapping booking(s) remain to DELETE (excluding confirmedBookingId={})", overlappingBookings.size(), confirmedBookingId);
            for (Booking b : overlappingBookings) {
                log.info("   - Overlapping Booking {}: status={}, checkIn={}, checkOut={}, userId={}", 
                        b.getId(), b.getStatus(), b.getCheckInDate(), b.getCheckOutDate(), b.getUserId());
            }
            
            if (overlappingBookings.isEmpty()) {
                log.info("No overlapping bookings found for booking {}", confirmedBookingId);
                return;
            }
            
            log.info("Found {} overlapping booking(s) to DELETE from database (excluding confirmedBookingId={})", overlappingBookings.size(), confirmedBookingId);
            
            int deletedCount = 0;
            int skippedCount = 0;
            for (Booking overlappingBooking : overlappingBookings) {
                if (overlappingBooking.getId().equals(confirmedBookingId)) {
                    log.error("CRITICAL ERROR: Attempted to delete the confirmed booking itself! bookingId={}. This should never happen - SQL query should exclude it!", confirmedBookingId);
                    continue;
                }
                
                String previousStatus = overlappingBooking.getStatus();
                
                if ("COMPLETED".equals(previousStatus)) {
                    log.info("Skipping booking {} - already completed (status: {}), will not delete", 
                            overlappingBooking.getId(), previousStatus);
                    skippedCount++;
                    continue;
                }
                
                try {
                    Long bookingIdToDelete = overlappingBooking.getId();
                    bookingRepository.delete(overlappingBooking);
                    deletedCount++;
                    
                    log.info("DELETED overlapping booking from database: bookingId={}, previousStatus={}, " +
                            "checkIn={}, checkOut={} (overlapped with confirmed booking {})", 
                            bookingIdToDelete, previousStatus,
                            overlappingBooking.getCheckInDate(), overlappingBooking.getCheckOutDate(),
                            confirmedBookingId);
                } catch (Exception e) {
                    log.error("Failed to delete overlapping booking {}: {}", overlappingBooking.getId(), e.getMessage(), e);
                }
            }
            
            log.info("Deletion summary: {} deleted, {} skipped, {} total overlapping bookings for confirmed booking {}", 
                    deletedCount, skippedCount, overlappingBookings.size(), confirmedBookingId);
            
        } catch (Exception e) {
            log.error("Error deleting overlapping bookings for booking {}: {}", 
                    confirmedBookingId, e.getMessage(), e);
        }
    }

    public List<ma.fstt.bookingservice.api.dto.AdminBookingResponseDTO> getAllBookingsForAdmin() {
        List<Booking> bookings = bookingRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        
        return bookings.stream().map(booking -> {
            try {
                Property property = null;
                Map<String, Object> propertyDetails = null;
                String propertyTitle = "Unknown Property";
                String propertyAddress = "Unknown";
                Long ownerId = null;
                
                if (booking.getPropertyId() != null) {
                    property = propertyRepository.findById(booking.getPropertyId()).orElse(null);
                    if (property != null) {
                        ownerId = property.getOwnerId();
                    }
                    
                    try {
                        propertyDetails = getPropertyDetailsFromPropertyService(booking.getPropertyId());
                        if (propertyDetails != null) {
                            propertyTitle = (String) propertyDetails.getOrDefault("title", "Unknown Property");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> address = (Map<String, Object>) propertyDetails.get("address");
                            if (address != null) {
                                propertyAddress = address.getOrDefault("address", "Unknown") + ", " + 
                                                address.getOrDefault("city", "Unknown");
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Could not fetch property details for {}", booking.getPropertyId());
                    }
                }
                
                Map<String, Object> tenantInfo = getUserInfoFromUserService(booking.getUserId());
                String tenantName = tenantInfo.getOrDefault("firstName", "Unknown") + " " + 
                                  tenantInfo.getOrDefault("lastName", "User");
                String tenantEmail = (String) tenantInfo.getOrDefault("email", "unknown@example.com");
                
                String hostName = "Unknown Host";
                String hostEmail = "unknown@example.com";
                if (ownerId != null) {
                    try {
                        Map<String, Object> hostInfo = getUserInfoFromUserService(ownerId);
                        hostName = hostInfo.getOrDefault("firstName", "Unknown") + " " + 
                                  hostInfo.getOrDefault("lastName", "Host");
                        hostEmail = (String) hostInfo.getOrDefault("email", "unknown@example.com");
                    } catch (Exception e) {
                        log.warn("Could not fetch host info for {}", ownerId);
                    }
                }
                
                Integer numberOfNights = null;
                if (booking.getCheckInDate() != null && booking.getCheckOutDate() != null) {
                    numberOfNights = (int) ChronoUnit.DAYS.between(booking.getCheckInDate(), booking.getCheckOutDate());
                }
                
                return ma.fstt.bookingservice.api.dto.AdminBookingResponseDTO.builder()
                        .id(booking.getId())
                        .userId(booking.getUserId())
                        .propertyId(booking.getPropertyId())
                        .propertyTitle(propertyTitle)
                        .propertyAddress(propertyAddress)
                        .ownerId(ownerId)
                        .tenantName(tenantName)
                        .tenantEmail(tenantEmail)
                        .hostName(hostName)
                        .hostEmail(hostEmail)
                        .checkInDate(booking.getCheckInDate())
                        .checkOutDate(booking.getCheckOutDate())
                        .numberOfNights(numberOfNights)
                        .totalPrice(booking.getTotalPrice())
                        .longStayDiscountPercent(booking.getLongStayDiscountPercent())
                        .requestedNegotiationPercent(booking.getRequestedNegotiationPercent())
                        .status(booking.getStatus())
                        .onChainTxHash(booking.getOnChainTxHash())
                        .negotiationExpiresAt(booking.getNegotiationExpiresAt())
                        .createdAt(booking.getCreatedAt())
                        .updatedAt(booking.getUpdatedAt())
                        .build();
            } catch (Exception e) {
                log.error("Error enriching booking {}: {}", booking.getId(), e.getMessage());
                return ma.fstt.bookingservice.api.dto.AdminBookingResponseDTO.builder()
                        .id(booking.getId())
                        .userId(booking.getUserId())
                        .propertyId(booking.getPropertyId())
                        .propertyTitle("Error loading")
                        .propertyAddress("Error loading")
                        .tenantName("Error loading")
                        .tenantEmail("Error loading")
                        .hostName("Error loading")
                        .hostEmail("Error loading")
                        .checkInDate(booking.getCheckInDate())
                        .checkOutDate(booking.getCheckOutDate())
                        .totalPrice(booking.getTotalPrice())
                        .status(booking.getStatus())
                        .createdAt(booking.getCreatedAt())
                        .updatedAt(booking.getUpdatedAt())
                        .build();
            }
        }).toList();
    }
}

