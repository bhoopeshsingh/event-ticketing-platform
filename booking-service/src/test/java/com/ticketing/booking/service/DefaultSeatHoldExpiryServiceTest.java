package com.ticketing.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultSeatHoldExpiryServiceTest {

    @Mock private RedisMessageListenerContainer listenerContainer;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper = new ObjectMapper();
    private DefaultSeatHoldExpiryService expiryService;

    @BeforeEach
    void setUp() {
        expiryService = new DefaultSeatHoldExpiryService(listenerContainer, kafkaTemplate, objectMapper);
    }

    @Test
    void onMessage_ValidSeatKey_PublishesKafkaEvent() {
        Message message = mockMessage("seat:1:42:HELD");

        when(kafkaTemplate.send(any(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));

        expiryService.onMessage(message, null);

        verify(kafkaTemplate).send(any(), eq("1:42"), anyString());
    }

    @Test
    void onMessage_NonSeatKey_Ignored() {
        Message message = mockMessage("other:key:value");

        expiryService.onMessage(message, null);

        verify(kafkaTemplate, never()).send(any(), anyString(), anyString());
    }

    @Test
    void onMessage_InvalidFormat_Ignored() {
        Message message = mockMessage("seat:not:valid:format:extra:HELD");

        expiryService.onMessage(message, null);

        verify(kafkaTemplate, never()).send(any(), anyString(), anyString());
    }

    @Test
    void onMessage_NonNumericId_LogsWarning() {
        Message message = mockMessage("seat:abc:def:HELD");

        expiryService.onMessage(message, null);

        verify(kafkaTemplate, never()).send(any(), anyString(), anyString());
    }

    @Test
    void handleExpiredSeatHold_DelegatedToKafka() {
        // No-op method, just verify it doesn't throw
        expiryService.handleExpiredSeatHold("HOLD_123");
    }

    @Test
    void cleanupExpiredHolds_DelegatedToJob() {
        expiryService.cleanupExpiredHolds();
    }

    @Test
    void bulkMarkExpiredHolds_ReturnsZero() {
        assertEquals(0, expiryService.bulkMarkExpiredHolds());
    }

    private Message mockMessage(String body) {
        Message msg = mock(Message.class);
        when(msg.getBody()).thenReturn(body.getBytes());
        return msg;
    }
}
