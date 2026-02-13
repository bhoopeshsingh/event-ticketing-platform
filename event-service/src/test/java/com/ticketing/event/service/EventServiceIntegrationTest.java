package com.ticketing.event.service;

import com.ticketing.common.dto.EventDto;
import com.ticketing.common.entity.Event;
import com.ticketing.common.enums.EventStatus;
import com.ticketing.event.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
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
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.cache.type=none"
})
@ActiveProfiles("test")
@Transactional
class EventServiceIntegrationTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();

        // Create sample data
        Event event1 = Event.builder()
            .title("Java Conference")
            .description("Deep dive into Java 21")
            .category("technology")
            .city("San Francisco")
            .venue("Moscone West")
            .eventDate(LocalDateTime.now().plusDays(30))
            .totalCapacity(500)
            .availableSeats(500)
            .basePrice(new BigDecimal("299.00"))
            .status(EventStatus.PUBLISHED)
            .organizerId(1L)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        Event event2 = Event.builder()
            .title("Rock Concert")
            .description("Live music")
            .category("Music")
            .city("New York")
            .venue("MSG")
            .eventDate(LocalDateTime.now().plusDays(10))
            .totalCapacity(1000)
            .availableSeats(1000)
            .basePrice(new BigDecimal("150.00"))
            .status(EventStatus.PUBLISHED)
            .organizerId(2L)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        Event event3 = Event.builder() // Draft event
            .title("Secret Event")
            .description("TBA")
            .category("Mystery")
            .city("Unknown")
            .venue("TBA")
            .eventDate(LocalDateTime.now().plusDays(60))
            .totalCapacity(100)
            .availableSeats(100)
            .basePrice(new BigDecimal("999.00"))
            .status(EventStatus.DRAFT)
            .organizerId(1L)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        eventRepository.saveAll(List.of(event1, event2, event3));
    }

    @Test
    void searchEvents_Integration_FilterByCity() {
        // Act
        Page<EventDto> result = eventService.searchEvents("San Francisco", null, null, null, 0, 10);

        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals("Java Conference", result.getContent().get(0).getTitle());
    }

    @Test
    void searchEvents_Integration_FilterByCategory() {
        // Act
        Page<EventDto> result = eventService.searchEvents(null, "Music", null, null, 0, 10);

        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals("Rock Concert", result.getContent().get(0).getTitle());
    }

    @Test
    void getUpcomingEvents_Integration() {
        // Act
        Page<EventDto> result = eventService.getUpcomingEvents(0, 10);

        // Assert
        // Should only return published events (2 out of 3)
        assertEquals(2, result.getTotalElements());
        assertTrue(result.getContent().stream().noneMatch(e -> e.getTitle().equals("Secret Event")));
    }

    @Test
    void createEvent_Integration() {
        // Arrange
        EventDto newEvent = EventDto.builder()
            .title("New Integration Test Event")
            .description("Desc")
            .category("Test")
            .city("London")
            .venue("O2")
            .eventDate(LocalDateTime.now().plusDays(20))
            .totalCapacity(5000)
            .basePrice(new BigDecimal("80.00"))
            .organizerId(3L)
            .build();

        // Act
        EventDto created = eventService.createEvent(newEvent);

        // Assert
        assertNotNull(created.getId());
        assertEquals("DRAFT", created.getStatus()); // Default status

        // Verify in DB
        Optional<Event> fromDb = eventRepository.findById(created.getId());
        assertTrue(fromDb.isPresent());
        assertEquals("London", fromDb.get().getCity());
    }
}
