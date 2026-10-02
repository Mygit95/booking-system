package com.reservation.booking_system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReserveRequest(

        @NotEmpty
        List<@NotBlank String> seats,

        @NotBlank
        String idempotency_key
) {
}