package com.reservation.booking_system.service;

import com.reservation.booking_system.dto.ReservationResponse;
import com.reservation.booking_system.dto.ReserveRequest;
import java.util.UUID;
public interface ReservationService {

    ReservationResponse reserve(
            UUID showId,
            String userId,
            ReserveRequest request
    );

    ReservationResponse cancel(
            UUID reservationId,
            String userId
    );
}
