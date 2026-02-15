package com.ticketing.event.controller;

import com.ticketing.common.dto.EventDto;
import com.ticketing.event.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventControllerTest {

    @Mock
    private EventService eventService;

    @InjectMocks
    private EventController eventController;

    // ─── searchEvents ───────────────────────────────────────────────────

    @Test
    void searchEvents_Success() {
        Page<EventDto> page = new PageImpl<>(List.of(
            EventDto.builder().id(1L).title("Rock Concert").build()));
        when(eventService.searchEvents(eq("NYC"), isNull(), isNull(), isNull(), eq(0), eq(20)))
            .thenReturn(page);

        ResponseEntity<Page<EventDto>> response = eventController.searchEvents("NYC", null, null, null, 0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getTotalElements());
    }

    @Test
    void searchEvents_InvalidPage_Returns400() {
        ResponseEntity<Page<EventDto>> response = eventController.searchEvents(null, null, null, null, -1, 20);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void searchEvents_InvalidSize_Returns400() {
        ResponseEntity<Page<EventDto>> response = eventController.searchEvents(null, null, null, null, 0, 0);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void searchEvents_SizeExceedsMax_Returns400() {
        ResponseEntity<Page<EventDto>> response = eventController.searchEvents(null, null, null, null, 0, 101);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ─── getEvent ───────────────────────────────────────────────────────

    @Test
    void getEvent_Found() {
        EventDto dto = EventDto.builder().id(1L).title("Concert").build();
        when(eventService.getEventById(1L)).thenReturn(Optional.of(dto));

        ResponseEntity<EventDto> response = eventController.getEvent(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Concert", response.getBody().getTitle());
    }

    @Test
    void getEvent_NotFound() {
        when(eventService.getEventById(99L)).thenReturn(Optional.empty());

        ResponseEntity<EventDto> response = eventController.getEvent(99L);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── getEventWithSeats ──────────────────────────────────────────────

    @Test
    void getEventWithSeats_Found() {
        EventDto dto = EventDto.builder().id(1L).title("Concert").build();
        when(eventService.getEventWithSeats(1L)).thenReturn(Optional.of(dto));

        ResponseEntity<EventDto> response = eventController.getEventWithSeats(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Concert", response.getBody().getTitle());
    }

    @Test
    void getEventWithSeats_NotFound() {
        when(eventService.getEventWithSeats(99L)).thenReturn(Optional.empty());

        ResponseEntity<EventDto> response = eventController.getEventWithSeats(99L);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── upcoming events ────────────────────────────────────────────────

    @Test
    void getUpcomingEvents_Success() {
        Page<EventDto> page = new PageImpl<>(List.of(
            EventDto.builder().id(1L).title("Event1").build()));
        when(eventService.getUpcomingEvents(0, 20)).thenReturn(page);

        ResponseEntity<Page<EventDto>> response = eventController.getUpcomingEvents(0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getTotalElements());
    }

    @Test
    void getUpcomingEvents_InvalidPage() {
        ResponseEntity<Page<EventDto>> response = eventController.getUpcomingEvents(-1, 20);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void getUpcomingEvents_InvalidSize() {
        ResponseEntity<Page<EventDto>> response = eventController.getUpcomingEvents(0, 0);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ─── cities ─────────────────────────────────────────────────────────

    @Test
    void getActiveCities() {
        when(eventService.getActiveCities()).thenReturn(List.of("NYC", "LA"));

        ResponseEntity<List<String>> response = eventController.getActiveCities();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().size());
    }

    // ─── categories ─────────────────────────────────────────────────────

    @Test
    void getActiveCategories() {
        when(eventService.getActiveCategories()).thenReturn(List.of("Music", "Tech"));

        ResponseEntity<List<String>> response = eventController.getActiveCategories();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().size());
    }

    // ─── search by title ────────────────────────────────────────────────

    @Test
    void searchEventsByTitle_Success() {
        EventDto dto = EventDto.builder().id(1L).title("Rock Show").build();
        when(eventService.searchEventsByTitle("Rock", 10)).thenReturn(List.of(dto));

        ResponseEntity<List<EventDto>> response = eventController.searchEventsByTitle("Rock", 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void searchEventsByTitle_ShortQuery_Returns400() {
        ResponseEntity<List<EventDto>> response = eventController.searchEventsByTitle("R", 10);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void searchEventsByTitle_NullQuery_Returns400() {
        ResponseEntity<List<EventDto>> response = eventController.searchEventsByTitle(null, 10);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void searchEventsByTitle_LimitCapped() {
        when(eventService.searchEventsByTitle("Rock", 10)).thenReturn(List.of());

        ResponseEntity<List<EventDto>> response = eventController.searchEventsByTitle("Rock", 200);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ─── createEvent ────────────────────────────────────────────────────

    @Test
    void createEvent_Success() {
        EventDto inputDto = EventDto.builder()
            .title("New Event").description("Desc").category("Music")
            .city("NYC").venue("MSG")
            .eventDate(LocalDateTime.now().plusDays(30))
            .totalCapacity(500).basePrice(new BigDecimal("99.00"))
            .organizerId(1L).build();

        EventDto createdDto = EventDto.builder().id(1L).title("New Event").status("DRAFT").build();
        when(eventService.createEvent(any())).thenReturn(createdDto);

        ResponseEntity<EventDto> response = eventController.createEvent(inputDto, 1L);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("New Event", response.getBody().getTitle());
    }

    @Test
    void createEvent_ServiceException_Returns500() {
        EventDto inputDto = EventDto.builder()
            .title("Event").description("Desc").category("Music")
            .city("NYC").venue("MSG")
            .eventDate(LocalDateTime.now().plusDays(5))
            .totalCapacity(100).basePrice(new BigDecimal("10"))
            .organizerId(1L).build();

        when(eventService.createEvent(any())).thenThrow(new RuntimeException("DB error"));

        ResponseEntity<EventDto> response = eventController.createEvent(inputDto, 1L);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // ─── updateEvent ────────────────────────────────────────────────────

    @Test
    void updateEvent_Success() {
        EventDto updated = EventDto.builder().id(1L).title("Updated").build();
        when(eventService.updateEvent(eq(1L), any())).thenReturn(Optional.of(updated));

        EventDto input = EventDto.builder().title("Updated").build();
        ResponseEntity<EventDto> response = eventController.updateEvent(1L, input, 1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Updated", response.getBody().getTitle());
    }

    @Test
    void updateEvent_NotFound() {
        when(eventService.updateEvent(eq(99L), any())).thenReturn(Optional.empty());

        ResponseEntity<EventDto> response = eventController.updateEvent(99L, EventDto.builder().build(), 1L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── publishEvent ───────────────────────────────────────────────────

    @Test
    void publishEvent_Success() {
        when(eventService.publishEvent(1L)).thenReturn(true);

        ResponseEntity<Void> response = eventController.publishEvent(1L, 1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void publishEvent_Failure() {
        when(eventService.publishEvent(1L)).thenReturn(false);

        ResponseEntity<Void> response = eventController.publishEvent(1L, 1L);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ─── health ─────────────────────────────────────────────────────────

    @Test
    void health() {
        ResponseEntity<String> response = eventController.health();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Event Service is healthy", response.getBody());
    }
}
