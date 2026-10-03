package com.reservation.booking_system;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DockerReservationBurstTest {

    private static final String BASE_URL = "http://localhost:8080";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void twentyThousandDifferentUsersSameSeatAgainstDocker() throws Exception {

        /*
         * ---------------------------------------------------------
         * 1. Create a fresh show through the deployed REST API
         * ---------------------------------------------------------
         */

        String showId = createShow();

        System.out.println();
        System.out.println("Docker show created: " + showId);
        System.out.println();


        /*
         * ---------------------------------------------------------
         * 2. Burst configuration
         * ---------------------------------------------------------
         */

        int totalRequests = 20_000;
        int workers = 200;

        String targetSeat = "A1";


        /*
         * ---------------------------------------------------------
         * 3. Counters
         * ---------------------------------------------------------
         */

        AtomicInteger successCount =
                new AtomicInteger();

        AtomicInteger conflictCount =
                new AtomicInteger();

        AtomicInteger errorCount =
                new AtomicInteger();

        ConcurrentHashMap<String, AtomicInteger> errorTypes =
                new ConcurrentHashMap<>();


        /*
         * ---------------------------------------------------------
         * 4. Executor and start latch
         *
         * IMPORTANT:
         * We do NOT use a readyLatch for all 20,000 tasks.
         *
         * With only 200 worker threads, waiting for all 20,000
         * tasks to become "ready" would deadlock the test.
         * ---------------------------------------------------------
         */

        ExecutorService executor =
                Executors.newFixedThreadPool(workers);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        List<Future<?>> futures =
                new ArrayList<>(totalRequests);


        try {

            /*
             * -----------------------------------------------------
             * 5. Submit all 20,000 requests
             * -----------------------------------------------------
             */

            for (int i = 0; i < totalRequests; i++) {

                final int requestNumber = i;

                futures.add(
                        executor.submit(() -> {

                            try {

                                /*
                                 * All worker threads wait here.
                                 *
                                 * Once the main thread releases the
                                 * latch, the available 200 workers
                                 * start sending requests.
                                 */

                                startLatch.await();


                                String userId =
                                        "docker-hot-seat-user-"
                                                + requestNumber;

                                String idempotencyKey =
                                        "docker-hot-seat-key-"
                                                + requestNumber;


                                String result =
                                        reserve(
                                                showId,
                                                userId,
                                                targetSeat,
                                                idempotencyKey
                                        );


                                /*
                                 * ---------------------------------
                                 * Classify response
                                 * ---------------------------------
                                 */

                                if (result.startsWith("201")) {

                                    successCount.incrementAndGet();

                                } else if (result.startsWith("409")) {

                                    conflictCount.incrementAndGet();

                                } else {

                                    errorCount.incrementAndGet();

                                    errorTypes.computeIfAbsent(
                                            result,
                                            key -> new AtomicInteger()
                                    ).incrementAndGet();
                                }


                            } catch (Exception e) {

                                errorCount.incrementAndGet();

                                String error =
                                        e.getClass().getSimpleName()
                                                + " - "
                                                + e.getMessage();

                                errorTypes.computeIfAbsent(
                                        error,
                                        key -> new AtomicInteger()
                                ).incrementAndGet();
                            }
                        })
                );
            }


            /*
             * ---------------------------------------------------------
             * 6. Start the burst
             * ---------------------------------------------------------
             */

            long startTime =
                    System.currentTimeMillis();

            startLatch.countDown();


            /*
             * ---------------------------------------------------------
             * 7. Wait for every request to finish
             * ---------------------------------------------------------
             */

            for (Future<?> future : futures) {

                future.get(
                        180,
                        TimeUnit.SECONDS
                );
            }


            long elapsedMs =
                    System.currentTimeMillis()
                            - startTime;


            /*
             * ---------------------------------------------------------
             * 8. Calculate throughput
             * ---------------------------------------------------------
             */

            double requestsPerSecond =
                    totalRequests
                            / (elapsedMs / 1000.0);


            /*
             * ---------------------------------------------------------
             * 9. Print results
             * ---------------------------------------------------------
             */

            System.out.println();

            System.out.println(
                    "======= DOCKER 20K HOT-SEAT BURST RESULT ======="
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
                    "Target seat:         "
                            + targetSeat
            );

            System.out.println(
                    "201 responses:       "
                            + successCount.get()
            );

            System.out.println(
                    "409 responses:       "
                            + conflictCount.get()
            );

            System.out.println(
                    "Errors:              "
                            + errorCount.get()
            );

            System.out.println(
                    "Elapsed ms:          "
                            + elapsedMs
            );

            System.out.println(
                    "Requests/sec:        "
                            + requestsPerSecond
            );


            /*
             * ---------------------------------------------------------
             * 10. Print error breakdown
             * ---------------------------------------------------------
             */

            System.out.println();

            System.out.println(
                    "---------- ERROR BREAKDOWN ----------"
            );

            if (errorTypes.isEmpty()) {

                System.out.println(
                        "[NONE]"
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


            /*
             * ---------------------------------------------------------
             * 11. Validate HTTP results
             * ---------------------------------------------------------
             */

            assertEquals(
                    1,
                    successCount.get(),
                    "Exactly one request should reserve A1"
            );

            assertEquals(
                    totalRequests - 1,
                    conflictCount.get(),
                    "All other requests should receive 409"
            );

            assertEquals(
                    0,
                    errorCount.get(),
                    "There should be no unexpected errors"
            );


            /*
             * ---------------------------------------------------------
             * 12. Verify final show state through REST API
             * ---------------------------------------------------------
             */

            JsonNode show =
                    getShow(showId);

            JsonNode seats =
                    show.get("seats");


            int confirmedSeats = 0;
            int availableSeats = 0;
            int heldSeats = 0;


            for (JsonNode seat : seats) {

                String seatNumber =
                        seat.get("seat_number")
                                .asText();

                String status =
                        seat.get("status")
                                .asText();


                if ("CONFIRMED".equals(status)) {

                    confirmedSeats++;
                }

                if ("AVAILABLE".equals(status)) {

                    availableSeats++;
                }

                if ("HELD".equals(status)) {

                    heldSeats++;
                }


                /*
                 * A1 specifically must be confirmed.
                 */

                if (targetSeat.equals(seatNumber)) {

                    assertEquals(
                            "CONFIRMED",
                            status,
                            "A1 must be CONFIRMED"
                    );
                }
            }


            /*
             * ---------------------------------------------------------
             * 13. Verify seat-state invariant
             *
             * available + held + confirmed = total
             * ---------------------------------------------------------
             */

            int totalSeats =
                    show.get("total_seats")
                            .asInt();


            assertEquals(
                    totalSeats,
                    availableSeats
                            + heldSeats
                            + confirmedSeats,
                    "Seat state invariant must hold"
            );


            /*
             * Exactly one seat should have been sold.
             */

            assertEquals(
                    1,
                    confirmedSeats,
                    "Exactly one seat should be confirmed"
            );


            /*
             * HELD is not used in our cancellation-based design.
             */

            assertEquals(
                    0,
                    heldSeats,
                    "HELD should remain unused"
            );


            /*
             * Four seats should remain available because the
             * show contains five seats and only A1 was purchased.
             */

            assertEquals(
                    totalSeats - 1,
                    availableSeats,
                    "All seats except A1 should remain available"
            );


            /*
             * ---------------------------------------------------------
             * 14. Success message
             * ---------------------------------------------------------
             */

            System.out.println();

            System.out.println(
                    "Final show state verified successfully."
            );

            System.out.println(
                    "Confirmed seats: "
                            + confirmedSeats
            );

            System.out.println(
                    "Available seats: "
                            + availableSeats
            );

            System.out.println(
                    "Held seats:      "
                            + heldSeats
            );

            System.out.println();

            System.out.println(
                    "======= DOCKER BURST TEST PASSED ======="
            );

        } finally {

            /*
             * ---------------------------------------------------------
             * 15. Always shut down executor
             * ---------------------------------------------------------
             */

            executor.shutdown();

            if (!executor.awaitTermination(
                    30,
                    TimeUnit.SECONDS)) {

                executor.shutdownNow();
            }
        }
    }

    /*
     * =============================================================
     * Create show
     * =============================================================
     */

    private String createShow() throws Exception {

        String url =
                BASE_URL + "/shows";

        HttpHeaders headers =
                new HttpHeaders();

        headers.setContentType(
                MediaType.APPLICATION_JSON
        );

        String body = """
                {
                    "name": "Docker 20K Burst %s",
                    "seats": [
                        "A1",
                        "A2",
                        "A3",
                        "A4",
                        "A5"
                    ],
                    "price_paise": 25000,
                    "per_user_limit": 4
                }
                """.formatted(UUID.randomUUID());

        HttpEntity<String> request =
                new HttpEntity<>(
                        body,
                        headers
                );

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        url,
                        request,
                        String.class
                );

        assertEquals(
                HttpStatus.CREATED,
                response.getStatusCode()
        );

        JsonNode json =
                objectMapper.readTree(
                        response.getBody()
                );

        return json.get("id").asText();
    }


    /*
     * =============================================================
     * Reserve seat
     * =============================================================
     */

    private String reserve(
            String showId,
            String userId,
            String seat,
            String idempotencyKey) {

        String url =
                BASE_URL
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
                    .value()
                    + " SUCCESS";

        } catch (HttpStatusCodeException e) {

            return e.getStatusCode()
                    .value()
                    + " HTTP_ERROR: "
                    + e.getResponseBodyAsString();

        } catch (Exception e) {

            return "CLIENT_ERROR: "
                    + e.getClass().getSimpleName()
                    + " - "
                    + e.getMessage();
        }
    }


    /*
     * =============================================================
     * Get show
     * =============================================================
     */

    private JsonNode getShow(
            String showId) throws Exception {

        String url =
                BASE_URL
                        + "/shows/"
                        + showId;

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        url,
                        String.class
                );

        assertEquals(
                HttpStatus.OK,
                response.getStatusCode()
        );

        return objectMapper.readTree(
                response.getBody()
        );
    }
}