package com.reservation.booking_system.dto;

import java.util.List;
import java.util.UUID;

public record ReservationResponse(

        UUID reservation_id,

        UUID show_id,

        String user_id,

        List<String> seats,

        Long amount_paise,

        String status
) {
}