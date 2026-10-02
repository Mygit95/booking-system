package com.reservation.booking_system.repository;

import com.reservation.booking_system.entity.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    @Query("""
        SELECT s
        FROM Seat s
        WHERE s.show.id = :showId
        ORDER BY s.seatNumber ASC
    """)
    List<Seat> findByShowIdOrderBySeatNumber(
            @Param("showId") UUID showId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s
        FROM Seat s
        WHERE s.show.id = :showId
          AND s.seatNumber IN :seatNumbers
        ORDER BY s.seatNumber ASC
    """)
    List<Seat> findSeatsForUpdate(
            @Param("showId") UUID showId,
            @Param("seatNumbers") List<String> seatNumbers
    );
}