package com.ticketing.event.service;

import com.ticketing.common.dto.EventDto;
import com.ticketing.common.entity.Event;
import com.ticketing.common.entity.Seat;
import com.ticketing.common.enums.EventStatus;
import com.ticketing.common.service.SeatStatusCacheService;
import com.ticketing.event.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SeatStatusCacheService seatStatusCacheService;

    @InjectMocks
    private EventService eventService;

    private Event testEvent;

    @BeforeEach
    void setUp() {
        testEvent = Event.builder()
            .id(1L)
            .title("Test Event")
            .description("Test Description")
            .category("Music")
            .city("New York")
            .venue("MSG")
            .eventDate(LocalDateTime.now().plusDays(10))
            .totalCapacity(1000)
            .availableSeats(1000)
            .basePrice(new BigDecimal("100.00"))
            .status(EventStatus.PUBLISHED)
            .organizerId(1L)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }

    // ─── searchEvents ───────────────────────────────────────────────────

    @Test
    void searchEvents_Success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Event> eventPage = new PageImpl<>(List.of(testEvent));

        when(eventRepository.findEventsWithFilters(
            eq("New York"), isNull(), any(), any(), eq(EventStatus.PUBLISHED), any(Pageable.class)))
            .thenReturn(eventPage);

        Page<EventDto> result = eventService.searchEvents("New York", null, null, null, 0, 10);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("Test Event", result.getContent().get(0).getTitle());
    }

    @Test
    void searchEvents_WithAllFilters() {
        LocalDateTime from = LocalDateTime.now();
        LocalDateTime to = LocalDateTime.now().plusDays(30);
        Page<Event> eventPage = new PageImpl<>(List.of(testEvent));

        when(eventRepository.findEventsWithFilters(
            eq("New York"), eq("Music"), eq(from), eq(to), eq(EventStatus.PUBLISHED), any(Pageable.class)))
            .thenReturn(eventPage);

        Page<EventDto> result = eventService.searchEvents("New York", "Music", from, to, 0, 10);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void searchEvents_EmptyResult() {
        when(eventRepository.findEventsWithFilters(any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());

        Page<EventDto> result = eventService.searchEvents("London", null, null, null, 0, 10);

        assertTrue(result.isEmpty());
    }

    // ─── getEventById ───────────────────────────────────────────────────

    @Test
    void getEventById_Success() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));

        Optional<EventDto> result = eventService.getEventById(1L);

        assertTrue(result.isPresent());
        assertEquals("Test Event", result.get().getTitle());
        assertEquals("PUBLISHED", result.get().getStatus());
    }

    @Test
    void getEventById_NotFound() {
        when(eventRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<EventDto> result = eventService.getEventById(99L);

        assertTrue(result.isEmpty());
    }

    @Test
    void getEventById_DraftEvent_ReturnsEmpty() {
        testEvent.setStatus(EventStatus.DRAFT);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));

        Optional<EventDto> result = eventService.getEventById(1L);

        assertTrue(result.isEmpty());
    }

    // ─── getEventWithSeats ──────────────────────────────────────────────

    @Test
    void getEventWithSeats_Success_WithRedisOverlay() {
        Seat seat1 = Seat.builder()
            .id(10L).event(testEvent).section("A").rowLetter("A").seatNumber(1)
            .price(new BigDecimal("100.00")).status(Seat.SeatStatus.AVAILABLE).build();
        Seat seat2 = Seat.builder()
            .id(20L).event(testEvent).section("A").rowLetter("A").seatNumber(2)
            .price(new BigDecimal("100.00")).status(Seat.SeatStatus.AVAILABLE).build();

        testEvent.setSeats(List.of(seat1, seat2));

        when(eventRepository.findByIdWithSeats(1L)).thenReturn(Optional.of(testEvent));

        // Redis shows seat 10 as HELD
        Map<Long, String> recentChanges = new HashMap<>();
        recentChanges.put(10L, "HELD");
        when(seatStatusCacheService.getRecentChanges(1L)).thenReturn(recentChanges);

        Optional<EventDto> result = eventService.getEventWithSeats(1L);

        assertTrue(result.isPresent());
        EventDto dto = result.get();
        assertEquals(2, dto.getSeats().size());
        assertEquals("HELD", dto.getSeats().stream()
            .filter(s -> s.getId().equals(10L)).findFirst().get().getStatus());
        assertEquals("AVAILABLE", dto.getSeats().stream()
            .filter(s -> s.getId().equals(20L)).findFirst().get().getStatus());
    }

    @Test
    void getEventWithSeats_NoSeats() {
        testEvent.setSeats(null);
        when(eventRepository.findByIdWithSeats(1L)).thenReturn(Optional.of(testEvent));

        Optional<EventDto> result = eventService.getEventWithSeats(1L);

        assertTrue(result.isPresent());
        assertNull(result.get().getSeats());
    }

    @Test
    void getEventWithSeats_EmptySeats() {
        testEvent.setSeats(List.of());
        when(eventRepository.findByIdWithSeats(1L)).thenReturn(Optional.of(testEvent));

        Optional<EventDto> result = eventService.getEventWithSeats(1L);

        assertTrue(result.isPresent());
    }

    @Test
    void getEventWithSeats_NotFound() {
        when(eventRepository.findByIdWithSeats(99L)).thenReturn(Optional.empty());

        Optional<EventDto> result = eventService.getEventWithSeats(99L);
        assertTrue(result.isEmpty());
    }

    @Test
    void getEventWithSeats_NotPublished() {
        testEvent.setStatus(EventStatus.DRAFT);
        when(eventRepository.findByIdWithSeats(1L)).thenReturn(Optional.of(testEvent));

        Optional<EventDto> result = eventService.getEventWithSeats(1L);
        assertTrue(result.isEmpty());
    }

    @Test
    void getEventWithSeats_NoRedisChanges() {
        Seat seat = Seat.builder()
            .id(10L).event(testEvent).section("A").rowLetter("A").seatNumber(1)
            .price(new BigDecimal("100.00")).status(Seat.SeatStatus.AVAILABLE).build();
        testEvent.setSeats(List.of(seat));

        when(eventRepository.findByIdWithSeats(1L)).thenReturn(Optional.of(testEvent));
        when(seatStatusCacheService.getRecentChanges(1L)).thenReturn(Map.of());

        Optional<EventDto> result = eventService.getEventWithSeats(1L);

        assertTrue(result.isPresent());
        assertEquals("AVAILABLE", result.get().getSeats().get(0).getStatus());
    }

    // ─── getPopularEventsByCity ──────────────────────────────────────────

    @Test
    void getPopularEventsByCity_Success() {
        Page<Event> page = new PageImpl<>(List.of(testEvent));
        when(eventRepository.findByCity(eq("New York"), any(), any())).thenReturn(page);

        Page<EventDto> result = eventService.getPopularEventsByCity("New York", 0, 10);

        assertEquals(1, result.getTotalElements());
        assertEquals("Test Event", result.getContent().get(0).getTitle());
    }

    // ─── getUpcomingEvents ──────────────────────────────────────────────

    @Test
    void getUpcomingEvents_Success() {
        Page<Event> page = new PageImpl<>(List.of(testEvent));
        when(eventRepository.findUpcomingEvents(any(), any())).thenReturn(page);

        Page<EventDto> result = eventService.getUpcomingEvents(0, 20);

        assertEquals(1, result.getTotalElements());
    }

    // ─── getActiveCities ────────────────────────────────────────────────

    @Test
    void getActiveCities_Success() {
        when(eventRepository.findActiveCities(any())).thenReturn(List.of("New York", "London"));

        List<String> cities = eventService.getActiveCities();

        assertEquals(2, cities.size());
        assertTrue(cities.contains("New York"));
    }

    // ─── getActiveCategories ────────────────────────────────────────────

    @Test
    void getActiveCategories_Success() {
        when(eventRepository.findActiveCategories(any())).thenReturn(List.of("Music", "Tech"));

        List<String> categories = eventService.getActiveCategories();

        assertEquals(2, categories.size());
    }

    // ─── searchEventsByTitle ────────────────────────────────────────────

    @Test
    void searchEventsByTitle_Success() {
        when(eventRepository.searchByTitle(eq("Test"), any())).thenReturn(List.of(testEvent));

        List<EventDto> result = eventService.searchEventsByTitle("Test", 10);

        assertEquals(1, result.size());
        assertEquals("Test Event", result.get(0).getTitle());
    }

    @Test
    void searchEventsByTitle_NullQuery_ReturnsEmpty() {
        List<EventDto> result = eventService.searchEventsByTitle(null, 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchEventsByTitle_ShortQuery_ReturnsEmpty() {
        List<EventDto> result = eventService.searchEventsByTitle("T", 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchEventsByTitle_Trimmed() {
        when(eventRepository.searchByTitle(eq("Ev"), any())).thenReturn(List.of(testEvent));

        List<EventDto> result = eventService.searchEventsByTitle("  Ev  ", 10);

        assertEquals(1, result.size());
    }

    @Test
    void searchEventsByTitle_LimitApplied() {
        Event ev2 = Event.builder().id(2L).title("Event 2").status(EventStatus.PUBLISHED)
            .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(eventRepository.searchByTitle(eq("Event"), any())).thenReturn(List.of(testEvent, ev2));

        List<EventDto> result = eventService.searchEventsByTitle("Event", 1);

        assertEquals(1, result.size());
    }

    // ─── createEvent ────────────────────────────────────────────────────

    @Test
    void createEvent_Success() {
        EventDto inputDto = EventDto.builder()
            .title("New Event").description("Desc").category("Tech")
            .city("SF").venue("Center")
            .eventDate(LocalDateTime.now().plusDays(5))
            .totalCapacity(500).basePrice(new BigDecimal("50.00"))
            .organizerId(2L).build();

        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            event.setId(2L);
            event.setStatus(EventStatus.DRAFT);
            return event;
        });

        EventDto result = eventService.createEvent(inputDto);

        assertNotNull(result);
        assertEquals(2L, result.getId());
        assertEquals("DRAFT", result.getStatus());
    }

    // ─── updateEvent ────────────────────────────────────────────────────

    @Test
    void updateEvent_Success() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
        when(eventRepository.save(any(Event.class))).thenReturn(testEvent);

        EventDto updateDto = EventDto.builder()
            .title("Updated Title").description("Updated Desc")
            .category("Updated Category").venue("Updated Venue")
            .eventDate(LocalDateTime.now().plusDays(20))
            .basePrice(new BigDecimal("200.00"))
            .build();

        Optional<EventDto> result = eventService.updateEvent(1L, updateDto);

        assertTrue(result.isPresent());
        verify(eventRepository).save(any(Event.class));
    }

    @Test
    void updateEvent_WithStatusChange() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
        when(eventRepository.save(any(Event.class))).thenReturn(testEvent);

        EventDto updateDto = EventDto.builder()
            .title("Title").description("Desc").category("Cat").venue("V")
            .eventDate(LocalDateTime.now().plusDays(20))
            .status("CANCELLED")
            .build();

        Optional<EventDto> result = eventService.updateEvent(1L, updateDto);

        assertTrue(result.isPresent());
    }

    @Test
    void updateEvent_NotFound() {
        when(eventRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<EventDto> result = eventService.updateEvent(99L, EventDto.builder().build());

        assertTrue(result.isEmpty());
    }

    // ─── publishEvent ───────────────────────────────────────────────────

    @Test
    void publishEvent_Success() {
        testEvent.setStatus(EventStatus.DRAFT);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
        when(eventRepository.save(any(Event.class))).thenReturn(testEvent);

        boolean result = eventService.publishEvent(1L);

        assertTrue(result);
        assertEquals(EventStatus.PUBLISHED, testEvent.getStatus());
    }

    @Test
    void publishEvent_AlreadyPublished_ReturnsFalse() {
        testEvent.setStatus(EventStatus.PUBLISHED);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));

        boolean result = eventService.publishEvent(1L);

        assertFalse(result);
    }

    @Test
    void publishEvent_NotFound_ReturnsFalse() {
        when(eventRepository.findById(99L)).thenReturn(Optional.empty());

        boolean result = eventService.publishEvent(99L);

        assertFalse(result);
    }
}
