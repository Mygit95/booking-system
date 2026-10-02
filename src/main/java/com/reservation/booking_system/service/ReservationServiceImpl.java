package com.reservation.booking_system.service;

import com.reservation.booking_system.dto.ReservationResponse;
import com.reservation.booking_system.dto.ReserveRequest;
import com.reservation.booking_system.entity.*;
import com.reservation.booking_system.exception.ConflictException;
import com.reservation.booking_system.exception.ResourceNotFoundException;
import com.reservation.booking_system.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationServiceImpl
        implements ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final UserShowLimitRepository userShowLimitRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @Override
    @Transactional
    public ReservationResponse reserve(
            UUID showId,
            String userId,
            ReserveRequest request) {

        /*
         * STEP 1
         * Load the show.
         */
        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Show not found: " + showId
                        ));

        /*
         * STEP 2
         * Validate the requested seat list.
         */
        List<String> requestedSeats =
                request.seats()
                        .stream()
                        .distinct()
                        .sorted()
                        .toList();

        if (requestedSeats.isEmpty()) {
            throw new ConflictException(
                    "At least one seat is required",
                    "INVALID_REQUEST"
            );
        }

        /*
         * STEP 3
         * Check idempotency.
         *
         * If the same request was already completed,
         * return the original reservation.
         */
        Optional<IdempotencyKey> existing =
                idempotencyKeyRepository
                        .findByShowIdAndUserIdAndIdempotencyKey(
                                showId,
                                userId,
                                request.idempotency_key()
                        );

        if (existing.isPresent()) {

            IdempotencyKey existingKey = existing.get();

            String requestHash =
                    createRequestHash(requestedSeats);

            if (!existingKey.getRequestHash()
                    .equals(requestHash)) {

                throw new ConflictException(
                        "Idempotency key was already used with a different request",
                        "IDEMPOTENCY_CONFLICT"
                );
            }

            return toResponse(
                    existingKey.getReservation()
            );
        }

        /*
         * STEP 4
         * Ensure a user/show limit row exists.
         */
        userShowLimitRepository.createIfAbsent(
                showId,
                userId
        );

        /*
         * STEP 5
         * Lock the user/show row.
         *
         * This serializes reservations for the same
         * user + show combination.
         */
        UserShowLimit userLimit =
                userShowLimitRepository.findForUpdate(
                        showId,
                        userId
                );

        /*
         * STEP 6
         * Check the user's booking limit.
         */
        int requestedCount = requestedSeats.size();

        if (userLimit.getReservedCount() + requestedCount
                > show.getPerUserLimit()) {

            throw new ConflictException(
                    "User booking limit exceeded",
                    "PER_USER_LIMIT"
            );
        }

        /*
         * STEP 7
         * Lock all requested seats.
         *
         * requestedSeats is already sorted.
         * Therefore, every transaction acquires locks
         * in deterministic order.
         */
        List<Seat> seats =
                seatRepository.findSeatsForUpdate(
                        showId,
                        requestedSeats
                );

        /*
         * If the number of rows returned differs,
         * one or more requested seats don't exist.
         */
        if (seats.size() != requestedSeats.size()) {

            throw new ConflictException(
                    "One or more requested seats do not exist",
                    "INVALID_SEAT"
            );
        }

        /*
         * STEP 8
         * Verify ALL seats are available.
         *
         * We deliberately use all-or-nothing semantics.
         */
        boolean allAvailable = seats.stream()
                .allMatch(seat ->
                        seat.getStatus() == SeatStatus.AVAILABLE
                );

        if (!allAvailable) {

            throw new ConflictException(
                    "One or more requested seats are already taken",
                    "SEAT_TAKEN"
            );
        }

        /*
         * STEP 9
         * Create reservation.
         */
        long amountPaise =
                show.getPricePaise() * requestedCount;

        Reservation reservation =
                Reservation.builder()
                        .show(show)
                        .userId(userId)
                        .amountPaise(amountPaise)
                        .status(ReservationStatus.CONFIRMED)
                        .createdAt(Instant.now())
                        .build();

        Reservation savedReservation =
                reservationRepository.save(reservation);

        /*
         * STEP 10
         * Assign seats to reservation.
         */
        List<ReservationSeat> reservationSeats =
                seats.stream()
                        .map(seat -> ReservationSeat.builder()
                                .reservationId(
                                        savedReservation.getId()
                                )
                                .seatId(
                                        seat.getId()
                                )
                                .build())
                        .toList();

        reservationSeatRepository.saveAll(
                reservationSeats
        );

        /*
         * STEP 11
         * Mark seats as confirmed.
         */
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.CONFIRMED);
            seat.setReservation(reservation);
        }

        seatRepository.saveAll(seats);

        /*
         * STEP 12
         * Increment user's reserved count.
         */
        userLimit.setReservedCount(
                userLimit.getReservedCount()
                        + requestedCount
        );

        userShowLimitRepository.save(userLimit);

        /*
         * STEP 13
         * Store idempotency record.
         */
        IdempotencyKey idempotencyKey =
                IdempotencyKey.builder()
                        .show(show)
                        .userId(userId)
                        .idempotencyKey(
                                request.idempotency_key()
                        )
                        .requestHash(
                                createRequestHash(requestedSeats)
                        )
                        .reservation(reservation)
                        .createdAt(Instant.now())
                        .build();

        idempotencyKeyRepository.save(
                idempotencyKey
        );

        /*
         * STEP 14
         * Transaction commits automatically.
         *
         * If anything fails before commit,
         * the entire operation rolls back.
         */

        return toResponse(reservation);
    }

    @Override
    @Transactional
    public ReservationResponse cancel(
            UUID reservationId,
            String userId) {

        Reservation reservation =
                reservationRepository.findById(reservationId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Reservation not found"
                                ));

        /*
         * Only the owner can cancel.
         */
        if (!reservation.getUserId().equals(userId)) {

            throw new ConflictException(
                    "You cannot cancel another user's reservation",
                    "RESERVATION_NOT_OWNED"
            );
        }

        /*
         * Idempotent cancellation.
         */
        if (reservation.getStatus()
                == ReservationStatus.CANCELLED) {

            return toResponse(reservation);
        }

        /*
         * We need the user's limit row locked while
         * decrementing the count.
         */
        UUID showId =
                reservation.getShow().getId();

        UserShowLimit userLimit =
                userShowLimitRepository.findForUpdate(
                        showId,
                        userId
                );

        /*
         * Find all seats belonging to reservation.
         *
         * We'll add this repository query next.
         */

        List<Seat> seats =
                seatRepository.findByReservationId(
                        reservationId
                );

        for (Seat seat : seats) {

            seat.setStatus(SeatStatus.AVAILABLE);
            seat.setReservation(null);
        }

        seatRepository.saveAll(seats);

        /*
         * Decrease reserved count.
         */
        userLimit.setReservedCount(
                Math.max(
                        0,
                        userLimit.getReservedCount()
                                - seats.size()
                )
        );

        userShowLimitRepository.save(userLimit);

        reservation.setStatus(
                ReservationStatus.CANCELLED
        );

        reservation.setCancelledAt(
                Instant.now()
        );

        reservationRepository.save(reservation);

        return toResponse(reservation);
    }

    private String createRequestHash(List<String> seats) {

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            String canonical =
                    String.join("|", seats);

            byte[] hash =
                    digest.digest(
                            canonical.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 algorithm unavailable",
                    e
            );
        }
    }

    private ReservationResponse toResponse(
            Reservation reservation) {

        List<String> seats =
                reservation.getShow()
                        .getId() != null
                        ? reservationSeatRepository
                        .findByReservationId(
                                reservation.getId()
                        )
                        .stream()
                        .map(ReservationSeat::getSeatId)
                        .map(seatId ->
                                seatRepository.findById(seatId)
                                        .orElseThrow()
                                        .getSeatNumber()
                        )
                        .toList()
                        : List.of();

        return new ReservationResponse(
                reservation.getId(),
                reservation.getShow().getId(),
                reservation.getUserId(),
                seats,
                reservation.getAmountPaise(),
                reservation.getStatus()
                        .name()
                        .toLowerCase()
        );
    }
}