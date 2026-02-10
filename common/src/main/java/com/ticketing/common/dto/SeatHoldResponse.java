package com.ticketing.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatHoldResponse {

    private String holdToken;
    private Long customerId;
    private Long eventId;
    private String eventTitle;
    private int seatCount;
    private BigDecimal totalAmount;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;

    private long timeRemainingSeconds;
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    // Payment URL for completing the booking
    private String paymentUrl;

    // Instructions for the user
    private String message;
}
