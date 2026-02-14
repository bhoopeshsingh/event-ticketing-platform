package com.ticketing.booking.service;

import com.ticketing.common.service.SeatStatusCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeatStatusCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @InjectMocks
    private SeatStatusCacheService seatStatusCacheService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void cacheSeatStatusChange_shouldAddToZSetWithTimestamp() {
        Long eventId = 1L;
        Long seatId = 123L;
        String status = "HELD";

        seatStatusCacheService.cacheSeatStatusChange(eventId, seatId, status);

        verify(zSetOperations).add(eq("1:recent_changes:HELD"), eq("123"), anyDouble());
        verify(zSetOperations).removeRangeByScore(eq("1:recent_changes:HELD"), 
                                                   eq(Double.NEGATIVE_INFINITY), 
                                                   anyDouble());
        verify(redisTemplate).expire(eq("1:recent_changes:HELD"), eq(Duration.ofSeconds(120)));
    }

    @Test
    void cacheSeatStatusChanges_shouldBatchAddMultipleSeats() {
        Long eventId = 1L;
        List<Long> seatIds = Arrays.asList(101L, 102L, 103L);
        String status = "BOOKED";

        seatStatusCacheService.cacheSeatStatusChanges(eventId, seatIds, status);

        verify(zSetOperations).add(eq("1:recent_changes:BOOKED"), anySet());
        verify(zSetOperations).removeRangeByScore(eq("1:recent_changes:BOOKED"), 
                                                   eq(Double.NEGATIVE_INFINITY), 
                                                   anyDouble());
        verify(redisTemplate).expire(eq("1:recent_changes:BOOKED"), eq(Duration.ofSeconds(120)));
    }

    @Test
    void cacheSeatStatusChanges_withEmptyList_shouldSkip() {
        seatStatusCacheService.cacheSeatStatusChanges(1L, List.of(), "HELD");

        verify(zSetOperations, never()).add(anyString(), anySet());
    }

    @Test
    void cacheSeatStatusChanges_withNullList_shouldSkip() {
        seatStatusCacheService.cacheSeatStatusChanges(1L, null, "HELD");

        verify(zSetOperations, never()).add(anyString(), anySet());
    }

    @Test
    void removeSeatFromStatus_shouldRemoveFromZSet() {
        Long eventId = 1L;
        Long seatId = 123L;
        String oldStatus = "HELD";

        seatStatusCacheService.removeSeatFromStatus(eventId, seatId, oldStatus);

        verify(zSetOperations).remove("1:recent_changes:HELD", "123");
    }

    @Test
    void transitionSeatStatus_shouldRemoveFromOldAndAddToNew() {
        Long eventId = 1L;
        Long seatId = 123L;
        String fromStatus = "HELD";
        String toStatus = "BOOKED";

        seatStatusCacheService.transitionSeatStatus(eventId, seatId, fromStatus, toStatus);

        verify(zSetOperations).remove("1:recent_changes:HELD", "123");
        verify(zSetOperations).add(eq("1:recent_changes:BOOKED"), eq("123"), anyDouble());
    }

    @Test
    void transitionSeatStatus_withNullFromStatus_shouldOnlyAddToNew() {
        Long eventId = 1L;
        Long seatId = 123L;
        String toStatus = "HELD";

        seatStatusCacheService.transitionSeatStatus(eventId, seatId, null, toStatus);

        verify(zSetOperations, never()).remove(anyString(), anyString());
        verify(zSetOperations).add(eq("1:recent_changes:HELD"), eq("123"), anyDouble());
    }

    @Test
    void transitionSeatStatuses_shouldBatchTransition() {
        Long eventId = 1L;
        List<Long> seatIds = Arrays.asList(101L, 102L, 103L);
        String fromStatus = "HELD";
        String toStatus = "AVAILABLE";

        seatStatusCacheService.transitionSeatStatuses(eventId, seatIds, fromStatus, toStatus);

        verify(zSetOperations, times(3)).remove(eq("1:recent_changes:HELD"), anyString());
        verify(zSetOperations).add(eq("1:recent_changes:AVAILABLE"), anySet());
    }

    @Test
    void getRecentChanges_shouldMergeAllStatusSets() {
        Long eventId = 1L;

        when(zSetOperations.range("1:recent_changes:HELD", 0, -1))
            .thenReturn(Set.of("101", "102"));
        when(zSetOperations.range("1:recent_changes:BOOKED", 0, -1))
            .thenReturn(Set.of("103", "104"));
        when(zSetOperations.range("1:recent_changes:AVAILABLE", 0, -1))
            .thenReturn(Set.of("105"));

        Map<Long, String> changes = seatStatusCacheService.getRecentChanges(eventId);

        assertThat(changes).hasSize(5);
        assertThat(changes.get(101L)).isEqualTo("HELD");
        assertThat(changes.get(102L)).isEqualTo("HELD");
        assertThat(changes.get(103L)).isEqualTo("BOOKED");
        assertThat(changes.get(104L)).isEqualTo("BOOKED");
        assertThat(changes.get(105L)).isEqualTo("AVAILABLE");
    }

    @Test
    void getRecentChanges_withNullSets_shouldHandleGracefully() {
        Long eventId = 1L;

        when(zSetOperations.range(anyString(), anyLong(), anyLong())).thenReturn(null);

        Map<Long, String> changes = seatStatusCacheService.getRecentChanges(eventId);

        assertThat(changes).isEmpty();
    }

    @Test
    void getRecentStatusCounts_shouldReturnCountsPerStatus() {
        Long eventId = 1L;

        when(zSetOperations.zCard("1:recent_changes:HELD")).thenReturn(5L);
        when(zSetOperations.zCard("1:recent_changes:BOOKED")).thenReturn(10L);
        when(zSetOperations.zCard("1:recent_changes:AVAILABLE")).thenReturn(2L);

        Map<String, Long> counts = seatStatusCacheService.getRecentStatusCounts(eventId);

        assertThat(counts).hasSize(3);
        assertThat(counts.get("HELD")).isEqualTo(5L);
        assertThat(counts.get("BOOKED")).isEqualTo(10L);
        assertThat(counts.get("AVAILABLE")).isEqualTo(2L);
    }

    @Test
    void clearRecentChanges_shouldDeleteAllStatusKeys() {
        Long eventId = 1L;

        seatStatusCacheService.clearRecentChanges(eventId);

        verify(redisTemplate).delete("1:recent_changes:HELD");
        verify(redisTemplate).delete("1:recent_changes:BOOKED");
        verify(redisTemplate).delete("1:recent_changes:AVAILABLE");
    }

    @Test
    void cacheSeatStatusChange_withException_shouldLogAndContinue() {
        Long eventId = 1L;
        Long seatId = 123L;
        String status = "HELD";

        when(zSetOperations.add(anyString(), anyString(), anyDouble()))
            .thenThrow(new RuntimeException("Redis connection error"));

        seatStatusCacheService.cacheSeatStatusChange(eventId, seatId, status);

        verify(zSetOperations).add(anyString(), anyString(), anyDouble());
    }
}
