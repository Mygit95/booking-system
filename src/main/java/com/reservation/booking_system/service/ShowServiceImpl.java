package com.reservation.booking_system.service;

import com.reservation.booking_system.dto.CreateShowRequest;
import com.reservation.booking_system.dto.SeatResponse;
import com.reservation.booking_system.dto.ShowResponse;
import com.reservation.booking_system.entity.Seat;
import com.reservation.booking_system.entity.SeatStatus;
import com.reservation.booking_system.entity.Show;
import com.reservation.booking_system.exception.ResourceNotFoundException;
import com.reservation.booking_system.repository.SeatRepository;
import com.reservation.booking_system.repository.ShowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShowServiceImpl implements ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    @Override
    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {

        Show show = Show.builder()
                .name(request.name())
                .pricePaise(request.price_paise())
                .perUserLimit(
                        request.per_user_limit() != null
                                ? request.per_user_limit()
                                : 4
                )
                .createdAt(Instant.now())
                .build();

        show = showRepository.save(show);

        Show savedShow = show;

        List<Seat> seats = request.seats()
                .stream()
                .distinct()
                .map(seatNumber -> Seat.builder()
                        .show(savedShow)
                        .seatNumber(seatNumber)
                        .status(SeatStatus.AVAILABLE)
                        .createdAt(Instant.now())
                        .build())
                .toList();

        seatRepository.saveAll(seats);

        return toShowResponse(show, seats);
    }

    @Override
    @Transactional(readOnly = true)
    public ShowResponse getShow(UUID showId) {

        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Show not found: " + showId
                        ));

        List<Seat> seats =
                seatRepository.findByShowIdOrderBySeatNumber(showId);

        long available = seats.stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .count();

        long held = seats.stream()
                .filter(s -> s.getStatus() == SeatStatus.HELD)
                .count();

        long confirmed = seats.stream()
                .filter(s -> s.getStatus() == SeatStatus.CONFIRMED)
                .count();

        return new ShowResponse(
                show.getId(),
                show.getName(),
                show.getPricePaise(),
                seats.size(),
                (int) available,
                (int) held,
                (int) confirmed,
                seats.stream()
                        .map(this::toSeatResponse)
                        .toList()
        );
    }

    private ShowResponse toShowResponse(
            Show show,
            List<Seat> seats) {

        int totalSeats = seats.size();

        int availableSeats = (int) seats.stream()
                .filter(seat -> seat.getStatus() == SeatStatus.AVAILABLE)
                .count();

        int heldSeats = (int) seats.stream()
                .filter(seat -> seat.getStatus() == SeatStatus.HELD)
                .count();

        int confirmedSeats = (int) seats.stream()
                .filter(seat -> seat.getStatus() == SeatStatus.CONFIRMED)
                .count();

        return new ShowResponse(
                show.getId(),
                show.getName(),
                show.getPricePaise(),
                totalSeats,
                availableSeats,
                heldSeats,
                confirmedSeats,
                seats.stream()
                        .map(this::toSeatResponse)
                        .toList()
        );
    }

    private SeatResponse toSeatResponse(Seat seat) {

        return new SeatResponse(
                seat.getId(),
                seat.getSeatNumber(),
                seat.getStatus().name()
        );
    }
}