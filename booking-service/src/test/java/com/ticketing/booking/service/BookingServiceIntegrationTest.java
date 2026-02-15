package com.ticketing.booking.service;

import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.dto.SeatHoldRequest;
import com.ticketing.common.dto.SeatHoldResponse;
import com.ticketing.common.entity.Event;
import com.ticketing.common.entity.Seat;
import com.ticketing.common.entity.SeatHold;
import com.ticketing.common.service.SeatStatusCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=password",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.sql.init.mode=always",
    "spring.jpa.defer-datasource-initialization=true",
    "booking.hold.cleanup.enabled=false"
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

    @MockBean
    private EventMessagingService messagingService;

    @MockBean
    private SeatHoldExpiryService expiryService;

    @MockBean
    private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

    // Mock Redis so the normal (non-degraded) path is taken,
    // avoiding the PESSIMISTIC_WRITE query that H2 doesn't support.
    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private SeatStatusCacheService seatStatusCacheService;

    private Event testEvent;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        // Configure Redis mock
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // Default: Redis SET NX always succeeds (normal path, no degraded mode)
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(true);

        // Prepare Database Data
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
        List<Seat> availableSeats = seatRepository.findAvailableSeatsByEvent(testEvent.getId());
        List<Long> seatIds = availableSeats.stream().map(Seat::getId).limit(2).toList();

        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(100L)
            .eventId(testEvent.getId())
            .seatIds(seatIds)
            .build();

        SeatHoldResponse response = bookingService.holdSeats(request);

        assertNotNull(response);
        assertNotNull(response.getHoldToken());
        assertEquals(2, response.getSeatCount());

        // Clear persistence context so re-fetch reads actual DB state
        entityManager.flush();
        entityManager.clear();

        // Verify Database State
        List<Seat> heldSeats = seatRepository.findAllById(seatIds);
        assertTrue(heldSeats.stream().allMatch(s -> s.getStatus() == Seat.SeatStatus.HELD));

        Optional<SeatHold> holdOpt = seatHoldRepository.findByHoldToken(response.getHoldToken());
        assertTrue(holdOpt.isPresent());
        assertEquals("ACTIVE", holdOpt.get().getStatus().name());
    }

    @Test
    void holdSeats_Integration_ConcurrentHoldRejectedByRedis() {
        List<Seat> availableSeats = seatRepository.findAvailableSeatsByEvent(testEvent.getId());
        List<Long> seatIds = availableSeats.stream().map(Seat::getId).limit(2).toList();

        // First hold succeeds (Redis returns true for both seats)
        SeatHoldRequest request1 = SeatHoldRequest.builder()
            .customerId(100L)
            .eventId(testEvent.getId())
            .seatIds(seatIds)
            .build();

        SeatHoldResponse firstResponse = bookingService.holdSeats(request1);
        assertNotNull(firstResponse.getHoldToken());

        // Reconfigure mock: Redis SET NX now returns false (seats are locked)
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(false);

        // Second hold for same seats — Redis rejects
        SeatHoldRequest request2 = SeatHoldRequest.builder()
            .customerId(101L)
            .eventId(testEvent.getId())
            .seatIds(seatIds)
            .build();

        assertThrows(BookingException.class, () -> bookingService.holdSeats(request2));
    }
}
