package com.ticketing.event.service;

import com.ticketing.common.dto.EventDto;
import com.ticketing.common.entity.Event;
import com.ticketing.common.enums.EventStatus;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

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

    @Test
    void searchEvents_Success() {
        // Arrange
        String city = "New York";
        Pageable pageable = PageRequest.of(0, 10);
        Page<Event> eventPage = new PageImpl<>(List.of(testEvent));

        when(eventRepository.findEventsWithFilters(
            eq(city), isNull(), any(), any(), eq(EventStatus.PUBLISHED), any(Pageable.class)))
            .thenReturn(eventPage);

        // Act
        Page<EventDto> result = eventService.searchEvents(city, null, null, null, 0, 10);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("Test Event", result.getContent().get(0).getTitle());
        verify(eventRepository).findEventsWithFilters(any(), any(), any(), any(), any(), any());
    }

    @Test
    void getEventById_Success() {
        // Arrange
        when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));

        // Act
        Optional<EventDto> result = eventService.getEventById(1L);

        // Assert
        assertTrue(result.isPresent());
        assertEquals("Test Event", result.get().getTitle());
    }

    @Test
    void getEventById_NotFound() {
        // Arrange
        when(eventRepository.findById(99L)).thenReturn(Optional.empty());

        // Act
        Optional<EventDto> result = eventService.getEventById(99L);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void createEvent_Success() {
        // Arrange
        EventDto inputDto = EventDto.builder()
            .title("New Event")
            .description("Desc")
            .category("Tech")
            .city("SF")
            .venue("Center")
            .eventDate(LocalDateTime.now().plusDays(5))
            .totalCapacity(500)
            .basePrice(new BigDecimal("50.00"))
            .organizerId(2L)
            .build();

        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            event.setId(2L);
            event.setStatus(EventStatus.DRAFT);
            return event;
        });

        // Act
        EventDto result = eventService.createEvent(inputDto);

        // Assert
        assertNotNull(result);
        assertEquals(2L, result.getId());
        assertEquals("DRAFT", result.getStatus());
        verify(eventRepository).save(any(Event.class));
    }
}
