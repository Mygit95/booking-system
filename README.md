# Seat Reservation Service

A concurrency-safe seat reservation service built with **Java 21, Spring Boot, PostgreSQL, JPA/Hibernate, Flyway, Docker, and Prometheus**.

This project was developed as a backend engineering take-home exercise focused on reservation correctness under high concurrency, idempotency, per-user limits, deployment, and observability.

## Live Deployment

The service is deployed and publicly accessible at:

```text
https://booking-system-wlku.onrender.com
```

### Health Check

```http
GET https://booking-system-wlku.onrender.com/actuator/health
```

### API Base URL

```text
https://booking-system-wlku.onrender.com
```

The deployed service can be used to test the reservation APIs directly.

## Architecture

```text
                    ┌─────────────────────┐
                    │      Client         │
                    └──────────┬──────────┘
                               │ HTTP
                               ▼
                    ┌─────────────────────┐
                    │   Spring Boot API   │
                    │                     │
                    │ ReservationService  │
                    │ ShowService          │
                    └──────────┬──────────┘
                               │
                     ┌─────────┴─────────┐
                     │                   │
                     ▼                   ▼
              ┌─────────────┐    ┌──────────────┐
              │ PostgreSQL   │    │  Prometheus  │
              │              │    │   Metrics    │
              └─────────────┘    └──────────────┘
```

The application and PostgreSQL can be run together using Docker Compose.

## Technology Stack

* Java 21
* Spring Boot 4
* Spring Web MVC
* Spring Data JPA
* PostgreSQL 17
* Flyway
* Maven
* Docker / Docker Compose
* Micrometer
* Prometheus
* JUnit 5

## Core Requirements

The service guarantees:

* No double-selling of a seat under concurrent requests
* Per-user booking limits
* Idempotent reservation requests
* Same idempotency key + same request returns the existing reservation
* Same idempotency key + different request is rejected
* Atomic multi-seat reservations
* Explicit reservation cancellation
* Consistent seat-state counts
* Health and readiness endpoints
* Prometheus metrics

## Concurrency Design

### Seat-level concurrency

Requested seats are locked using PostgreSQL row-level pessimistic locking:

```text
SELECT ... FOR UPDATE
```

The application locks requested seats in deterministic seat-number order.

This prevents two concurrent transactions from successfully reserving the same seat.

The reservation transaction follows:

```text
Lock requested seats
        ↓
Verify all seats are AVAILABLE
        ↓
Create reservation
        ↓
Create reservation-seat records
        ↓
Mark seats CONFIRMED
        ↓
Update user's reservation count
        ↓
Create idempotency record
        ↓
COMMIT
```

The complete operation runs inside a single database transaction.

### Per-user limit

The `(show_id, user_id)` row in `user_show_limits` is locked with a pessimistic write lock.

This serializes concurrent reservation attempts for the same user and show.

The limit is therefore enforced atomically even when multiple requests arrive concurrently.

### Idempotency

Idempotency records use:

```text
(show_id, user_id, idempotency_key)
```

as a unique key.

The request also stores a SHA-256 hash of the canonicalized requested seats.

This allows the service to distinguish:

```text
Same key + same request
        → return existing reservation
```

from:

```text
Same key + different request
        → HTTP 409
```

The database unique constraint provides an additional correctness boundary against concurrent inserts.

## Seat State Model

The service uses:

```text
AVAILABLE
CONFIRMED
CANCELLED reservation → AVAILABLE
```

`HELD` is represented in the API/database model but is not used because this implementation uses explicit cancellation rather than time-based holds.

The following invariant is maintained:

```text
available_seats
+ held_seats
+ confirmed_seats
= total_seats
```

## API

### Create a show

```http
POST /shows
Content-Type: application/json
```

Example:

```json
{
  "name": "friday-night",
  "seats": ["A1", "A2", "A3", "A4", "A5"],
  "price_paise": 25000,
  "per_user_limit": 4
}
```

Example response:

```json
{
  "id": "SHOW_UUID",
  "name": "friday-night",
  "price_paise": 25000,
  "total_seats": 5,
  "available_seats": 5,
  "held_seats": 0,
  "confirmed_seats": 0,
  "seats": [
    {
      "id": "SEAT_UUID",
      "seat_number": "A1",
      "status": "AVAILABLE"
    }
  ]
}
```

### Reserve seats

```http
POST /shows/{showId}/reserve
X-User-Id: user-123
Content-Type: application/json
```

Request:

```json
{
  "seats": ["A1"],
  "idempotency_key": "order-12345"
}
```

A successful reservation returns:

```http
201 Created
```

### Get show state

```http
GET /shows/{showId}
```

The response contains:

* Total seats
* Available seats
* Held seats
* Confirmed seats
* Per-seat status

### Cancel reservation

```http
POST /reservations/{reservationId}/cancel
X-User-Id: user-123
```

Cancellation returns the reserved seats to `AVAILABLE`.

## Running Locally

### Prerequisites

* Java 21
* Docker
* Docker Compose

### Run tests

```powershell
.\mvnw.cmd clean test
```

The test suite includes concurrent reservation tests covering:

* Same seat / different users
* Per-user booking limits
* Different users / different seats
* Concurrent idempotency retries
* High-volume hot-seat contention

### Build

```powershell
.\mvnw.cmd clean package -DskipTests
```

### Start with Docker Compose

```powershell
docker compose up --build
```

The application will be available at:

```text
http://localhost:8080
```

Stop the stack:

```powershell
docker compose down
```

The PostgreSQL data is persisted in the `postgres_data` Docker volume.

## Health and Observability

### Health

```text
GET /actuator/health
```

### Liveness

```text
GET /actuator/health/liveness
```

### Readiness

```text
GET /actuator/health/readiness
```

### Prometheus

```text
GET /actuator/prometheus
```

Prometheus-compatible metrics are exposed through Spring Boot Actuator and Micrometer.

## Concurrency Testing

The project contains a deployment-level test:

```text
DockerReservationBurstTest
```

This test treats the Dockerized application as a black box.

The test:

1. Creates a new show through the REST API.
2. Sends 20,000 concurrent reservation attempts.
3. Uses 200 worker threads.
4. Uses 20,000 different users.
5. Uses 20,000 different idempotency keys.
6. Targets the same seat (`A1`).
7. Verifies the final show state through the REST API.

Run it with:

```powershell
.\mvnw.cmd -Dtest=DockerReservationBurstTest test
```

### Dockerized 20K Hot-Seat Result

A completed local Docker deployment produced:

```text
======= DOCKER 20K HOT-SEAT BURST RESULT =======
Total requests:      20000
Workers:             200
Target seat:         A1
201 responses:       1
409 responses:       19999
Errors:              0
Elapsed ms:          59962
Requests/sec:        333.54

---------- ERROR BREAKDOWN ----------
[NONE]

Final show state verified successfully.
```

This demonstrates that under a 20,000-request hot-seat burst, exactly one request successfully reserved the seat and the remaining requests were rejected with `409 Conflict`, with no unexpected errors.

## Error Handling

Typical responses include:

```text
404 NOT_FOUND
```

for missing resources and:

```text
409 CONFLICT
```

for business conflicts such as:

* Seat already reserved
* Per-user booking limit exceeded
* Idempotency key reused with a different request

The API does not convert arbitrary database failures into business-level `409` responses.

## Docker Deployment

The repository contains:

```text
Dockerfile
docker-compose.yml
```

The Docker Compose configuration runs:

```text
Spring Boot application
        +
PostgreSQL
```

The application connects to PostgreSQL using the Docker Compose service name:

```text
postgres:5432
```

rather than `localhost`.

UTC is explicitly configured for the JVM:

```text
-Duser.timezone=UTC
```

## Production Deployment

The application is deployed at:

```text
https://booking-system-wlku.onrender.com
```

The production environment should provide:

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
JAVA_TOOL_OPTIONS
```

No database credentials should be committed to source control.

## Project Structure

```text
src/
├── main/
│   ├── java/
│   │   └── com/reservation/booking_system/
│   │       ├── controller/
│   │       ├── dto/
│   │       ├── entity/
│   │       ├── exception/
│   │       ├── repository/
│   │       └── service/
│   └── resources/
│       ├── db/
│       │   └── migration/
│       └── application.properties
│
└── test/
    └── java/
        └── com/reservation/booking_system/
            ├── ReservationConcurrencyTest.java
            └── DockerReservationBurstTest.java
```

## Design Trade-offs

### Pessimistic locking

Pessimistic locking was chosen because seat reservation is a contention-heavy operation where correctness is more important than optimistic retry complexity.

### Database-enforced uniqueness

Important invariants are protected at both the application and database levels.

Examples include:

```text
(show_id, seat_number)
(show_id, user_id)
(show_id, user_id, idempotency_key)
```

This prevents correctness from depending exclusively on application code.

### Simulated authentication

For the take-home exercise, authenticated identity is represented using:

```text
X-User-Id
```

In a production authentication environment this header would be populated from the authenticated principal/token rather than accepted directly from an untrusted client.

## Security Considerations

This implementation intentionally keeps authentication out of scope for the assignment.

Before exposing the service to real users, the following should be added:

* JWT/OAuth2 authentication
* Authorization
* Rate limiting
* TLS
* Secret management
* API gateway/WAF
* Production database credentials
* Restricted Actuator exposure

## License

This project was created as a technical take-home assignment.
