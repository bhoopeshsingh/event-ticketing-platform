package com.ticketing.event.service;

import com.ticketing.common.dto.EventDto;
import com.ticketing.common.dto.SeatDto;
import com.ticketing.common.entity.Event;
import com.ticketing.common.entity.Seat;
import com.ticketing.common.enums.EventStatus;
import com.ticketing.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final EventRepository eventRepository;

    /**
     * Search events with multiple filters - core read scenario
     * Cached for performance, with TTL of 5 minutes
     */
    @Cacheable(value = "events", key = "#city + '_' + #category + '_' + #fromDate + '_' + #toDate + '_' + #page + '_' + #size")
    @Transactional(readOnly = true)
    public Page<EventDto> searchEvents(String city, String category,
                                      LocalDateTime fromDate, LocalDateTime toDate,
                                      int page, int size) {

        log.debug("Searching events: city={}, category={}, fromDate={}, toDate={}, page={}, size={}",
                 city, category, fromDate, toDate, page, size);

        Pageable pageable = PageRequest.of(page, size);

        Page<Event> events = eventRepository.findEventsWithFilters(
            city, category, fromDate, toDate, EventStatus.PUBLISHED, pageable);

        log.debug("Found {} events for search criteria", events.getTotalElements());

        return events.map(this::convertToDto);
    }

    /**
     * Get event details with real-time seat availability
     */
    @Cacheable(value = "event_details", key = "#eventId")
    @Transactional(readOnly = true)
    public Optional<EventDto> getEventById(Long eventId) {
        log.debug("Fetching event details for ID: {}", eventId);

        return eventRepository.findById(eventId)
            .filter(event -> event.getStatus() == EventStatus.PUBLISHED)
            .map(this::convertToDto);
    }

    /**
     * Get event with seat layout - for booking flow
     */
    @Cacheable(value = "event_with_seats", key = "#eventId")
    @Transactional(readOnly = true)
    public Optional<EventDto> getEventWithSeats(Long eventId) {
        log.debug("Fetching event with seats for ID: {}", eventId);

        Optional<Event> eventOpt = eventRepository.findByIdWithSeats(eventId);
        if (eventOpt.isEmpty()) {
            log.debug("Event not found: {}", eventId);
            return Optional.empty();
        }

        Event event = eventOpt.get();
        if (event.getStatus() != EventStatus.PUBLISHED) {
            log.debug("Event not published: {} status: {}", eventId, event.getStatus());
            return Optional.empty();
        }

        EventDto eventDto = convertToDto(event);

        // Add seat information
        if (event.getSeats() != null && !event.getSeats().isEmpty()) {
            log.debug("Converting {} seats to DTOs for event: {}", event.getSeats().size(), eventId);
            List<SeatDto> seatDtos = event.getSeats().stream()
                .map(this::convertSeatToDto)
                .toList();
            eventDto.setSeats(seatDtos);
        }
        
        eventDto.setPricingTiers(null); // Will be set separately

        return Optional.of(eventDto);
    }

    /**
     * Get popular events by city - homepage feature
     */
    @Cacheable(value = "popular_events", key = "#city")
    @Transactional(readOnly = true)
    public Page<EventDto> getPopularEventsByCity(String city, int page, int size) {
        log.debug("Fetching popular events for city: {}", city);

        Pageable pageable = PageRequest.of(page, size);
        Page<Event> events = eventRepository.findByCity(city, LocalDateTime.now(), pageable);

        return events.map(this::convertToDto);
    }

    /**
     * Get upcoming events - landing page
     */
    @Cacheable(value = "upcoming_events", key = "#page + '_' + #size")
    @Transactional(readOnly = true)
    public Page<EventDto> getUpcomingEvents(int page, int size) {
        log.debug("Fetching upcoming events: page={}, size={}", page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<Event> events = eventRepository.findUpcomingEvents(LocalDateTime.now(), pageable);

        return events.map(this::convertToDto);
    }

    /**
     * Get available cities for search filters
     */
    @Cacheable(value = "active_cities")
    @Transactional(readOnly = true)
    public List<String> getActiveCities() {
        log.debug("Fetching active cities");
        return eventRepository.findActiveCities(LocalDateTime.now());
    }

    /**
     * Get available categories for search filters
     */
    @Cacheable(value = "active_categories")
    @Transactional(readOnly = true)
    public List<String> getActiveCategories() {
        log.debug("Fetching active categories");
        return eventRepository.findActiveCategories(LocalDateTime.now());
    }

    /**
     * Search events by title - autocomplete
     */
    @Transactional(readOnly = true)
    public List<EventDto> searchEventsByTitle(String query, int limit) {
        log.debug("Searching events by title: {}", query);

        if (query == null || query.trim().length() < 2) {
            return List.of();
        }

        List<Event> events = eventRepository.searchByTitle(query.trim(), LocalDateTime.now());

        return events.stream()
            .limit(limit)
            .map(this::convertToDto)
            .toList();
    }

    /**
     * Create new event - organizer feature
     */
    @CacheEvict(value = {"events", "upcoming_events", "popular_events", "active_cities", "active_categories"}, allEntries = true)
    @Transactional
    public EventDto createEvent(EventDto eventDto) {
        log.info("Creating new event: {}", eventDto.getTitle());

        Event event = convertFromDto(eventDto);
        event.setStatus(EventStatus.DRAFT); // Always start as draft
        event.setCreatedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());

        Event savedEvent = eventRepository.save(event);

        log.info("Event created successfully: ID={}, Title={}", savedEvent.getId(), savedEvent.getTitle());

        return convertToDto(savedEvent);
    }

    /**
     * Update event - organizer feature
     */
    @CacheEvict(value = {"events", "event_details", "event_with_seats", "upcoming_events", "popular_events"},
                key = "#eventDto.id", condition = "#eventDto.id != null")
    @Transactional
    public Optional<EventDto> updateEvent(Long eventId, EventDto eventDto) {
        log.info("Updating event: {}", eventId);

        Optional<Event> existingEventOpt = eventRepository.findById(eventId);
        if (existingEventOpt.isEmpty()) {
            return Optional.empty();
        }

        Event existingEvent = existingEventOpt.get();

        // Update allowed fields
        existingEvent.setTitle(eventDto.getTitle());
        existingEvent.setDescription(eventDto.getDescription());
        existingEvent.setCategory(eventDto.getCategory());
        existingEvent.setVenue(eventDto.getVenue());
        existingEvent.setEventDate(eventDto.getEventDate());
        existingEvent.setBasePrice(eventDto.getBasePrice());
        existingEvent.setUpdatedAt(LocalDateTime.now());

        // Status changes require special handling
        if (eventDto.getStatus() != null) {
            EventStatus newStatus = EventStatus.valueOf(eventDto.getStatus());
            existingEvent.setStatus(newStatus);
        }

        Event savedEvent = eventRepository.save(existingEvent);

        log.info("Event updated successfully: ID={}", eventId);

        return Optional.of(convertToDto(savedEvent));
    }

    /**
     * Publish event - make it available for booking
     */
    @CacheEvict(value = {"events", "event_details", "upcoming_events", "popular_events"}, allEntries = true)
    @Transactional
    public boolean publishEvent(Long eventId) {
        log.info("Publishing event: {}", eventId);

        Optional<Event> eventOpt = eventRepository.findById(eventId);
        if (eventOpt.isEmpty()) {
            return false;
        }

        Event event = eventOpt.get();
        if (event.getStatus() != EventStatus.DRAFT) {
            log.warn("Cannot publish event {} - current status: {}", eventId, event.getStatus());
            return false;
        }

        event.setStatus(EventStatus.PUBLISHED);
        event.setUpdatedAt(LocalDateTime.now());
        eventRepository.save(event);

        log.info("Event published successfully: {}", eventId);
        return true;
    }

    // DTO Conversion Methods
    private EventDto convertToDto(Event event) {
        return EventDto.builder()
            .id(event.getId())
            .title(event.getTitle())
            .description(event.getDescription())
            .category(event.getCategory())
            .city(event.getCity())
            .venue(event.getVenue())
            .eventDate(event.getEventDate())
            .totalCapacity(event.getTotalCapacity())
            .availableSeats(event.getAvailableSeats())
            .basePrice(event.getBasePrice())
            .status(event.getStatus().name())
            .organizerId(event.getOrganizerId())
            .createdAt(event.getCreatedAt())
            .updatedAt(event.getUpdatedAt())
            .build();
    }

    private Event convertFromDto(EventDto eventDto) {
        return Event.builder()
            .id(eventDto.getId())
            .title(eventDto.getTitle())
            .description(eventDto.getDescription())
            .category(eventDto.getCategory())
            .city(eventDto.getCity())
            .venue(eventDto.getVenue())
            .eventDate(eventDto.getEventDate())
            .totalCapacity(eventDto.getTotalCapacity())
            .availableSeats(eventDto.getAvailableSeats() != null ? eventDto.getAvailableSeats() : eventDto.getTotalCapacity())
            .basePrice(eventDto.getBasePrice())
            .organizerId(eventDto.getOrganizerId())
            .build();
    }

    private SeatDto convertSeatToDto(Seat seat) {
        return SeatDto.builder()
            .id(seat.getId())
            .eventId(seat.getEvent().getId())
            .section(seat.getSection())
            .rowLetter(seat.getRowLetter())
            .seatNumber(seat.getSeatNumber())
            .price(seat.getPrice())
            .status(seat.getStatus().name())
            .build();
    }
}
