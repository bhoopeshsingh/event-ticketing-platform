package com.ticketing.common.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingConfirmRequest {

    @NotBlank(message = "Hold token is required")
    private String holdToken;

    @NotBlank(message = "Payment ID is required")
    private String paymentId;

    @NotNull(message = "Customer ID is required")
    private Long customerId;

    // Optional: customer details for ticket generation
    @Size(max = 100)
    private String customerEmail;

    @Size(max = 100)
    private String customerName;

    // Optional: special requests
    @Size(max = 500)
    private String specialRequests;
}
