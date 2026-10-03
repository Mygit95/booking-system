package com.reservation.booking_system;

import com.reservation.booking_system.entity.Seat;
import com.reservation.booking_system.entity.SeatStatus;
import com.reservation.booking_system.entity.Show;
import com.reservation.booking_system.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationConcurrencyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSeatRepository reservationSeatRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private UserShowLimitRepository userShowLimitRepository;

    private final RestTemplate restTemplate = new RestTemplate();

    private UUID showId;

    @BeforeEach
    void setUp() {

        reservationSeatRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        userShowLimitRepository.deleteAll();
        showRepository.deleteAll();

        Show show = Show.builder()
                .name("Concurrency Test Show")
                .pricePaise(25000L)
                .perUserLimit(4)
                .createdAt(java.time.Instant.now())
                .build();

        show = showRepository.save(show);
        showId = show.getId();

        List<Seat> seats = List.of(
                createSeat(show, "A1"),
                createSeat(show, "A2"),
                createSeat(show, "A3"),
                createSeat(show, "A4"),
                createSeat(show, "A5")
        );

        seatRepository.saveAll(seats);
    }

    private Seat createSeat(Show show, String seatNumber) {
        return Seat.builder()
                .show(show)
                .seatNumber(seatNumber)
                .status(SeatStatus.AVAILABLE)
                .createdAt(java.time.Instant.now())
                .build();
    }

    private String reserve(
            String userId,
            String seat,
            String idempotencyKey) {

        String url = "http://localhost:" + port
                + "/shows/" + showId + "/reserve";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId);

        String body = """
                {
                    "seats": ["%s"],
                    "idempotency_key": "%s"
                }
                """.formatted(seat, idempotencyKey);

        HttpEntity<String> request =
                new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response =
                    restTemplate.postForEntity(
                            url,
                            request,
                            String.class
                    );

            return response.getStatusCode().toString();

        } catch (HttpStatusCodeException e) {
            return e.getStatusCode().toString();
        }
        catch (Exception e) {
            return "ERROR: "
                    + e.getClass().getSimpleName()
                    + " - "
                    + e.getMessage();
        }
    }

    /**
     * 1 seat, 50 concurrent requests
     * @throws Exception
     */
    @Test
    void onlyOneUserCanReserveTheSameSeatConcurrently()
            throws Exception {

        int numberOfRequests = 100;

        ExecutorService executor =
                Executors.newFixedThreadPool(numberOfRequests);

        CountDownLatch ready =
                new CountDownLatch(numberOfRequests);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfRequests; i++) {

            int userNumber = i;

            futures.add(executor.submit(() -> {

                ready.countDown();

                start.await();

                return reserve(
                        "user-" + userNumber,
                        "A1",
                        UUID.randomUUID().toString()
                );
            }));
        }

        // Wait until all threads are ready.
        assertTrue(
                ready.await(10, TimeUnit.SECONDS)
        );

        // Release everyone at approximately the same time.
        start.countDown();

        long successfulRequests = 0;
        long conflictRequests = 0;

        for (Future<String> future : futures) {

            String result = future.get(
                    10,
                    TimeUnit.SECONDS
            );

            if (result.startsWith("201")) {
                successfulRequests++;
            }

            if (result.startsWith("409")) {
                conflictRequests++;
            }
        }

        executor.shutdown();

        assertEquals(1, successfulRequests);
        assertEquals(99, conflictRequests);

        List<Seat> seats =
                seatRepository.findByShowIdOrderBySeatNumber(showId);

        Seat a1 = seats.stream()
                .filter(s -> s.getSeatNumber().equals("A1"))
                .findFirst()
                .orElseThrow();

        assertEquals(
                SeatStatus.CONFIRMED,
                a1.getStatus()
        );

        assertEquals(
                1,
                reservationRepository.count()
        );
    }

    /**
     * Same key, idempotency check
     * @throws Exception
     */
    @Test
    void sameIdempotencyKeyConcurrentlyCreatesOnlyOneReservation()
            throws Exception {

        int numberOfRequests = 100;

        ExecutorService executor =
                Executors.newFixedThreadPool(numberOfRequests);

        CountDownLatch ready =
                new CountDownLatch(numberOfRequests);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<String>> futures = new ArrayList<>();

        String idempotencyKey = UUID.randomUUID().toString();

        for (int i = 0; i < numberOfRequests; i++) {

            futures.add(executor.submit(() -> {

                ready.countDown();

                start.await();

                return reserve(
                        "user-1",
                        "A1",
                        idempotencyKey
                );
            }));
        }

        assertTrue(
                ready.await(10, TimeUnit.SECONDS)
        );

        start.countDown();

        long successfulRequests = 0;
        long conflictRequests = 0;
        long errors = 0;

        for (Future<String> future : futures) {

            String result = future.get(
                    10,
                    TimeUnit.SECONDS
            );

            if (result.startsWith("201")) {
                successfulRequests++;
            } else if (result.startsWith("409")) {
                conflictRequests++;
            } else {
                errors++;
            }
        }

        executor.shutdown();

        assertEquals(100, successfulRequests);
        assertEquals(0, conflictRequests);
        assertEquals(0, errors);

        Seat a1 = seatRepository
                .findByShowIdOrderBySeatNumber(showId)
                .stream()
                .filter(seat ->
                        seat.getSeatNumber().equals("A1"))
                .findFirst()
                .orElseThrow();

        assertEquals(
                SeatStatus.CONFIRMED,
                a1.getStatus()
        );

        assertEquals(
                1,
                reservationRepository.count()
        );
    }

    /**
     * Same User book 4 seats approximately
     * @throws Exception
     */
    @Test
    void sameUserCannotExceedPerUserLimitConcurrently()
            throws Exception {

        int numberOfRequests = 100;

        ExecutorService executor =
                Executors.newFixedThreadPool(numberOfRequests);

        CountDownLatch ready =
                new CountDownLatch(numberOfRequests);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfRequests; i++) {

            String seat = "A" + ((i % 5) + 1);

            String idempotencyKey =
                    UUID.randomUUID().toString();

            futures.add(executor.submit(() -> {

                ready.countDown();

                start.await();

                return reserve(
                        "same-user",
                        seat,
                        idempotencyKey
                );
            }));
        }

        assertTrue(
                ready.await(10, TimeUnit.SECONDS)
        );

        start.countDown();

        long successfulRequests = 0;
        long conflictRequests = 0;
        long errors = 0;

        for (Future<String> future : futures) {

            String result = future.get(
                    10,
                    TimeUnit.SECONDS
            );

            if (result.startsWith("201")) {
                successfulRequests++;
            } else if (result.startsWith("409")) {
                conflictRequests++;
            } else {
                errors++;
            }
        }

        executor.shutdown();

        assertEquals(4, successfulRequests);
        assertEquals(0, errors);

        assertEquals(
                4,
                seatRepository
                        .findByShowIdOrderBySeatNumber(showId)
                        .stream()
                        .filter(seat ->
                                seat.getStatus() == SeatStatus.CONFIRMED)
                        .count()
        );

        assertEquals(
                4,
                reservationRepository.count()
        );
    }

    /**
     * Different Users try different seats - all or nothing
     * @throws Exception
     */
    @Test
    void differentUsersCanReserveDifferentSeatsConcurrently()
            throws Exception {

        int numberOfRequests = 100;

        ExecutorService executor =
                Executors.newFixedThreadPool(numberOfRequests);

        CountDownLatch ready =
                new CountDownLatch(numberOfRequests);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfRequests; i++) {

            int userNumber = i;
            String seat = "A" + (i + 1);

            futures.add(executor.submit(() -> {

                ready.countDown();

                start.await();

                return reserve(
                        "user-" + userNumber,
                        seat,
                        UUID.randomUUID().toString()
                );
            }));
        }

        assertTrue(
                ready.await(10, TimeUnit.SECONDS)
        );

        start.countDown();

        long successfulRequests = 0;
        long errors = 0;

        for (Future<String> future : futures) {

            String result = future.get(
                    10,
                    TimeUnit.SECONDS
            );

            if (result.startsWith("201")) {
                successfulRequests++;
            } else if (!result.startsWith("409")) {
                errors++;
            }
        }

        executor.shutdown();

        assertEquals(5, successfulRequests);
        assertEquals(0, errors);

        long confirmedSeats =
                seatRepository
                        .findByShowIdOrderBySeatNumber(showId)
                        .stream()
                        .filter(seat ->
                                seat.getStatus() == SeatStatus.CONFIRMED)
                        .count();

        assertEquals(5, confirmedSeats);

        assertEquals(
                5,
                reservationRepository.count()
        );
    }
}
