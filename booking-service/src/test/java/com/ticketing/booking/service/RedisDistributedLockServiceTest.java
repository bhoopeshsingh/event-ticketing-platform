package com.ticketing.booking.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisDistributedLockServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisDistributedLockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new RedisDistributedLockService(redisTemplate);
    }

    // ─── acquireLock ─────────────────────────────────────────────────────

    @Test
    void acquireLock_Success_ReturnsToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:my-resource"), anyString(), eq(Duration.ofSeconds(30))))
            .thenReturn(true);

        String token = lockService.acquireLock("my-resource", Duration.ofSeconds(30));

        assertNotNull(token);
        verify(valueOperations).setIfAbsent(eq("lock:my-resource"), anyString(), eq(Duration.ofSeconds(30)));
    }

    @Test
    void acquireLock_Fails_ReturnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:my-resource"), anyString(), any(Duration.class)))
            .thenReturn(false);

        String token = lockService.acquireLock("my-resource", Duration.ofSeconds(30));

        assertNull(token);
    }

    @Test
    void acquireLock_NullResult_ReturnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(null);

        String token = lockService.acquireLock("my-resource", Duration.ofSeconds(30));

        assertNull(token);
    }

    @Test
    void acquireLock_RedisException_ReturnsNull() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));

        String token = lockService.acquireLock("my-resource", Duration.ofSeconds(30));

        assertNull(token);
    }

    // ─── releaseLock ─────────────────────────────────────────────────────

    @Test
    void releaseLock_Success_ReturnsTrue() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(Collections.singletonList("lock:my-resource")), eq("token-123")))
            .thenReturn(1L);

        boolean released = lockService.releaseLock("my-resource", "token-123");

        assertTrue(released);
    }

    @Test
    void releaseLock_NotOwner_ReturnsFalse() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
            .thenReturn(0L);

        boolean released = lockService.releaseLock("my-resource", "wrong-token");

        assertFalse(released);
    }

    @Test
    void releaseLock_NullResult_ReturnsFalse() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
            .thenReturn(null);

        boolean released = lockService.releaseLock("my-resource", "token-123");

        assertFalse(released);
    }

    @Test
    void releaseLock_RedisException_ReturnsFalse() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
            .thenThrow(new RuntimeException("Redis error"));

        boolean released = lockService.releaseLock("my-resource", "token-123");

        assertFalse(released);
    }

    // ─── executeWithLock ─────────────────────────────────────────────────

    @Test
    void executeWithLock_Success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:my-resource"), anyString(), eq(Duration.ofSeconds(30))))
            .thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
            .thenReturn(1L);

        String result = lockService.executeWithLock("my-resource", Duration.ofSeconds(30), () -> "task-result");

        assertEquals("task-result", result);
        // Verify lock was released
        verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    void executeWithLock_LockAcquisitionFails_ThrowsException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(false);

        assertThrows(RuntimeException.class,
            () -> lockService.executeWithLock("my-resource", Duration.ofSeconds(30), () -> "result"));
    }

    @Test
    void executeWithLock_TaskThrows_StillReleasesLock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
            .thenReturn(1L);

        assertThrows(RuntimeException.class,
            () -> lockService.executeWithLock("my-resource", Duration.ofSeconds(30), () -> {
                throw new RuntimeException("Task failed");
            }));

        // Verify lock was still released in finally block
        verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    // ─── isLocked ────────────────────────────────────────────────────────

    @Test
    void isLocked_True() {
        when(redisTemplate.hasKey("lock:my-resource")).thenReturn(true);

        assertTrue(lockService.isLocked("my-resource"));
    }

    @Test
    void isLocked_False() {
        when(redisTemplate.hasKey("lock:my-resource")).thenReturn(false);

        assertFalse(lockService.isLocked("my-resource"));
    }

    @Test
    void isLocked_NullResult_ReturnsFalse() {
        when(redisTemplate.hasKey("lock:my-resource")).thenReturn(null);

        assertFalse(lockService.isLocked("my-resource"));
    }

    @Test
    void isLocked_RedisException_ReturnsFalse() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("Redis error"));

        assertFalse(lockService.isLocked("my-resource"));
    }

    // ─── DistributedLockService static methods ───────────────────────────

    @Test
    void seatReservationLock_FormatsCorrectly() {
        String key = DistributedLockService.seatReservationLock(1L, 42L);
        assertEquals("seat_reservation:1:42", key);
    }

    @Test
    void eventBookingLock_FormatsCorrectly() {
        String key = DistributedLockService.eventBookingLock(99L);
        assertEquals("event_booking:99", key);
    }

    @Test
    void customerBookingLock_FormatsCorrectly() {
        String key = DistributedLockService.customerBookingLock(100L);
        assertEquals("customer_booking:100", key);
    }
}
