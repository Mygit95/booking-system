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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationBurstTest {

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

    private UUID showId;

    private final RestTemplate restTemplate =
            new RestTemplate();

    @BeforeEach
    void setUp() {

        reservationSeatRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        userShowLimitRepository.deleteAll();
        showRepository.deleteAll();

        Show show = Show.builder()
                .name("20K Burst Test")
                .pricePaise(25000L)
                .perUserLimit(4)
                .createdAt(Instant.now())
                .build();

        Show createdShow =
                showRepository.save(show);

        List<String> seatNumbers =
                List.of(
                        "A1",
                        "A2",
                        "A3",
                        "A4",
                        "A5"
                );

        List<Seat> seats =
                seatNumbers.stream()
                        .map(seatNumber ->
                                Seat.builder()
                                        .show(createdShow)
                                        .seatNumber(seatNumber)
                                        .status(SeatStatus.AVAILABLE)
                                        .createdAt(Instant.now())
                                        .build()
                        )
                        .toList();

        seatRepository.saveAll(seats);

        showId = createdShow.getId();
    }

    @Test
    void twentyThousandSameIdempotencyRequests()
            throws Exception {

        int totalRequests = 20_000;
        int workers = 200;

        ExecutorService executor =
                Executors.newFixedThreadPool(workers);

        CountDownLatch start =
                new CountDownLatch(1);

        AtomicInteger successfulRequests =
                new AtomicInteger();

        AtomicInteger conflictRequests =
                new AtomicInteger();

        AtomicInteger errors =
                new AtomicInteger();

        ConcurrentHashMap<String, AtomicInteger>
                errorTypes =
                new ConcurrentHashMap<>();

        String idempotencyKey =
                UUID.randomUUID().toString();

        List<Future<?>> futures =
                new ArrayList<>();

        long startTime =
                System.currentTimeMillis();

        try {

            for (int i = 0; i < totalRequests; i++) {

                futures.add(
                        executor.submit(() -> {

                            try {

                                start.await();

                                String result =
                                        reserve(
                                                "burst-user",
                                                "A1",
                                                idempotencyKey
                                        );

                                if (result.startsWith("201")) {

                                    successfulRequests
                                            .incrementAndGet();

                                } else if (result.startsWith("409")) {

                                    conflictRequests
                                            .incrementAndGet();

                                } else {

                                    errors.incrementAndGet();

                                    errorTypes
                                            .computeIfAbsent(
                                                    result,
                                                    key ->
                                                            new AtomicInteger()
                                            )
                                            .incrementAndGet();
                                }

                            } catch (Exception e) {

                                errors.incrementAndGet();

                                String error =
                                        "TEST_ERROR: "
                                                + e.getClass()
                                                .getSimpleName()
                                                + " - "
                                                + e.getMessage();

                                errorTypes
                                        .computeIfAbsent(
                                                error,
                                                key ->
                                                        new AtomicInteger()
                                        )
                                        .incrementAndGet();
                            }

                            return null;
                        })
                );
            }

            start.countDown();

            for (Future<?> future : futures) {

                future.get(
                        120,
                        TimeUnit.SECONDS
                );
            }

        } finally {

            executor.shutdown();

            if (!executor.awaitTermination(
                    30,
                    TimeUnit.SECONDS)) {

                executor.shutdownNow();
            }
        }

        long elapsed =
                System.currentTimeMillis()
                        - startTime;

        System.out.println();
        System.out.println(
                "========== 20K BURST RESULT =========="
        );

        System.out.println(
                "Total requests:      "
                        + totalRequests
        );

        System.out.println(
                "Workers:             "
                        + workers
        );

        System.out.println(
                "201 responses:       "
                        + successfulRequests.get()
        );

        System.out.println(
                "409 responses:       "
                        + conflictRequests.get()
        );

        System.out.println(
                "Errors:              "
                        + errors.get()
        );

        System.out.println(
                "Elapsed ms:          "
                        + elapsed
        );

        System.out.println(
                "Requests/sec:        "
                        + (
                        totalRequests
                                * 1000.0
                                / elapsed
                )
        );

        System.out.println();

        System.out.println(
                "---------- ERROR BREAKDOWN ----------"
        );

        if (errorTypes.isEmpty()) {

            System.out.println(
                    "No errors"
            );

        } else {

            errorTypes.forEach(
                    (error, count) ->
                            System.out.println(
                                    count.get()
                                            + " -> "
                                            + error
                            )
            );
        }

        System.out.println(
                "--------------------------------------"
        );

        System.out.println(
                "======================================"
        );

        System.out.println();

        /*
         * These assertions are intentionally kept.
         *
         * The test should eventually demonstrate:
         *
         * 20,000 requests
         * 20,000 successful responses
         * 0 conflicts
         * 0 errors
         * 1 reservation
         * 1 idempotency key
         * A1 CONFIRMED
         */

        assertEquals(
                totalRequests,
                successfulRequests.get()
        );

        assertEquals(
                0,
                conflictRequests.get()
        );

        assertEquals(
                0,
                errors.get()
        );

        Seat a1 =
                seatRepository
                        .findByShowIdOrderBySeatNumber(showId)
                        .stream()
                        .filter(
                                seat ->
                                        seat.getSeatNumber()
                                                .equals("A1")
                        )
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

        assertEquals(
                1,
                idempotencyKeyRepository.count()
        );
    }

    private String reserve(
            String userId,
            String seat,
            String idempotencyKey) {

        String url =
                "http://localhost:"
                        + port
                        + "/shows/"
                        + showId
                        + "/reserve";

        HttpHeaders headers =
                new HttpHeaders();

        headers.setContentType(
                MediaType.APPLICATION_JSON
        );

        headers.set(
                "X-User-Id",
                userId
        );

        String body = """
                {
                    "seats": ["%s"],
                    "idempotency_key": "%s"
                }
                """.formatted(
                seat,
                idempotencyKey
        );

        HttpEntity<String> request =
                new HttpEntity<>(
                        body,
                        headers
                );

        try {

            ResponseEntity<String> response =
                    restTemplate.postForEntity(
                            url,
                            request,
                            String.class
                    );

            return response
                    .getStatusCode()
                    .toString();

        } catch (HttpStatusCodeException e) {

            return e.getStatusCode().value()
                    + " HTTP_ERROR: "
                    + e.getResponseBodyAsString();

        } catch (Exception e) {

            return "CLIENT_ERROR: "
                    + e.getClass().getSimpleName()
                    + " - "
                    + e.getMessage();
        }
    }

    @Test
    void twentyThousandDifferentUsersSameSeat() throws Exception {

        int totalRequests = 20_000;
        int workers = 200;

        ExecutorService executor =
                Executors.newFixedThreadPool(workers);

        CountDownLatch start =
                new CountDownLatch(1);

        AtomicInteger successfulRequests =
                new AtomicInteger();

        AtomicInteger conflictRequests =
                new AtomicInteger();

        AtomicInteger errors =
                new AtomicInteger();

        ConcurrentHashMap<String, AtomicInteger> errorTypes =
                new ConcurrentHashMap<>();

        List<Future<?>> futures =
                new ArrayList<>();

        long startTime =
                System.currentTimeMillis();

        try {

            for (int i = 0; i < totalRequests; i++) {

                final int requestNumber = i;

                futures.add(
                        executor.submit(() -> {

                            try {

                                start.await();

                                String userId =
                                        "hot-seat-user-" + requestNumber;

                                String idempotencyKey =
                                        "hot-seat-key-" + requestNumber;

                                String result =
                                        reserve(
                                                userId,
                                                "A1",
                                                idempotencyKey
                                        );

                                if (result.startsWith("201")) {

                                    successfulRequests
                                            .incrementAndGet();

                                } else if (result.startsWith("409")) {

                                    conflictRequests
                                            .incrementAndGet();

                                } else {

                                    errors.incrementAndGet();

                                    errorTypes
                                            .computeIfAbsent(
                                                    result,
                                                    key ->
                                                            new AtomicInteger()
                                            )
                                            .incrementAndGet();
                                }

                            } catch (Exception e) {

                                errors.incrementAndGet();

                                String error =
                                        "TEST_ERROR: "
                                                + e.getClass()
                                                .getSimpleName()
                                                + " - "
                                                + e.getMessage();

                                errorTypes
                                        .computeIfAbsent(
                                                error,
                                                key ->
                                                        new AtomicInteger()
                                        )
                                        .incrementAndGet();
                            }

                            return null;
                        })
                );
            }

            /*
             * Release all workers at approximately the same time.
             */
            start.countDown();

            /*
             * Wait for every request to complete.
             */
            for (Future<?> future : futures) {

                future.get(
                        120,
                        TimeUnit.SECONDS
                );
            }

        } finally {

            executor.shutdown();

            if (!executor.awaitTermination(
                    30,
                    TimeUnit.SECONDS)) {

                executor.shutdownNow();
            }
        }

        long elapsed =
                System.currentTimeMillis()
                        - startTime;

        double requestsPerSecond =
                totalRequests
                        * 1000.0
                        / elapsed;

        System.out.println();
        System.out.println(
                "======= 20K HOT-SEAT BURST RESULT ======="
        );

        System.out.println(
                "Total requests:      "
                        + totalRequests
        );

        System.out.println(
                "Workers:             "
                        + workers
        );

        System.out.println(
                "Target seat:         A1"
        );

        System.out.println(
                "201 responses:       "
                        + successfulRequests.get()
        );

        System.out.println(
                "409 responses:       "
                        + conflictRequests.get()
        );

        System.out.println(
                "Errors:              "
                        + errors.get()
        );

        System.out.println(
                "Elapsed ms:          "
                        + elapsed
        );

        System.out.println(
                "Requests/sec:        "
                        + requestsPerSecond
        );

        System.out.println();

        System.out.println(
                "---------- ERROR BREAKDOWN ----------"
        );

        if (errorTypes.isEmpty()) {

            System.out.println(
                    "No errors"
            );

        } else {

            errorTypes.forEach(
                    (error, count) ->
                            System.out.println(
                                    count.get()
                                            + " -> "
                                            + error
                            )
            );
        }

        System.out.println(
                "--------------------------------------"
        );

        System.out.println(
                "======================================="
        );

        System.out.println();

        /*
         * Exactly one request must successfully
         * reserve the seat.
         */
        assertEquals(
                1,
                successfulRequests.get()
        );

        /*
         * Every other request should discover
         * that A1 is already taken.
         */
        assertEquals(
                totalRequests - 1,
                conflictRequests.get()
        );

        /*
         * Absolutely no unexpected errors.
         */
        assertEquals(
                0,
                errors.get()
        );

        /*
         * Verify database state.
         */
        Seat a1 =
                seatRepository
                        .findByShowIdOrderBySeatNumber(showId)
                        .stream()
                        .filter(
                                seat ->
                                        seat.getSeatNumber()
                                                .equals("A1")
                        )
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                SeatStatus.CONFIRMED,
                a1.getStatus()
        );

        /*
         * Only one reservation should exist.
         */
        assertEquals(
                1,
                reservationRepository.count()
        );

        /*
         * Only one idempotency record should exist.
         */
        assertEquals(
                1,
                idempotencyKeyRepository.count()
        );

        /*
         * The remaining four seats must still
         * be available.
         */
        long confirmedSeats =
                seatRepository
                        .findByShowIdOrderBySeatNumber(showId)
                        .stream()
                        .filter(
                                seat ->
                                        seat.getStatus()
                                                == SeatStatus.CONFIRMED
                        )
                        .count();

        long availableSeats =
                seatRepository
                        .findByShowIdOrderBySeatNumber(showId)
                        .stream()
                        .filter(
                                seat ->
                                        seat.getStatus()
                                                == SeatStatus.AVAILABLE
                        )
                        .count();

        assertEquals(
                1,
                confirmedSeats
        );

        assertEquals(
                4,
                availableSeats
        );
    }
}