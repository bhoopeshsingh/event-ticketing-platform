package com.ticketing.booking.service;

import com.ticketing.common.service.SeatStatusCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class SeatStatusCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private SeatStatusCacheService seatStatusCacheService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void cacheSeatStatusChange_shouldSetFieldInHash() {
        seatStatusCacheService.cacheSeatStatusChange(1L, 123L, "HELD");

        verify(hashOperations).put("1:seat_status", "123", "HELD");
        verify(redisTemplate).expire("1:seat_status", Duration.ofSeconds(600));
    }

    @Test
    void cacheSeatStatusChange_shouldOverwritePreviousStatus() {
        // First call: seat is AVAILABLE
        seatStatusCacheService.cacheSeatStatusChange(1L, 5L, "AVAILABLE");
        verify(hashOperations).put("1:seat_status", "5", "AVAILABLE");

        // Second call: same seat now HELD -- overwrites atomically
        seatStatusCacheService.cacheSeatStatusChange(1L, 5L, "HELD");
        verify(hashOperations).put("1:seat_status", "5", "HELD");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheSeatStatusChanges_shouldBatchSetFields() {
        List<Long> seatIds = Arrays.asList(101L, 102L, 103L);

        seatStatusCacheService.cacheSeatStatusChanges(1L, seatIds, "BOOKED");

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("1:seat_status"), captor.capture());

        Map<String, String> captured = captor.getValue();
        assertThat(captured).hasSize(3);
        assertThat(captured.get("101")).isEqualTo("BOOKED");
        assertThat(captured.get("102")).isEqualTo("BOOKED");
        assertThat(captured.get("103")).isEqualTo("BOOKED");
        verify(redisTemplate).expire("1:seat_status", Duration.ofSeconds(600));
    }

    @Test
    void cacheSeatStatusChanges_withEmptyList_shouldSkip() {
        seatStatusCacheService.cacheSeatStatusChanges(1L, List.of(), "HELD");

        verify(hashOperations, never()).putAll(anyString(), anyMap());
    }

    @Test
    void cacheSeatStatusChanges_withNullList_shouldSkip() {
        seatStatusCacheService.cacheSeatStatusChanges(1L, null, "HELD");

        verify(hashOperations, never()).putAll(anyString(), anyMap());
    }

    @Test
    void removeSeatFromStatus_shouldDeleteField() {
        seatStatusCacheService.removeSeatFromStatus(1L, 123L, "HELD");

        verify(hashOperations).delete("1:seat_status", "123");
    }

    @Test
    void transitionSeatStatus_shouldOverwriteWithNewStatus() {
        // transition is just a put (HASH naturally overwrites)
        seatStatusCacheService.transitionSeatStatus(1L, 123L, "HELD", "BOOKED");

        verify(hashOperations).put("1:seat_status", "123", "BOOKED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void transitionSeatStatuses_shouldBatchOverwrite() {
        List<Long> seatIds = Arrays.asList(101L, 102L, 103L);

        seatStatusCacheService.transitionSeatStatuses(1L, seatIds, "HELD", "AVAILABLE");

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("1:seat_status"), captor.capture());

        Map<String, String> captured = captor.getValue();
        assertThat(captured.get("101")).isEqualTo("AVAILABLE");
        assertThat(captured.get("102")).isEqualTo("AVAILABLE");
        assertThat(captured.get("103")).isEqualTo("AVAILABLE");
    }

    @Test
    void getRecentChanges_shouldReturnAllFields() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("101", "HELD");
        entries.put("102", "HELD");
        entries.put("103", "BOOKED");
        entries.put("104", "AVAILABLE");
        when(hashOperations.entries("1:seat_status")).thenReturn(entries);

        Map<Long, String> changes = seatStatusCacheService.getRecentChanges(1L);

        assertThat(changes).hasSize(4);
        assertThat(changes.get(101L)).isEqualTo("HELD");
        assertThat(changes.get(102L)).isEqualTo("HELD");
        assertThat(changes.get(103L)).isEqualTo("BOOKED");
        assertThat(changes.get(104L)).isEqualTo("AVAILABLE");
    }

    @Test
    void getRecentChanges_eachSeatAppearsExactlyOnce() {
        // This is the bug scenario: seat 5 was AVAILABLE, then re-HELD.
        // With HASH, the latest write wins. Seat 5 should only be HELD.
        Map<Object, Object> entries = new HashMap<>();
        entries.put("5", "HELD");  // only one entry per seat in a HASH
        entries.put("6", "AVAILABLE");
        when(hashOperations.entries("1:seat_status")).thenReturn(entries);

        Map<Long, String> changes = seatStatusCacheService.getRecentChanges(1L);

        assertThat(changes).hasSize(2);
        assertThat(changes.get(5L)).isEqualTo("HELD");
        assertThat(changes.get(6L)).isEqualTo("AVAILABLE");
    }

    @Test
    void getRecentChanges_withEmptyHash_shouldReturnEmpty() {
        when(hashOperations.entries("1:seat_status")).thenReturn(new HashMap<>());

        Map<Long, String> changes = seatStatusCacheService.getRecentChanges(1L);

        assertThat(changes).isEmpty();
    }

    @Test
    void getRecentStatusCounts_shouldAggregate() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("1", "HELD");
        entries.put("2", "HELD");
        entries.put("3", "BOOKED");
        entries.put("4", "AVAILABLE");
        entries.put("5", "AVAILABLE");
        when(hashOperations.entries("1:seat_status")).thenReturn(entries);

        Map<String, Long> counts = seatStatusCacheService.getRecentStatusCounts(1L);

        assertThat(counts.get("HELD")).isEqualTo(2L);
        assertThat(counts.get("BOOKED")).isEqualTo(1L);
        assertThat(counts.get("AVAILABLE")).isEqualTo(2L);
    }

    @Test
    void clearRecentChanges_shouldDeleteKey() {
        seatStatusCacheService.clearRecentChanges(1L);

        verify(redisTemplate).delete("1:seat_status");
    }

    @Test
    void cacheSeatStatusChange_withException_shouldLogAndContinue() {
        doThrow(new RuntimeException("Redis connection error"))
            .when(hashOperations).put(anyString(), any(), any());

        // Should not throw
        seatStatusCacheService.cacheSeatStatusChange(1L, 123L, "HELD");

        verify(hashOperations).put(anyString(), any(), any());
    }
}
