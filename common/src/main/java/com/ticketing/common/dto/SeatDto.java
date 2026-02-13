package com.ticketing.common.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    @NotNull(message = "Event ID is required")
    private Long eventId;

    @NotBlank(message = "Section is required")
    @Size(max = 50)
    private String section;

    @NotBlank(message = "Row letter is required")
    @Size(max = 10)
    private String rowLetter;

    @NotNull(message = "Seat number is required")
    @Positive(message = "Seat number must be positive")
    private Integer seatNumber;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price must be non-negative")
    private BigDecimal price;

    private String status; // AVAILABLE, HELD, BOOKED

    // Helper methods
    public String getSeatIdentifier() {
        return String.format("%s-%s%d", section, rowLetter, seatNumber);
    }

    public boolean isAvailable() {
        return "AVAILABLE".equals(status);
    }

    public boolean isSelected() {
        return "HELD".equals(status) || "BOOKED".equals(status);
    }
}
