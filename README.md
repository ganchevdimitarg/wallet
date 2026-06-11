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
│   │   ├── config/
│   │   │   ├── DataSourceConfig.java    # Primary/replica routing datasource
│   │   │   ├── FlywayConfig.java        # Explicit Flyway migration runner
│   │   │   ├── JacksonConfig.java       # ObjectMapper bean
│   │   │   └── RedisConfig.java         # StringRedisTemplate bean
│   │   ├── controller/
│   │   │   └── WalletController.java    # REST endpoints
│   │   ├── dto/                         # Request/Response DTOs
│   │   ├── exception/
│   │   │   ├── GlobalExceptionHandler.java
│   │   │   ├── InsufficientFundsException.java
│   │   │   ├── ServiceUnavailableException.java
│   │   │   └── WalletNotFoundException.java
│   │   ├── filter/
│   │   │   ├── IdempotencyFilter.java   # Intercepts POST requests
│   │   │   └── IdempotencyStore.java    # Redis-backed idempotency checks
│   │   ├── model/
│   │   │   ├── Transaction.java         # JPA entity
│   │   │   ├── TransactionType.java     # Enum: DEPOSIT, WITHDRAWAL
│   │   │   └── Wallet.java              # JPA entity with @Version
│   │   ├── repository/
│   │   │   ├── TransactionRepository.java
│   │   │   └── WalletRepository.java
│   │   ├── service/
│   │   │   └── WalletService.java       # Business logic + circuit breaker
│   │   └── WalletApplication.java       # Spring Boot entry point
│   └── resources/
│       ├── application.yml              # Application configuration
│       └── db/migration/                # Flyway SQL migrations
└── test/
    └── java/.../wallet/                 # Unit tests
```

## Testing

```bash
./mvnw test
```

### Test Architecture

- **Unit tests** (`WalletServiceTest`, `WalletControllerTest`, `GlobalExceptionHandlerTest`) — use Mockito for mocking Spring beans. No database or Redis required.
- **Integration tests** (`IdempotencyStoreTest`) — use **Testcontainers** to spin up real PostgreSQL and Redis instances. These are the tests that verify idempotency against real infrastructure.

Testcontainers config is in `src/test/resources/application.yml` — it uses random ports and lets Testcontainers manage the lifecycle.

### Running with Docker services

For manual verification against the full stack:

```bash
docker compose up -d
export DB_PASSWORD=wallet_dev_password
./mvnw spring-boot:run
```

## Environment Variables

| Variable | Default (dev) | Description |
|----------|---------------|-------------|
| `DB_PASSWORD` | `wallet_dev_password` | PostgreSQL password for `wallet_user` |

The `.env` file is committed to the repository with dev-only values — safe for local development. Docker Compose reads it automatically. The Spring Boot app reads `DB_PASSWORD` from the environment, so you must export it before running:

```bash
export DB_PASSWORD=wallet_dev_password
./mvnw spring-boot:run
```

In production, override with a real secret via your deployment platform's secret manager (never commit production passwords).

## Configuration

Key settings in `application.yml`:

### Datasource

| Property | Default | Description |
|----------|---------|-------------|
| `spring.datasource.primary.jdbc-url` | `jdbc:postgresql://localhost:5432/wallet_db` | Primary (write) database |
| `spring.datasource.replica.jdbc-url` | `jdbc:postgresql://localhost:5433/wallet_db` | Replica (read) database |
| `spring.datasource.primary.username` | `wallet_user` | Database user |
| `spring.datasource.primary.password` | `${DB_PASSWORD}` | Resolved from environment variable |
| `spring.datasource.primary.hikari.maximum-pool-size` | `20` | Connection pool for writes |
| `spring.datasource.replica.hikari.maximum-pool-size` | `10` | Connection pool for reads |
| `spring.datasource.replica.hikari.read-only` | `true` | Hikari-level guard against writes |

### Redis

| Property | Default | Description |
|----------|---------|-------------|
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `spring.data.redis.timeout` | `2000ms` | Fail fast — don't let Redis block transactions |

### Flyway

| Property | Default | Description |
|----------|---------|-------------|
| `spring.flyway.enabled` | `true` | Run migrations on startup |
| `spring.flyway.url` | `jdbc:postgresql://localhost:5432/wallet_db` | Migrations only run against primary |

### Resilience4j

| Property | Default | Description |
|----------|---------|-------------|
| `resilience4j.circuitbreaker.instances.wallet-db.sliding-window-size` | `10` | Calls evaluated for failure rate |
| `resilience4j.circuitbreaker.instances.wallet-db.failure-rate-threshold` | `50` | Percent failures before opening circuit |
| `resilience4j.circuitbreaker.instances.wallet-db.wait-duration-in-open-state` | `10s` | Time before attempting half-open |
| `resilience4j.circuitbreaker.instances.wallet-db.permitted-number-of-calls-in-half-open-state` | `3` | Test calls before closing circuit |

### Server

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | HTTP listen port |
