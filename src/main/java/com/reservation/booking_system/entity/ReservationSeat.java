package com.reservation.booking_system.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "reservation_seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(ReservationSeatId.class)
public class ReservationSeat {

    @Id
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Id
    @Column(name = "seat_id")
    private UUID seatId;
}
