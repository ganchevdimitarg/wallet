# Wallet Service

A Spring Boot REST API for managing player wallets — deposits, withdrawals, and balance queries — built with idempotency guarantees and concurrency safety at its core.

## Features

- **Deposit / Withdraw / Balance** — simple REST endpoints for wallet operations
- **Idempotency** — dual-layer protection via Redis (fast path) + database unique constraint (belt-and-suspenders). Duplicate requests return the original response, never a double debit.
- **Concurrency-safe** — `SELECT ... FOR UPDATE` pessimistic row-level locks prevent race conditions on concurrent transactions against the same wallet.
- **Optimistic locking** — `@Version` field acts as a second line of defense against lost updates.
- **RFC 9457 Problem Details** — consistent, machine-readable error responses.
- **Database migrations** — Flyway keeps the schema reproducible across environments.
- **Input validation** — Jakarta Bean Validation on all request DTOs.

## Tech Stack

| Layer          | Technology                          |
|----------------|-------------------------------------|
| Runtime        | Java 25                             |
| Framework      | Spring Boot 4.0.6                   |
| Database       | PostgreSQL 17 (primary + read replica) |
| Cache          | Redis 7                             |
| Migrations     | Flyway 11                           |
| Resilience     | Resilience4j Circuit Breaker        |
| Infrastructure | Docker Compose                      |
| Build          | Maven                               |
| Testing        | JUnit 5, Mockito, AssertJ, Testcontainers |

## Architecture

The system follows a layered pattern: REST controllers delegate to a service layer that coordinates persistence through routing datasources and resilient circuit breakers.

### Read/Write Splitting

The service uses a **primary/replica** datasource topology to separate transactional writes from analytical reads:

```
┌──────────────┐     ┌──────────────────┐     ┌──────────────┐
│   POST       │     │                  │     │              │
│  /withdraw   │────>│  Primary (5432)  │────>│  wallets     │
│  /deposit    │     │  readWrite=true  │     │  transactions│
└──────────────┘     └──────────────────┘     └──────────────┘

┌──────────────┐     ┌──────────────────┐
│   GET        │     │                  │
│  /balance    │────>│  Replica (5433)  │
│              │     │  readOnly=true   │
└──────────────┘     └──────────────────┘
```

How it works:
- `DataSourceConfig` defines three beans: `primaryDataSource`, `replicaDataSource`, and a `routingDataSource` (marked `@Primary`).
- The routing datasource inspects the current Spring transaction context: `@Transactional(readOnly = true)` → replica, otherwise → primary.
- The replica uses HikariCP's `read-only: true` as a second guard — it will refuse write attempts at the connection pool level.

```java
// In WalletService:
@Transactional                    // → routes to primary
public WithdrawalResponse withdraw(...) { ... }

@Transactional(readOnly = true)   // → routes to replica
public BigDecimal getBalance(...) { ... }
```

### Circuit Breaker

The service wraps all database calls with Resilience4j circuit breakers. If the database becomes unreachable, the circuit opens and returns a fallback rather than cascading failures:

| Method | Fallback Behavior |
|--------|-------------------|
| `withdraw` / `deposit` | Throw `ServiceUnavailableException` (503) |
| `getBalance` | Serve **stale cache** from Redis if available; otherwise 503 |

Configuration (`application.yml`):

```yaml
resilience4j:
  circuitbreaker:
    instances:
      wallet-db:
        sliding-window-size: 10       # evaluate last 10 calls
        failure-rate-threshold: 50    # open at 50% failure rate
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
```

The balance read's fallback is prioritized — it serves stale cache rather than failing, so players can still see their balance even during a DB outage.

### Idempotency

See the [Idempotency Design](#idempotency-design) section below for the dual-layer Redis + database approach.

## Quick Start

### Prerequisites

- Java 25+
- Docker (for backing services)
- Maven 3.9+ (or use the included `mvnw` wrapper)

### 1. Clone & Configure

```bash
git clone <repo-url>
cd wallet
```

The `.env` file is pre-configured with dev defaults:

```bash
# .env (committed with dev-only values — safe for local use)
DB_PASSWORD=wallet_dev_password
```

### 2. Start Backing Services

```bash
docker compose up -d
```

This starts three containers:

| Container | Port | Purpose |
|-----------|------|---------|
| `wallet-db-primary` | 5432 | PostgreSQL — writes (withdraw, deposit) |
| `wallet-db-replica` | 5433 | PostgreSQL — reads (balance queries) |
| `wallet-redis` | 6379 | Redis — idempotency keys + balance cache |

Wait for all three to show `(healthy)`:

```bash
docker compose ps
```

Look for `(healthy)` in the STATUS column.

Flyway runs migrations automatically on app startup against the primary. The replica gets its own schema via a separate migration run (in production this would be WAL streaming).

### 3. Build & Run

```bash
export DB_PASSWORD=wallet_dev_password
./mvnw spring-boot:run
```

Docker Compose reads `.env` automatically; for `spring-boot:run` you need the variable in your shell environment.

The server starts on **http://localhost:8080**.

### 4. Verify

```bash
# Health check (Spring Boot Actuator endpoint)
curl http://localhost:8080/actuator/health

# Check balance (wallet is created on first deposit)
curl http://localhost:8080/api/v1/wallet/<player-id>/balance

# Deposit
curl -X POST http://localhost:8080/api/v1/wallet/deposit \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{"playerId": "<player-id>", "amount": 100.00}'

# Withdraw
curl -X POST http://localhost:8080/api/v1/wallet/withdraw \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{"playerId": "<player-id>", "amount": 25.00}'
```

(If `uuidgen` is unavailable, substitute any unique string for the idempotency key.)

### 5. Stop

```bash
docker compose down
```

## API Reference

Base URL: `http://localhost:8080/api/v1/wallet`

### POST `/deposit`

Credit funds to a player's wallet. Creates the wallet if it doesn't exist.

**Headers:**
- `X-Idempotency-Key` (required) — unique key to prevent duplicate processing
- `Content-Type: application/json`

**Body:**
```json
{
  "playerId": "550e8400-e29b-41d4-a716-446655440000",
  "amount": 100.00
}
```

**Response** `200 OK`:
```json
{
  "transactionId": "660e8400-e29b-41d4-a716-446655440001",
  "balanceAfter": 100.00
}
```

### POST `/withdraw`

Debit funds from a player's wallet.

**Headers:** same as deposit

**Body:** same shape as deposit

**Response** `200 OK`: same shape as deposit

**Errors:**
- `409 Conflict` — insufficient funds
- `404 Not Found` — wallet not found for this player

### GET `/{playerId}/balance`

Get the current balance for a player.

**Response** `200 OK`: `100.00`

**Errors:**
- `404 Not Found` — no wallet for this player

## Idempotency Design

The service uses a **two-layer** idempotency strategy:

1. **Redis filter layer** (`IdempotencyFilter`) — intercepts POST requests before they reach the controller. If the `X-Idempotency-Key` is found in Redis, the cached response is returned immediately (sub-millisecond). Uses `SET NX` for atomicity so only the first of two racing requests gets through.

2. **Database layer** — even if Redis is down or evicted, the `transactions.idempotency_key` column has a `UNIQUE` constraint. The key is stored in the same database transaction as the balance update — both succeed or both roll back. A duplicate key causes a constraint violation which the service catches and returns the original result.

Keys expire from Redis after 24 hours but persist in the database indefinitely.

## Project Structure

```
src/
├── main/
│   ├── java/com/ganchevdimitarg/wallet/
│   │   ├── config/          # Redis configuration
│   │   ├── controller/      # REST controllers
│   │   ├── dto/             # Request/Response DTOs
│   │   ├── exception/       # Custom exceptions + global handler
│   │   ├── filter/          # Idempotency filter + Redis store
│   │   ├── model/           # JPA entities + enums
│   │   ├── repository/      # Spring Data JPA repositories
│   │   └── service/         # Business logic
│   └── resources/
│       ├── application.yml  # Application configuration
│       └── db/migration/    # Flyway SQL migrations
└── test/
    └── java/.../wallet/     # Unit tests
```

## Testing

```bash
./mvnw test
```

## Configuration

Key settings in `application.yml`:

| Property                          | Default                | Description                              |
|-----------------------------------|------------------------|------------------------------------------|
| `spring.datasource.url`           | `jdbc:postgresql:...`  | PostgreSQL connection string             |
| `spring.datasource.password`      | `${DB_PASSWORD}`       | Resolved from environment variable       |
| `spring.data.redis.host`          | `localhost`            | Redis host                               |
| `spring.data.redis.port`          | `6379`                 | Redis port                               |
| `server.port`                     | `8080`                 | HTTP listen port                         |
| `spring.datasource.hikari.maximum-pool-size` | `20`       | HikariCP connection pool size            |
