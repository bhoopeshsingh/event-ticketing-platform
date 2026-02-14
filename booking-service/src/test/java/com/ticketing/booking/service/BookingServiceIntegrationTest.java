package com.ticketing.booking.service;

import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.dto.SeatHoldRequest;
import com.ticketing.common.dto.SeatHoldResponse;
import com.ticketing.common.entity.Event;
import com.ticketing.common.entity.Seat;
import com.ticketing.common.entity.SeatHold;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=password",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", 
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Transactional
class BookingServiceIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private SeatHoldRepository seatHoldRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private EventMessagingService messagingService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private SeatHoldExpiryService expiryService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

    private Event testEvent;
    
    // We need to persist Event first since Seat references it. 
    // However, Event entity is in Common but Event Repository is in Event Service.
    // In strict microservices, Booking Service wouldn't write to Event table.
    // But since they share the same DB (monolithic database pattern) or at least entities, 
    // and for integration testing with H2 we need Referential Integrity, we'll need to mock or 
    // insert data carefully.
    // 
    // Looking at `Seat` entity, it has @ManyToOne to Event.
    // Since we are using H2 and Hibernate ddl-auto=create-drop, we need to insert the Event manually 
    // using EntityManager or raw SQL if EventRepository is not available in this module.
    
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @BeforeEach
    void setUp() {
        // Prepare Database Data
        
        // 1. Create Event
        testEvent = Event.builder()
            .title("Integration Test Event")
            .description("Test Description")
            .category("Test")
            .city("Test City")
            .venue("Test Venue")
            .eventDate(LocalDateTime.now().plusDays(1))
            .totalCapacity(100)
            .availableSeats(100)
            .basePrice(new BigDecimal("50.00"))
            .status(com.ticketing.common.enums.EventStatus.PUBLISHED)
            .organizerId(1L)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
            
        entityManager.persist(testEvent);

        // 2. Create Seats
        Seat seat1 = Seat.builder()
            .event(testEvent)
            .section("A")
            .rowLetter("A")
            .seatNumber(1)
            .price(new BigDecimal("50.00"))
            .status(Seat.SeatStatus.AVAILABLE)
            .build();
            
        Seat seat2 = Seat.builder()
            .event(testEvent)
            .section("A")
            .rowLetter("A")
            .seatNumber(2)
            .price(new BigDecimal("50.00"))
            .status(Seat.SeatStatus.AVAILABLE)
            .build();

        seatRepository.saveAll(List.of(seat1, seat2));
        entityManager.flush();
    }

    @Test
    void holdSeats_Integration_Success() {
        // Arrange
        // Get seat IDs that were just persisted
        List<Seat> availableSeats = seatRepository.findAvailableSeatsByEvent(testEvent.getId());
        List<Long> seatIds = availableSeats.stream().map(Seat::getId).limit(2).toList();
        
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(100L)
            .eventId(testEvent.getId())
            .seatIds(seatIds)
            .build();

        // Act
        SeatHoldResponse response = bookingService.holdSeats(request);

        // Assert
        assertNotNull(response);
        assertNotNull(response.getHoldToken());
        assertEquals(2, response.getSeatCount());

        // Verify Database State
        
        // 1. Check Seat Status (Should be HELD)
        List<Seat> heldSeats = seatRepository.findAllById(seatIds);
        assertTrue(heldSeats.stream().allMatch(s -> s.getStatus() == Seat.SeatStatus.HELD));

        // 2. Check SeatHold Record created
        Optional<SeatHold> holdOpt = seatHoldRepository.findByHoldToken(response.getHoldToken());
        assertTrue(holdOpt.isPresent());
        assertEquals("ACTIVE", holdOpt.get().getStatus().name());
    }

    @Test
    void holdSeats_Integration_ConcurrentHoldParams() {
         // Attempt to hold already held seats (Simulation of race condition check at repo level)
         // First hold
        List<Seat> availableSeats = seatRepository.findAvailableSeatsByEvent(testEvent.getId());
        List<Long> seatIds = availableSeats.stream().map(Seat::getId).limit(2).toList();
        
        SeatHoldRequest request1 = SeatHoldRequest.builder()
            .customerId(100L)
            .eventId(testEvent.getId())
            .seatIds(seatIds)
            .build();
            
        bookingService.holdSeats(request1);
        
        // Second hold for same seats
        SeatHoldRequest request2 = SeatHoldRequest.builder()
            .customerId(101L)
            .eventId(testEvent.getId())
            .seatIds(seatIds)
            .build();
            
        // Should fail because seats are HELD in DB
        assertThrows(BookingException.class, () -> bookingService.holdSeats(request2));
    }
}
