package com.reservation.booking_system.entity;

import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ReservationSeatId implements Serializable {

    private UUID reservationId;
    private UUID seatId;
}
