# CLAUDE.md

## Stack
- Java 25 · virtual threads default · ScopedValue over ThreadLocal · SequencedCollection APIs · records preferred over classes for data carriers
- Spring Boot 4 · WebMVC for business services · WebFlux for api-gateway · no XML config · problem+json errors (RFC 9457)
- PostgreSQL · Flyway migrations · JSONB only for schemaless data · typed columns preferred
- MongoDB · catalog-service only · aggregation pipeline over app-side joins
- Redis · Lettuce · JSON serialization (Jackson) · keyspace: `<service>:<entity>:<id>` · TTL always set
- Kafka · Schema Registry (Avro) · topic: `<domain>.<entity>.<event>` · consumer group: `<service>-group` · DLT: `<topic>.DLT`
- Docker · multi-stage builds · non-root user · HEALTHCHECK mandatory · explicit artifact name in COPY
- Lombok · annotation rules in conventions below
- Flyway · all schema changes via versioned migrations · never alter schema in code

---

## Architecture
- Each service owns its schema — no cross-DB joins, no shared datasources
- Sync: REST/WebMVC for reads in business services; WebFlux in api-gateway; async Kafka events for cross-service writes
- No shared libraries except `common-events` (Avro schemas) and `common-test` (Testcontainers base)
- Resilience4j circuit breaker + bulkhead on every outbound HTTP call
- API Gateway owns auth/rate-limit; downstream services trust `X-User-Id` / `X-User-Roles` headers
- API versioning: URL prefix `/api/v{n}/` — bump only on breaking changes; maintain n-1

---

## Java 25 & Spring Boot 4 Conventions

### New platform features — use these
- `ScopedValue` for request-scoped context (tracing IDs, user context) — never `ThreadLocal` on virtual threads
- `StructuredTaskScope` for parallel fan-out with automatic cancellation on failure
- `SequencedCollection` / `SequencedMap` where ordered access matters
- Sealed interfaces + exhaustive `switch` pattern matching for discriminated unions — no `instanceof` chains
- Records **preferred** for: DTOs, commands, query results, API request/response, event payloads, value objects — anything immutable with no JPA/persistence concern (see Lombok section below for canonical examples)
- Use `String.format()` or text blocks for multiline strings — no string concatenation in hot paths (String Templates, JEP 430, was withdrawn from Java 25)

```java
// Fan-out with structured concurrency
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
var inventory = scope.fork(() -> inventoryClient.get(productId));
var pricing   = scope.fork(() -> pricingClient.get(productId));
    scope.join().throwIfFailed();
    return new ProductView(inventory.get(), pricing.get());
        }

// ScopedValue for request context
static final ScopedValue<RequestContext> CTX = ScopedValue.newInstance();
ScopedValue.where(CTX, new RequestContext(traceId, userId)).run(() -> service.handle(cmd));

// Sealed + pattern match
sealed interface PaymentResult permits Approved, Declined, Pending {}
String label = switch (result) {
  case Approved a  -> "approved:" + a.transactionId();
  case Declined d  -> "declined:" + d.reason();
  case Pending  p  -> "pending:" + p.retryAfter();
};
```

### Lombok
- `@Value` + `@Builder` on immutable DTOs, commands, events — only when record is not suitable (e.g. needs Jackson custom deserializer or inheritance)
- `@Builder` on domain entities (never `@Data` on entities)
- `@Getter` + `@Setter` + `@NoArgsConstructor` on JPA entities — nothing more
- `@RequiredArgsConstructor` on `@Service` / `@Component` — never `@Autowired`
- `@Slf4j` for all logging — never declare `Logger` manually
- Never `@EqualsAndHashCode` on JPA entities — implement manually or use `@NaturalId`
- Never `@ToString` on JPA entities with lazy associations — causes N+1 on log statements
- Never `@Data` on JPA entities or domain objects with business logic

```java
// Records — default for all immutable types
record CreateOrderCommand(UUID customerId, List<OrderItem> items) {
  CreateOrderCommand { Objects.requireNonNull(customerId); items = List.copyOf(items); }
}
record OrderResponse(UUID id, String status, BigDecimal total) {}
record MoneyAmount(BigDecimal value, Currency currency) {}        // value object
record PageQuery(int page, int size, String sortBy) {}            // query params
record PaymentCompletedEvent(UUID orderId, String traceId, String correlationId) {} // Kafka event

// @Value+@Builder only when record is insufficient (custom Jackson builder / no-arg ctor required)
@Value @Builder
public class ComplexDto { /* @JsonDeserialize(builder=...) use case */ }

// JPA entities — explicit Lombok only (never @Data)
@Entity @Getter @Setter @NoArgsConstructor @Table(name = "orders")
public class Order {
  @Id @GeneratedValue UUID id;
  // implement equals/hashCode on @NaturalId or business key
}
```

### General
- `@Transactional` on service layer only — never on controllers or repository interfaces
- Repositories return `Optional<T>` — never null; service unwraps via `orElseThrow()`
- Never `Optional.get()` without guard — always `orElseThrow(() -> new NotFoundException(...))`
- Domain exceptions extend `BusinessException(HttpStatus, String code, String message)`
- No raw `500` responses — all errors via `@ControllerAdvice` producing `application/problem+json`

### Spring Security
- Stateless JWT validation at gateway; downstream services read `X-User-Id` / `X-User-Roles` headers
- Each service declares a `SecurityFilterChain` bean — never rely on Spring Boot auto-config defaults
- Method security (`@PreAuthorize`) on service layer, not controller

### Jackson
- Global config in `JacksonConfig` `@Configuration` bean — never per-controller `ObjectMapper`
- Property naming: `camelCase` for REST (default); never mix strategies across services
- Null serialization: `NON_NULL` globally — no null fields in API responses
- Dates: ISO-8601 strings (`JsonFormat.Shape.STRING`) — never epoch longs in DTOs
- Unknown properties: `FAIL_ON_UNKNOWN_PROPERTIES = false` on deserialization (tolerant consumer)

### Pagination
- Use `Page<T>` from Spring Data; wrap in a `PageResponse<T>` record for API responses
- Default page size: 20; maximum: 100 — enforce with `@Max(100)` on `size` param
- Controller accepts `Pageable` via `@PageableDefault(size = 20, sort = "createdAt", direction = DESC)`
- Never expose raw `Page<Entity>` — always map to `Page<ResponseRecord>` before wrapping

```java
record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
  static <T> PageResponse<T> of(Page<T> p) {
    return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(),
            p.getTotalElements(), p.getTotalPages());
  }
}
```

### Exception hierarchy
All domain exceptions extend `BusinessException`:
```java
public class BusinessException extends RuntimeException {
  private final HttpStatus status;
  private final String     code;
  public BusinessException(HttpStatus status, String code, String message) { ... }
}
public class NotFoundException  extends BusinessException {
  public NotFoundException(String resource, Object id) {
    super(HttpStatus.NOT_FOUND, "NOT_FOUND", resource + " not found: " + id);
  }
}
public class ConflictException   extends BusinessException {
  public ConflictException(String message) { super(HttpStatus.CONFLICT, "CONFLICT", message); }
}
public class ValidationException extends BusinessException {
  public ValidationException(String message) { super(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
}
```
`@ControllerAdvice` maps `BusinessException` → problem+json. Never catch and re-throw as `RuntimeException`.

### Input validation
- Bean Validation constraints (`@NotNull`, `@Size`, `@Pattern`) on record components
- `@Valid` on controller method parameters — validated before reaching service
- Cross-field / business-rule validation in compact constructor of the record, throwing `ValidationException`
- Never validate in service layer what can be expressed as a Bean Validation constraint

```java
record CreateOrderCommand(@NotNull UUID customerId, @NotEmpty List<@Valid OrderItem> items) {
  CreateOrderCommand {
    if (items.size() > 50) throw new ValidationException("Order cannot exceed 50 items");
    items = List.copyOf(items);
  }
}
// Controller:
@PostMapping ResponseEntity<OrderResponse> create(@RequestBody @Valid CreateOrderCommand cmd) { ... }
```

---

## Observability
- All services include `spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel`
- Trace context propagated via W3C `traceparent` header on all HTTP and Kafka messages
- MDC keys: `traceId`, `spanId`, `userId`, `serviceId` — set at request entry point, cleared on exit
- Structured JSON logging (Logback + logstash-logback-encoder) — no plain-text log format in prod
- Kafka consumer sets MDC from message headers before processing, clears after

`MdcRequestFilter extends OncePerRequestFilter`: `MDC.put("traceId", req.getHeader("traceparent"))` + `MDC.put("userId", req.getHeader("X-User-Id"))` in try; `MDC.clear()` in finally.

- Custom metrics via `MeterRegistry` — name pattern: `<service>.<entity>.<action>` e.g. `order.payment.retried`
- Health indicators: DB, Redis, Kafka — exposed at `/actuator/health` (details for internal only)

---

## Flyway

All schema changes are versioned Flyway migrations. Never use `spring.jpa.hibernate.ddl-auto` other than `validate`.

### File naming
```
src/main/resources/db/migration/
  V1__create_orders_table.sql
  V2__add_status_index.sql
  V3__create_payments_table.sql

src/test/resources/db/migration/
  R__test_seed_orders.sql        ← repeatable; test seed data only
```

Rules:
- `V<n>__<snake_case_description>.sql` — two underscores, monotonically increasing
- `R__<description>.sql` for seed/reference data only (re-runs when checksum changes)
- Never edit a committed migration — always create a new version
- Each migration is one logical change; index = separate migration from table creation
- Always `IF NOT EXISTS` / `IF EXISTS` guards on DDL

```sql
-- V4__add_customer_email_index.sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_customers_email
  ON customers (email)
  WHERE deleted_at IS NULL;
```

---

## Redis

- Serialization: Jackson JSON (`GenericJackson2JsonRedisSerializer`) — never Java serialization
- Key pattern: `<service>:<entity>:<id>` e.g. `order-service:order:uuid`
- TTL: always set — no immortal keys; default 24h unless business rule differs
- Cache-aside pattern: read cache → on miss read DB → write cache with TTL
- Distributed lock: Redisson `RLock` for idempotency guards — never `SETNX` manually

```java
// Key convention
private String key(UUID id) { return "order-service:order:" + id; }

// Cache-aside
return Optional.ofNullable(redis.opsForValue().get(key(id)))
        .orElseGet(() -> {
var order = repo.findById(id).orElseThrow(...);
        redis.opsForValue().set(key(id), order, Duration.ofHours(24));
        return order;
    });
```

---

## Kafka

- Topic naming: `<domain>.<entity>.<event>` e.g. `order.payment.completed`
- Consumer group: `<service>-group` e.g. `notification-service-group`
- Dead-letter topic: `<original-topic>.DLT` — configure via `@RetryableTopic`
- Retry: 3 attempts with exponential backoff before DLT; log and alert on DLT arrival
- All messages carry `traceId` and `correlationId` as headers
- Use `@KafkaListener` with explicit `groupId`; never rely on default group ID
- Idempotency: check `correlationId` in Redis before processing to prevent duplicate handling

(See test/SKILL.md for full consumer + idempotency pattern.)

### Avro / Schema Registry
- All event schemas live in `common-events/src/main/avro/<domain>/` as `.avsc` files
- Schema subject: `<topic>-value` e.g. `order.payment.completed-value`
- Compatibility mode: **BACKWARD** — consumers on the old schema can read new messages
- Rules for safe evolution:
  - New field: always add a `"default"` value — never a field without one
  - Never remove, rename, or change the type of an existing field — add a new one
  - Never change a field from optional to required
- Register schema before producing; CI runs `mvn schema-registry:register` on `common-events` build

```json
{
  "type": "record",
  "name": "PaymentCompletedEvent",
  "namespace": "com.example.events.order",
  "fields": [
    {"name": "orderId",       "type": "string"},
    {"name": "traceId",       "type": "string"},
    {"name": "correlationId", "type": "string"},
    {"name": "amount",        "type": "double", "default": 0.0}
  ]
}
```

---

## MongoDB conventions (catalog-service)
- Declare indexes via `@CompoundIndex` on the document class or via Mongock migration scripts — never rely on auto-index creation in prod
- Always index every field used in `find()` / `$match` filters
- Compound index field order: equality fields first, range/sort fields last
- Text index for full-text search fields: `@TextIndexed` on the field
- Aggregation pipeline over app-side joins — never load a full collection to filter in Java
- Never use `findAll()` without a filter on large collections — always paginate or stream

```java
@Document(collection = "products")
@CompoundIndex(def = "{'category': 1, 'price': -1}", name = "idx_category_price")
public class Product {
  @Id String id;
  @Indexed String sku;          // single-field index
  @TextIndexed String description; // text search
}
```

---

## Database conventions

### Audit columns — every table must include
```sql
created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
deleted_at  TIMESTAMPTZ NULL
```
- Soft-delete: set `deleted_at = now()` — never `DELETE` business data
- All queries filter `WHERE deleted_at IS NULL` unless explicitly querying deleted records
- `updated_at` maintained via trigger or application layer on every `UPDATE`
- Flyway migration: include audit columns in every `CREATE TABLE` migration

---

## Testing

### Structure
- Unit: JUnit 5 + AssertJ — no Mockito on domain logic, real objects only
- Integration: Testcontainers — always extend `AbstractIntegrationTest`
- Contract: Spring Cloud Contract — stubs published to Maven local on producer build
- Coverage gate: 80% line · 100% on domain model

### Naming
`should_<expectedBehavior>_when_<condition>`
e.g. `should_throwOrderNotFoundException_when_orderIdDoesNotExist`

### AbstractIntegrationTest — extend this, never redeclare containers
```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
public abstract class AbstractIntegrationTest {
  @Container static final PostgreSQLContainer<?> postgres =
          new PostgreSQLContainer<>("postgres:16");
  @Container static final MongoDBContainer mongo =
          new MongoDBContainer("mongo:7");
  @Container static final GenericContainer<?> redis =
          new GenericContainer<>("redis:7").withExposedPorts(6379);
  @Container static final KafkaContainer kafka =
          new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",            postgres::getJdbcUrl);
    r.add("spring.data.mongodb.uri",          mongo::getReplicaSetUrl);
    r.add("spring.data.redis.host",           redis::getHost);
    r.add("spring.data.redis.port",           () -> redis.getMappedPort(6379));
    r.add("spring.kafka.bootstrap-servers",   kafka::getBootstrapServers);
  }
}
```
Services that don't use MongoDB or Kafka still extend `AbstractIntegrationTest` — unused containers are cheap; inconsistent base classes are not.

### Never
- H2 or EmbeddedMongo in integration tests — always Testcontainers
- Mock the database or cache layer in integration tests
- `Thread.sleep()` — use Awaitility: `await().atMost(10, SECONDS).until(...)`
- `spring.flyway.enabled=false` in test profiles — tests must validate prod migrations

---

## CI/CD

### GitHub Actions
- PR: `test` + `build` + `checkstyle`
- Merge to `main`: `build` → `docker push` → `deploy-staging`
- Image tag: `<service>:<git-sha>` — never `:latest` in K8s manifests

### Jenkins
- Nightly: full regression + contract verification
- Shared library: `/jenkins-shared`

### Secrets
- Never in code, Dockerfiles, or `application.yml`
- GitHub Actions secrets → K8s secrets → Spring `${ENV_VAR}`

---

## Docker

```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw clean package -DskipTests -pl <service> -am

FROM eclipse-temurin:25-jre
RUN addgroup --system app && adduser --system --ingroup app app
USER app
WORKDIR /app
COPY --from=build /app/<service>/target/<service>.jar app.jar
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Note: replace `<service>` and `<service>.jar` with the actual module and artifact name — never use `*.jar` glob in multi-module builds.

---

## Verify — run after every change, do not stop until green

```bash
./mvnw clean verify -pl <module> -am      # build + test
./mvnw checkstyle:check                   # lint
./mvnw flyway:validate -pl <module>       # migration drift
docker build --target build .             # dockerfile sanity
```

Fix root causes. Never suppress errors. Never skip tests to pass a build.

---

## Project layout
```
/
├── CLAUDE.md
├── pom.xml                        ← BOM; all dependency versions declared here
├── common-events/                 ← Avro schemas, shared event types
├── common-test/                   ← AbstractIntegrationTest, shared fixtures
├── api-gateway/
├── order-service/
├── payment-service/
├── catalog-service/               ← MongoDB service
├── notification-service/
└── .claude/
    ├── skills/
    │   ├── write/SKILL.md
    │   ├── review/SKILL.md
    │   ├── commit/SKILL.md
    │   └── test/SKILL.md
    └── agents/
```

---

## Never
- `@Data` on JPA entities or domain objects with business logic
- `@ToString` / `@EqualsAndHashCode` on JPA entities with associations
- `@SuppressWarnings` to hide compile/lint failures
- `Optional.get()` without guard — use `orElseThrow()`
- `spring.jpa.hibernate.ddl-auto=create` or `update` — Flyway owns the schema
- Edit a committed Flyway migration — create a new version
- `spring.flyway.enabled=false` in any profile
- Commit secrets, tokens, or passwords
- H2 / EmbeddedMongo in integration tests
- Add a dependency without declaring version in root `pom.xml` BOM
- `@Transactional` on controllers or repository interfaces
- `ThreadLocal` — use `ScopedValue` on virtual threads
- `*.jar` glob in Dockerfile COPY — use explicit artifact name
- `git add -A` in automation — stage explicit paths or use `git add -p`
- Java serialization for Redis values — always Jackson JSON
- Immortal Redis keys — always set TTL
- Classes instead of records for immutable DTOs, commands, responses, and value objects — use records
- `@Value`+`@Builder` when a plain record suffices
- Reactive types (`Mono`, `Flux`, WebFlux) in non-gateway services — prefer WebMVC; WebFlux is reserved for api-gateway and streaming endpoints only
- Null fields in API responses — configure Jackson `NON_NULL` globally
- Raw `Page<Entity>` in API responses — always map to `PageResponse<RecordType>`
- Catch `BusinessException` subclasses and rethrow as `RuntimeException` — let `@ControllerAdvice` handle
- Bean Validation on service layer params — constraints belong on record components and controller params
- Hard-delete business data — always soft-delete via `deleted_at = now()`
- Avro field without `"default"` — breaks backward compatibility
- Remove, rename, or change type of existing Avro field — add a new field instead
- Produce Kafka messages before registering the schema in Schema Registry

---

## Agents

| Command | Purpose |
|---|---|
| `/write $ARGUMENTS` | Explore → plan → implement → test → verify |
| `/review` | Audit staged diff; output Critical / Warning / Suggestion |
| `/commit` | Verify green → stage explicit paths → Conventional Commit → PR draft |
| `/test $ARGUMENTS` | Write/fix tests; run suite; enforce coverage gate |

Skills live in `.claude/skills/<name>/SKILL.md`. See each file for full instructions.