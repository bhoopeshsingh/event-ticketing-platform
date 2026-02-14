package com.ticketing.event.service;

import com.ticketing.common.dto.EventDto;
import com.ticketing.common.dto.SeatDto;
import com.ticketing.common.entity.Event;
import com.ticketing.common.entity.Seat;
import com.ticketing.common.enums.EventStatus;
import com.ticketing.common.service.SeatStatusCacheService;
import com.ticketing.event.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@Transactional
class EventServiceRealTimeStatusIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ticketing_test")
        .withUsername("test_user")
        .withPassword("test_pass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SeatStatusCacheService seatStatusCacheService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Event testEvent;
    private List<Seat> testSeats;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();

        testEvent = Event.builder()
            .title("Real-Time Status Test Concert")
            .venue("Test Arena")
            .city("NYC")
            .category("Concert")
            .eventDate(LocalDateTime.now().plusDays(30))
            .totalCapacity(100)
            .availableSeats(100)
            .status(EventStatus.PUBLISHED)
            .build();
        testEvent = eventRepository.save(testEvent);

        testSeats = Arrays.asList(
            createSeat(testEvent, "A", "1", Seat.SeatStatus.AVAILABLE),
            createSeat(testEvent, "A", "2", Seat.SeatStatus.AVAILABLE),
            createSeat(testEvent, "A", "3", Seat.SeatStatus.AVAILABLE)
        );
        testEvent.setSeats(testSeats);
        testEvent = eventRepository.save(testEvent);
    }

    private Seat createSeat(Event event, String section, String seatNumber, Seat.SeatStatus status) {
        return Seat.builder()
            .event(event)
            .section(section)
            .rowLetter("A")
            .seatNumber(Integer.parseInt(seatNumber))
            .status(status)
            .price(new BigDecimal("100.00"))
            .build();
    }

    @Test
    void getEventWithSeats_withNoRedisCache_shouldReturnDBStatus() {
        Optional<EventDto> result = eventService.getEventWithSeats(testEvent.getId());

        assertThat(result).isPresent();
        EventDto eventDto = result.get();
        assertThat(eventDto.getSeats()).hasSize(3);
        assertThat(eventDto.getSeats())
            .allMatch(seat -> "AVAILABLE".equals(seat.getStatus()));
    }

    @Test
    void getEventWithSeats_withRecentHeldInRedis_shouldOverrideDBStatus() {
        Long seat1Id = testSeats.get(0).getId();
        Long seat2Id = testSeats.get(1).getId();

        seatStatusCacheService.cacheSeatStatusChanges(testEvent.getId(), 
            Arrays.asList(seat1Id, seat2Id), "HELD");

        Optional<EventDto> result = eventService.getEventWithSeats(testEvent.getId());

        assertThat(result).isPresent();
        EventDto eventDto = result.get();
        assertThat(eventDto.getSeats()).hasSize(3);

        SeatDto seat1 = findSeatById(eventDto.getSeats(), seat1Id);
        SeatDto seat2 = findSeatById(eventDto.getSeats(), seat2Id);
        SeatDto seat3 = findSeatById(eventDto.getSeats(), testSeats.get(2).getId());

        assertThat(seat1.getStatus()).isEqualTo("HELD");
        assertThat(seat2.getStatus()).isEqualTo("HELD");
        assertThat(seat3.getStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    void getEventWithSeats_withRecentBookedInRedis_shouldOverrideDBStatus() {
        Long seat1Id = testSeats.get(0).getId();

        seatStatusCacheService.cacheSeatStatusChange(testEvent.getId(), seat1Id, "BOOKED");

        Optional<EventDto> result = eventService.getEventWithSeats(testEvent.getId());

        assertThat(result).isPresent();
        EventDto eventDto = result.get();

        SeatDto seat1 = findSeatById(eventDto.getSeats(), seat1Id);
        assertThat(seat1.getStatus()).isEqualTo("BOOKED");
    }

    @Test
    void getEventWithSeats_afterTransition_shouldReflectNewStatus() {
        Long seat1Id = testSeats.get(0).getId();

        seatStatusCacheService.cacheSeatStatusChange(testEvent.getId(), seat1Id, "HELD");

        Optional<EventDto> result1 = eventService.getEventWithSeats(testEvent.getId());
        assertThat(result1).isPresent();
        SeatDto seat1Before = findSeatById(result1.get().getSeats(), seat1Id);
        assertThat(seat1Before.getStatus()).isEqualTo("HELD");

        seatStatusCacheService.transitionSeatStatus(testEvent.getId(), seat1Id, "HELD", "BOOKED");

        Optional<EventDto> result2 = eventService.getEventWithSeats(testEvent.getId());
        assertThat(result2).isPresent();
        SeatDto seat1After = findSeatById(result2.get().getSeats(), seat1Id);
        assertThat(seat1After.getStatus()).isEqualTo("BOOKED");
    }

    @Test
    void getEventWithSeats_afterCancellation_shouldShowAvailable() {
        Long seat1Id = testSeats.get(0).getId();

        seatStatusCacheService.cacheSeatStatusChange(testEvent.getId(), seat1Id, "HELD");

        Optional<EventDto> result1 = eventService.getEventWithSeats(testEvent.getId());
        assertThat(result1).isPresent();
        SeatDto seat1Held = findSeatById(result1.get().getSeats(), seat1Id);
        assertThat(seat1Held.getStatus()).isEqualTo("HELD");

        seatStatusCacheService.transitionSeatStatus(testEvent.getId(), seat1Id, "HELD", "AVAILABLE");

        Optional<EventDto> result2 = eventService.getEventWithSeats(testEvent.getId());
        assertThat(result2).isPresent();
        SeatDto seat1Released = findSeatById(result2.get().getSeats(), seat1Id);
        assertThat(seat1Released.getStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    void getEventWithSeats_withMixedStatuses_shouldMergeCorrectly() {
        Long seat1Id = testSeats.get(0).getId();
        Long seat2Id = testSeats.get(1).getId();
        Long seat3Id = testSeats.get(2).getId();

        seatStatusCacheService.cacheSeatStatusChange(testEvent.getId(), seat1Id, "HELD");
        seatStatusCacheService.cacheSeatStatusChange(testEvent.getId(), seat2Id, "BOOKED");

        Optional<EventDto> result = eventService.getEventWithSeats(testEvent.getId());

        assertThat(result).isPresent();
        EventDto eventDto = result.get();

        SeatDto seat1 = findSeatById(eventDto.getSeats(), seat1Id);
        SeatDto seat2 = findSeatById(eventDto.getSeats(), seat2Id);
        SeatDto seat3 = findSeatById(eventDto.getSeats(), seat3Id);

        assertThat(seat1.getStatus()).isEqualTo("HELD");
        assertThat(seat2.getStatus()).isEqualTo("BOOKED");
        assertThat(seat3.getStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    void getEventWithSeats_afterRedisClear_shouldReturnDBStatus() {
        Long seat1Id = testSeats.get(0).getId();

        seatStatusCacheService.cacheSeatStatusChange(testEvent.getId(), seat1Id, "HELD");

        Optional<EventDto> result1 = eventService.getEventWithSeats(testEvent.getId());
        assertThat(result1).isPresent();
        SeatDto seat1Cached = findSeatById(result1.get().getSeats(), seat1Id);
        assertThat(seat1Cached.getStatus()).isEqualTo("HELD");

        seatStatusCacheService.clearRecentChanges(testEvent.getId());

        Optional<EventDto> result2 = eventService.getEventWithSeats(testEvent.getId());
        assertThat(result2).isPresent();
        SeatDto seat1DB = findSeatById(result2.get().getSeats(), seat1Id);
        assertThat(seat1DB.getStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    void getEventWithSeats_withRedisDown_shouldFallbackToDBGracefully() {
        redisTemplate.getConnectionFactory().getConnection().close();

        Optional<EventDto> result = eventService.getEventWithSeats(testEvent.getId());

        assertThat(result).isPresent();
        EventDto eventDto = result.get();
        assertThat(eventDto.getSeats()).hasSize(3);
        assertThat(eventDto.getSeats())
            .allMatch(seat -> "AVAILABLE".equals(seat.getStatus()));
    }

    private SeatDto findSeatById(List<SeatDto> seats, Long seatId) {
        return seats.stream()
            .filter(seat -> seat.getId().equals(seatId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Seat not found: " + seatId));
    }
}
