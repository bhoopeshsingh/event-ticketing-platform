package com.ticketing.event.controller;

import com.ticketing.common.dto.EventDto;
import com.ticketing.event.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Event Controller", description = "Event discovery and management")
public class EventController {

    private final EventService eventService;

    @GetMapping
    @Operation(
        summary = "Search events with filters",
        description = "Core read scenario: Browse events by city, date range, and category. " +
                     "This endpoint supports the main user flow for event discovery with " +
                     "multi-layer caching and real-time availability."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Events found successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid search parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Page<EventDto>> searchEvents(
            @Parameter(description = "Filter by city")
            @RequestParam(required = false) String city,

            @Parameter(description = "Filter by category")
            @RequestParam(required = false) String category,

            @Parameter(description = "Start date for event search")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,

            @Parameter(description = "End date for event search")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,

            @Parameter(description = "Page number (0-based)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20") int size) {

        log.debug("Event search request: city={}, category={}, fromDate={}, toDate={}, page={}, size={}",
                 city, category, fromDate, toDate, page, size);

        // Validate page parameters
        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest().build();
        }

        Page<EventDto> events = eventService.searchEvents(city, category, fromDate, toDate, page, size);

        log.debug("Returning {} events (page {} of {})",
                 events.getNumberOfElements(), events.getNumber(), events.getTotalPages());

        return ResponseEntity.ok(events);
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "Get event details",
        description = "Retrieve detailed information about a specific event including availability."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event found"),
        @ApiResponse(responseCode = "404", description = "Event not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<EventDto> getEvent(
            @Parameter(description = "Event ID") @PathVariable Long id) {

        log.debug("Fetching event details for ID: {}", id);

        Optional<EventDto> event = eventService.getEventById(id);

        if (event.isPresent()) {
            return ResponseEntity.ok(event.get());
        } else {
            log.debug("Event not found: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/seats")
    @Operation(
        summary = "Get event with seat layout",
        description = "Retrieve event details including complete seat layout for booking flow."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event with seats found"),
        @ApiResponse(responseCode = "404", description = "Event not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<EventDto> getEventWithSeats(
            @Parameter(description = "Event ID") @PathVariable Long id) {

        log.debug("Fetching event with seats for ID: {}", id);

        Optional<EventDto> event = eventService.getEventWithSeats(id);

        if (event.isPresent()) {
            return ResponseEntity.ok(event.get());
        } else {
            log.debug("Event not found: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/upcoming")
    @Operation(
        summary = "Get upcoming events",
        description = "Retrieve list of upcoming published events for homepage/landing page."
    )
    public ResponseEntity<Page<EventDto>> getUpcomingEvents(
            @Parameter(description = "Page number (0-based)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20") int size) {

        log.debug("Fetching upcoming events: page={}, size={}", page, size);

        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest().build();
        }

        Page<EventDto> events = eventService.getUpcomingEvents(page, size);
        return ResponseEntity.ok(events);
    }

    @GetMapping("/cities")
    @Operation(
        summary = "Get available cities",
        description = "Retrieve list of cities with active events for search filters."
    )
    public ResponseEntity<List<String>> getActiveCities() {
        log.debug("Fetching active cities");

        List<String> cities = eventService.getActiveCities();
        return ResponseEntity.ok(cities);
    }

    @GetMapping("/categories")
    @Operation(
        summary = "Get available categories",
        description = "Retrieve list of categories with active events for search filters."
    )
    public ResponseEntity<List<String>> getActiveCategories() {
        log.debug("Fetching active categories");

        List<String> categories = eventService.getActiveCategories();
        return ResponseEntity.ok(categories);
    }

    @GetMapping("/search")
    @Operation(
        summary = "Search events by title",
        description = "Search events by title for autocomplete functionality."
    )
    public ResponseEntity<List<EventDto>> searchEventsByTitle(
            @Parameter(description = "Search query (minimum 2 characters)")
            @RequestParam String q,

            @Parameter(description = "Maximum number of results")
            @RequestParam(defaultValue = "10") int limit) {

        log.debug("Searching events by title: '{}', limit={}", q, limit);

        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.badRequest().build();
        }

        if (limit < 1 || limit > 50) {
            limit = 10; // Default limit
        }

        List<EventDto> events = eventService.searchEventsByTitle(q, limit);
        return ResponseEntity.ok(events);
    }

    // Organizer endpoints
    @PostMapping
    @Operation(
        summary = "Create new event",
        description = "Create a new event (organizer only). Event starts in DRAFT status."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Event created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid event data"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<EventDto> createEvent(
            @Valid @RequestBody EventDto eventDto,
            @RequestHeader("X-Organizer-Id") Long organizerId) {

        log.info("Creating new event: {} for organizer: {}", eventDto.getTitle(), organizerId);

        // Set organizer ID from header
        eventDto.setOrganizerId(organizerId);

        try {
            EventDto createdEvent = eventService.createEvent(eventDto);

            log.info("Event created successfully: ID={}", createdEvent.getId());

            return ResponseEntity.status(HttpStatus.CREATED).body(createdEvent);

        } catch (Exception e) {
            log.error("Error creating event for organizer: {}", organizerId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{id}")
    @Operation(
        summary = "Update event",
        description = "Update an existing event (organizer only)."
    )
    public ResponseEntity<EventDto> updateEvent(
            @Parameter(description = "Event ID") @PathVariable Long id,
            @Valid @RequestBody EventDto eventDto,
            @RequestHeader("X-Organizer-Id") Long organizerId) {

        log.info("Updating event: {} for organizer: {}", id, organizerId);

        eventDto.setOrganizerId(organizerId); // Ensure consistency

        Optional<EventDto> updatedEvent = eventService.updateEvent(id, eventDto);

        if (updatedEvent.isPresent()) {
            log.info("Event updated successfully: {}", id);
            return ResponseEntity.ok(updatedEvent.get());
        } else {
            log.warn("Event not found for update: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/publish")
    @Operation(
        summary = "Publish event",
        description = "Publish an event to make it available for booking."
    )
    public ResponseEntity<Void> publishEvent(
            @Parameter(description = "Event ID") @PathVariable Long id,
            @RequestHeader("X-Organizer-Id") Long organizerId) {

        log.info("Publishing event: {} for organizer: {}", id, organizerId);

        boolean published = eventService.publishEvent(id);

        if (published) {
            log.info("Event published successfully: {}", id);
            return ResponseEntity.ok().build();
        } else {
            log.warn("Failed to publish event: {}", id);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Health check endpoint")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Event Service is healthy");
    }
}
