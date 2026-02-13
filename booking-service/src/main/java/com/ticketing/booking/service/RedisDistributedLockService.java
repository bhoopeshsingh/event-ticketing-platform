package com.ticketing.booking.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Service
@Slf4j
public class RedisDistributedLockService implements DistributedLockService {

    private final StringRedisTemplate redisTemplate;

    // Lua script for atomic lock release
    private static final String RELEASE_LOCK_SCRIPT =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('DEL', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end";

    private final DefaultRedisScript<Long> releaseLockScript;

    @Autowired
    public RedisDistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.releaseLockScript = new DefaultRedisScript<>(RELEASE_LOCK_SCRIPT, Long.class);
    }

    /**
     * Acquire a distributed lock with automatic expiry
     */
    @Override
    public String acquireLock(String lockKey, Duration timeout) {
        String lockToken = UUID.randomUUID().toString();
        String fullLockKey = "lock:" + lockKey;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(fullLockKey, lockToken, timeout);

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Acquired lock: {} with token: {}", lockKey, lockToken);
                return lockToken;
            } else {
                log.debug("Failed to acquire lock: {}", lockKey);
                return null;
            }
        } catch (Exception e) {
            log.error("Error acquiring lock: {}", lockKey, e);
            return null;
        }
    }

    /**
     * Release a distributed lock using Lua script for atomicity
     */
    @Override
    public boolean releaseLock(String lockKey, String lockToken) {
        String fullLockKey = "lock:" + lockKey;

        try {
            Long result = redisTemplate.execute(
                releaseLockScript,
                Collections.singletonList(fullLockKey),
                lockToken
            );

            boolean released = result != null && result == 1;
            if (released) {
                log.debug("Released lock: {} with token: {}", lockKey, lockToken);
            } else {
                log.warn("Failed to release lock: {} with token: {}", lockKey, lockToken);
            }

            return released;
        } catch (Exception e) {
            log.error("Error releasing lock: {} with token: {}", lockKey, lockToken, e);
            return false;
        }
    }

    /**
     * Execute a task while holding a distributed lock
     */
    @Override
    public <T> T executeWithLock(String lockKey, Duration timeout, DistributedTask<T> task) {
        String lockToken = acquireLock(lockKey, timeout);

        if (lockToken == null) {
            throw new RuntimeException("Unable to acquire lock: " + lockKey);
        }

        try {
            return task.execute();
        } finally {
            releaseLock(lockKey, lockToken);
        }
    }

    /**
     * Check if a lock exists
     */
    @Override
    public boolean isLocked(String lockKey) {
        String fullLockKey = "lock:" + lockKey;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(fullLockKey));
        } catch (Exception e) {
            log.error("Error checking lock existence: {}", lockKey, e);
            return false;
        }
    }
}
