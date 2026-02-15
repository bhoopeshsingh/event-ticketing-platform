package com.ticketing.booking.service;

import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.entity.Event;
import com.ticketing.common.entity.SeatHold;
import com.ticketing.common.service.SeatStatusCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeatHoldCleanupJobTest {

    @Mock private SeatHoldRepository seatHoldRepository;
    @Mock private SeatRepository seatRepository;
    @Mock private EventMessagingService messagingService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SeatStatusCacheService seatStatusCacheService;
    @Mock private ValueOperations<String, String> valueOperations;

    private SeatHoldCleanupJob cleanupJob;
    private Event testEvent;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        cleanupJob = new SeatHoldCleanupJob(
            seatHoldRepository, seatRepository, messagingService, redisTemplate, seatStatusCacheService
        );
        testEvent = Event.builder().id(1L).title("Test Event").build();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void reconcileExpiredHolds_NoExpiredHolds() {
        when(seatHoldRepository.findExpiredHolds(any(LocalDateTime.class))).thenReturn(List.of());

        cleanupJob.reconcileExpiredHolds();

        verify(seatRepository, never()).releaseSeats(any());
    }

    @Test
    void reconcileExpiredHolds_RedisKeysGone_CleansUp() {
        SeatHold hold = SeatHold.builder()
            .id(1L).holdToken("HOLD_EXP").customerId(10L)
            .event(testEvent).seatIds(List.of(1L, 2L))
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();
        hold.setCreatedAt(LocalDateTime.now().minusMinutes(11));

        when(seatHoldRepository.findExpiredHolds(any())).thenReturn(List.of(hold));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null); // keys gone
        when(seatRepository.releaseSeats(List.of(1L, 2L))).thenReturn(2);
        when(seatHoldRepository.save(any())).thenReturn(hold);

        cleanupJob.reconcileExpiredHolds();

        verify(seatRepository).releaseSeats(List.of(1L, 2L));
        verify(seatHoldRepository).save(hold);

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(seatStatusCacheService).cacheSeatStatusChanges(eq(1L), eq(List.of(1L, 2L)), eq("AVAILABLE"));
        verify(messagingService).publishSeatHoldExpired(eq("HOLD_EXP"), eq(10L), eq(1L), eq(List.of(1L, 2L)));
    }

    @Test
    void reconcileExpiredHolds_RedisKeyStillExists_Skips() {
        SeatHold hold = SeatHold.builder()
            .id(1L).holdToken("HOLD_EXP").customerId(10L)
            .event(testEvent).seatIds(List.of(1L))
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();

        when(seatHoldRepository.findExpiredHolds(any())).thenReturn(List.of(hold));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("seat:1:1:HELD")).thenReturn("10:HOLD_EXP"); // key still exists

        cleanupJob.reconcileExpiredHolds();

        verify(seatRepository, never()).releaseSeats(any());
    }

    @Test
    void reconcileExpiredHolds_ExceptionInReconcile_ContinuesWithOthers() {
        SeatHold hold1 = SeatHold.builder()
            .id(1L).holdToken("H1").customerId(10L)
            .event(testEvent).seatIds(List.of(1L))
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();
        SeatHold hold2 = SeatHold.builder()
            .id(2L).holdToken("H2").customerId(20L)
            .event(testEvent).seatIds(List.of(2L))
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();

        when(seatHoldRepository.findExpiredHolds(any())).thenReturn(List.of(hold1, hold2));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // hold1 throws, hold2 succeeds
        when(valueOperations.get("seat:1:1:HELD")).thenThrow(new RuntimeException("err"));
        when(valueOperations.get("seat:1:2:HELD")).thenReturn(null);
        when(seatRepository.releaseSeats(List.of(2L))).thenReturn(1);
        when(seatHoldRepository.save(any())).thenReturn(hold2);

        cleanupJob.reconcileExpiredHolds();

        // hold2 should still be cleaned
        verify(seatRepository).releaseSeats(List.of(2L));
    }

    @Test
    void reconcileExpiredHolds_Rollback_ReAffirmsHeld() {
        SeatHold hold = SeatHold.builder()
            .id(1L).holdToken("HOLD_EXP").customerId(10L)
            .event(testEvent).seatIds(List.of(1L))
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();

        when(seatHoldRepository.findExpiredHolds(any())).thenReturn(List.of(hold));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(seatRepository.releaseSeats(any())).thenReturn(1);
        when(seatHoldRepository.save(any())).thenReturn(hold);

        cleanupJob.reconcileExpiredHolds();

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(seatStatusCacheService).cacheSeatStatusChanges(eq(1L), eq(List.of(1L)), eq("HELD"));
    }
}
