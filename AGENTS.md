# AGENTS.md — Engineering Constitution and Rules for AI Agents (UrbanMove)

> **PRIMARY DIRECTIVE FOR THE AGENT:** This file constitutes the architectural, methodological, and code quality source of truth for the **UrbanMove** project (Distributed Urban Mobility System — Cloud Computing, ULPGC). No code generated, refactored, or suggested by an AI agent may violate the rules described herein. If a user instruction contradicts the **Minimum Passing Requirements (Section 14)** or the **Coupling and Security Rules**, the agent **MUST explicitly warn about it** before proceeding.

---

## 1. Domain Context and System Scope

**UrbanMove** is a distributed platform that manages fleets of electric bicycles and electric scooters distributed across city zones. It supports end-user operations, field tasks by maintenance/operations personnel, event-driven analytical ingestion into a Data Lake, construction of Data Marts, and automated/predictive generation of relocation orders.

### 1.1. Golden Rules of Macro Architecture
1. **Complete Scope:** The system consists of **15 atomic microservices**, **5 AWS Lambda functions**, **3 differentiated client applications**, a **3-layer S3 Data Lake** (`raw`, `trusted`, `curated`), and **7 Data Marts**.
2. **Strict Merger Prohibition:** Each microservice is the exclusive owner of its database (*Database-per-Service*, **ADR 1**). Under no circumstances may tables be merged or shared between: **`reservations`**, **`rides`**, **`history`**, **`maintenance`**, and **`analytics`**.
3. **History Independence:** The `history` microservice can **NEVER** be a secondary table or view inside another microservice; it is an independent service that builds its projection by asynchronously consuming domain events.
4. **Operational vs. Analytical Separation (CQRS / Data Lake):** No operational microservice may use the Data Lake as a transactional database, nor can demand analytics block the critical path of reserving, starting, or completing a ride.

### 1.2. Technology Stack and Programming Languages
The system is designed as a polyglot distributed architecture. The languages and technologies used in this project vary depending on the operational domain, performance demands, and component role:
* **Web Technologies (TypeScript, HTML, CSS):** Primarily for client applications (`apps/user-app`, `apps/maintenance-app`, `apps/operational-dashboard`), as well as TypeScript / Node.js for microservices and serverless Lambdas where appropriate.
* **Java:** Used for robust, enterprise-grade backend microservices, data processing pipelines, and Lambdas, leveraging strong object-oriented domain modeling, concurrency, and Hexagonal Architecture.
* **Rust:** Used for performance-critical services, high-throughput ingestion/telemetry processing, simulators, or specialized engines requiring low latency, zero-cost abstractions, high concurrency, and strict memory safety.

> **Polyglot Consistency Rule:** Regardless of the chosen language (**TypeScript**, **Java**, or **Rust**), every microservice, tool, and Lambda must strictly respect the Hexagonal Architecture (Section 4), contract-first schemas (Section 3), structured logging and observability (Section 6), and test coverage requirements.

---

## 2. Component Map and Ownership by Blocks (Teams A, B, C, D)

The repository is structured as a **monorepo**. Every agent must respect the boundaries of each block and data ownership:

```text
urbanmove/
├── AGENTS.md
├── docker-compose.yml          # Complete local environment with LocalStack
├── docs/
│   ├── adr/                    # ADR-001 (DB per service), ADR-002 (SNS+SQS Bus), etc.
│   ├── architecture/           # C4 Diagrams (Context, Containers, Components, Sequences)
│   ├── contracts/
│   │   ├── openapi/            # REST Contracts (OpenAPI 3.1) - Cross-cutting lead: A
│   │   └── asyncapi/           # Event Contracts (AsyncAPI / JSON Schema) - Lead: C
│   └── datamarts/              # Definition of the 7 data marts and queries
├── apps/
│   ├── user-app/               # End-user interface (Block A)
│   ├── maintenance-app/        # Maintenance and operations interface (Block B)
│   └── operational-dashboard/  # Operational and system health dashboard (Block D/C)
├── services/                   # 15 Atomic Microservices
│   ├── identity/               # [A] Auth, sessions, JWT issuance and roles (user, maintenance, admin)
│   ├── profiles/               # [A] User data, preferences, soft delete (Does NOT issue tokens)
│   ├── reservations/           # [A] Creation, confirmation, cancellation, expiration, anti-double booking
│   ├── rides/                  # [A] Start/finish ride, duration, origin/destination, preliminary cost
│   ├── incidents/              # [A] Breakdowns, vandalism, lock issues, missing vehicles
│   ├── inventory/              # [B] Assets (bike/scooter), ID, type, purchase, model, admin status
│   ├── availability/           # [B] Which resources can be reserved at each moment and zone
│   ├── zones/                  # [B] Allowed zones, parking restrictions, geo metadata
│   ├── telemetry/              # [B] Battery level, location, km, and usages since last service
│   ├── acquisitions/           # [B] Purchase/registration of new vehicles and event emission
│   ├── maintenance/            # [B] Orders for withdrawal, recharge, repair, inspection, and return to service
│   ├── relocation/             # [B] Resource redistribution orders between zones
│   ├── history/                # [C] Independent historical projection of reservations, rides, etc.
│   ├── analytics/              # [C] Aggregated queries over the 7 data marts for the dashboard
│   └── notifications/          # [D] Sending/simulation of alerts to users and operations
├── lambdas/                    # 5 Serverless Functions connected to real events
│   ├── reservation-expiration/ # [A] EventBridge cron/timeout -> expires unconfirmed reservations
│   ├── maintenance-detection/  # [B] Detects low battery, breakdown, or excess usage -> creates order
│   ├── analytics-ingestion/    # [C] Ingestion of events into S3 (Raw -> Trusted -> Curated)
│   ├── datamarts-builder/      # [C] Incremental/window updates of the 7 Data Marts
│   └── relocation-engine/      # [C] Evaluates demand Data Mart / predictive model -> proposes relocation
├── tools/
│   ├── telemetry-simulator/    # [B] Telemetry and anomaly simulator
│   ├── synthetic-generator/    # [C] Synthetic data generator with temporal/spatial patterns
│   └── locust/                 # [A] Load testing on reservations and rides
└── infra/
    └── terraform/              # [D] IaC modules compatible with LocalStack and AWS Academy (LabRole)
```

---

## 3. Contract-Driven Development (Contract-First)

**PROHIBITION:** No agent may create or modify an HTTP endpoint or publish/consume an event without first verifying or updating its contract in `docs/contracts/`.

### 3.1. Synchronous Contracts (OpenAPI 3.1)
* Located in `docs/contracts/openapi/<service-name>.yaml`.
* All endpoints must strictly validate input and output DTOs against the schema.
* Authentication and authorization via **JWT** with explicit roles: `user`, `maintenance`, `admin`.
* Every HTTP error response must follow the **RFC 7807 (Problem Details for HTTP APIs)** standard and always include the `correlationId`.

### 3.2. Asynchronous Contracts (AsyncAPI and Mandatory Envelope)
* The event bus uses **SNS + SQS with Dead-Letter Queues (DLQ)** and **EventBridge** (**ADR 2**).
* **EVERY event** emitted by any microservice or Lambda **MUST** be wrapped in this immutable JSON schema:

```json
{
  "eventId": "uuid-v4-unique",
  "timestamp": "2026-11-15T10:30:00.000Z",
  "source": "urbanmove.reservations",
  "eventType": "VehicleReserved",
  "schemaVersion": "1.0.0",
  "correlationId": "uuid-v4-flow-traceability",
  "payload": {}
}
```

### 3.3. Closed Catalog of the 13 Domain Events
No event names should be invented outside of the official convention defined in `docs/contracts/asyncapi/`:
1. `UserRegistered`: User registration and profile creation.
2. `VehiclePurchased`: Addition of a new bicycle or scooter (`acquisitions` $\rightarrow$ `inventory`, `analytics`).
3. `VehicleReserved`: Reservation created on an available resource.
4. `ReservationCancelled`: Reservation cancelled by the user.
5. `ReservationExpired`: Reservation automatically released due to timeout (Lambda `reservation-expiration`).
6. `RideStarted`: Start of resource usage.
7. `RideFinished`: Completion of the ride and release or state change of the resource.
8. `VehicleBatteryLow`: Low battery detection (`telemetry` $\rightarrow$ Lambda `maintenance-detection`).
9. `VehicleWithdrawnForMaintenance`: Resource withdrawn from public streets for maintenance.
10. `VehicleReturnedToService`: Resource becomes available again after maintenance.
11. `DemandHotspotDetected`: Zone with demand exceeding availability.
12. `RelocationOrderCreated`: Order created to move resources between zones.
13. `RelocationOrderCompleted`: Order completed by the maintenance team.

### 3.4. Official Vehicle State Machine
Every mobility resource has an explicit lifecycle of **9 states**. It is forbidden to use arbitrary strings or booleans (`is_active`, `is_broken`) to represent the operational state:
* `AVAILABLE`: Available for reservation or public use.
* `RESERVED`: Temporarily reserved by a user.
* `IN_USE`: In active use during a ride.
* `LOW_BATTERY`: Available only for maintenance or pending withdrawal.
* `WITHDRAWN`: Withdrawn from public streets.
* `CHARGING`: In recharging process.
* `REPAIRING`: Under technical repair.
* `READY_FOR_SERVICE`: Ready to return to the streets after intervention.
* `RETIRED`: Permanently decommissioned from inventory.

---

## 4. Internal Code Architecture and Rigorous Design Principles

Each microservice and Lambda function must be structured following **Hexagonal Architecture (Ports & Adapters)** / **Clean Architecture**, ensuring that the domain has no knowledge whatsoever of HTTP, databases, or the AWS SDK, regardless of whether it is implemented in **Web technologies / TypeScript**, **Java**, or **Rust**.

```text
services/<service-name>/
├── src/
│   ├── domain/                 # Entities, Value Objects, States, Domain Events, Ports (Interfaces)
│   ├── application/            # Use Cases / Command & Query Handlers, Application DTOs
│   ├── infrastructure/         # Secondary adapters: DB Repositories, SNS/SQS/S3 Clients, Logging
│   └── interfaces/             # Primary adapters: REST Controllers, SQS Consumers, Middlewares
└── tests/
    ├── unit/                   # Domain and application tests (no network or Docker, pure mocks/fakes)
    ├── contract/               # Validation against OpenAPI / AsyncAPI schemas
    └── integration/            # Tests against local containers / LocalStack
```

### 4.1. Low Coupling and High Cohesion
* **Zero cross-service imports:** A service in `services/rides` can **NEVER** import code from `services/reservations`. If they share purely technical utilities (e.g., `correlationId` middleware, JWT validator, or base logging client), they must be injected from the common template without ever including business logic or business models.
* **Temporal and Spatial Decoupling:** Operations that trigger side effects in other domains (e.g., updating history, notifying the user, ingesting into the Data Lake, evaluating maintenance) **MUST** be performed by publishing domain events to SNS/SQS, never by chaining blocking synchronous HTTP calls.

### 4.2. SOLID Principles (Strict Application)
1. **S — Single Responsibility Principle (SRP):**
   * A class or module must have only one reason to change.
   * *Forbidden:* HTTP controllers that build SQL queries, validate complex business rules, and publish directly to `boto3` / AWS SDK.
   * *Mandatory:* The controller only deserializes the HTTP request, invokes the Use Case (`UseCase.execute(command)`), and formats the HTTP response.
2. **O — Open/Closed Principle (OCP):**
   * Code must be open for extension but closed for modification.
   * Vehicle state transitions, anomaly detection rules, and relocation algorithms must be extensible by adding new implementations of an interface without modifying the central orchestrator.
3. **L — Liskov Substitution Principle (LSP):**
   * Every implementation of a port (e.g., `InMemoryReservationRepository`, `PostgresReservationRepository`, or `DynamoReservationRepository`; `LocalStackEventBus` vs `AwsSnsEventBus`) must fulfill exactly the same behavioral contract, throwing equivalent domain exceptions upon invariant violations.
4. **I — Interface Segregation Principle (ISP):**
   * *Forbidden:* Creating monolithic interfaces like `IVehicleManager` with 20 methods.
   * *Mandatory:* Segregate ports according to client needs: e.g., `VehicleAvailabilityReader`, `VehicleStateWriter`, `DomainEventPublisher`.
5. **D — Dependency Inversion Principle (DIP):**
   * The `domain` and `application` layers define the abstractions (Interfaces/Protocols/Ports/Traits).
   * The `infrastructure` layer implements those abstractions.
   * Dependencies are injected explicitly via constructor (Constructor Dependency Injection). Instantiating database or AWS clients inside use cases is strictly forbidden.

### 4.3. Law of Demeter (Principle of Least Knowledge) and *Tell, Don't Ask*
A method $M$ of an object $O$ may only invoke methods of:
1. The object $O$ itself.
2. The parameters passed to $M$.
3. Any object created or instantiated within $M$.
4. Direct properties/components of $O$.

* **FORBIDDEN (Train Wrecks / Law of Demeter Violation + Feature Envy):**
  ```typescript
  // ❌ BAD: The service couples its logic to the internal structure of 3 nested objects
  if (vehicle.getTelemetry().getBattery().getPercentage() < 15 && vehicle.getState().getCode() === 'AVAILABLE') {
      vehicle.getState().setCode('LOW_BATTERY');
  }
  ```
* **MANDATORY (Tell, Don't Ask + Invariant Encapsulation):**
  ```typescript
  // ✅ GOOD: The domain entity protects its invariants and executes the transition
  vehicle.recordTelemetrySnapshot(telemetrySnapshot); // Internally transitions to LOW_BATTERY and records VehicleBatteryLow
  ```

---

## 5. Normative Catalog of Design Patterns

When an architectural or design problem requires a pattern, the agent **MUST** apply the following implementation guidelines and avoid *over-engineering* outside these cases:

### 5.1. Singleton Pattern (Restricted and Cloud-Safe Usage)
* **When to use it:** Exclusively for heavy, expensive-to-initialize, and thread-safe infrastructure resources (database connection pools, AWS SDK clients for SNS/SQS/S3, immutable environment variable configuration, and the structured logger).
* **In AWS Lambda:** The AWS SDK client and DB connection must be initialized in the global execution scope (outside the `handler`) as a Lazy Singleton to reuse connections in warm start executions.
* **ABSOLUTE PROHIBITION:** **NEVER** use a Singleton to store mutable business state in memory (e.g., local cache of available vehicles or user sessions), as it breaks the **horizontal scalability** of ECS containers and parallel Lambda concurrency.
* **Preferred implementation:** Manage the Singleton lifecycle through the Dependency Injection container at the entry point (`main` / `bootstrap`), not through hidden global variables that prevent unit testing.

### 5.2. Observer / Listener Pattern (Domain Events and Asynchronous Bus)
Applied at two clearly differentiated levels:
1. **Intra-Service Level (In-Process Domain Events):**
   * Domain Entities/Aggregates accumulate events in an internal list (`this.recordDomainEvent(new VehicleReservedEvent(...))`) when their state changes.
   * The Use Case, after committing the database transaction, dispatches these events to a `DomainEventDispatcher` (Subject/Observable) where decoupled `DomainEventListener`s (Observers) are registered (e.g., `PublishToSnsListener`, `AuditLogListener`, `MetricsCollectorListener`).
2. **Inter-Service Level (Distributed Pub/Sub with SNS + SQS):**
   * The producer publishes the event once to the domain SNS topic.
   * Multiple SQS queues (independent subscribers/observers: `history-queue`, `analytics-ingestion-queue`, `notifications-queue`) receive a fan-out copy without the producer knowing who is listening.

### 5.3. State Pattern (Vehicle State Machine)
* **Where it is mandatory:** In vehicle lifecycle management (`inventory` / `availability` / `maintenance`).
* **Rule:** Forbidden to use gigantic `switch/case` or `if/else` blocks scattered across multiple services to validate whether a vehicle can transition from `RESERVED` to `IN_USE` or from `LOW_BATTERY` to `WITHDRAWN`.
* **Implementation:** Model an explicit transition matrix or state classes (`VehicleState`) where each state defines which events/actions it accepts (`reserve()`, `startRide()`, `finishRide()`, `reportLowBattery()`, `withdrawForMaintenance()`, `startCharging()`, `startRepair()`, `markReadyForService()`, `returnToService()`, `retire()`) and throws a typed `InvalidStateTransitionError` exception upon any illegal transition.

### 5.4. Strategy Pattern (Interchangeable Algorithms)
* **Where to apply it:**
  1. **Relocation Engine (`relocation-engine`):** Define the `RelocationDecisionStrategy` interface. Implement at least two configuration-interchangeable strategies:
     * `ThresholdRuleRelocationStrategy`: Base heuristic rule (e.g., if a zone has $< 3$ available resources and $> 10$ reservation attempts or started rides in the last hour, it generates an order from a surplus zone).
     * `PredictiveModelRelocationStrategy`: Advanced strategy that queries the predictive demand model by zone and time slot (Extension Section 15).
  2. **Fare Calculation (`rides`):** `RidePricingStrategy` to calculate the preliminary cost according to vehicle type (bicycle vs scooter) and duration.

### 5.5. Idempotent Consumer Pattern and Double Booking Prevention
* **In `reservations` (Concurrency):** To prevent reserving the same resource twice, optimistic locking must be implemented (`version` column on resource/availability: `UPDATE ... WHERE vehicle_id = :id AND status = 'AVAILABLE' AND version = :v`) or atomic transactional locking, accompanied by an idempotency key (`Idempotency-Key` in HTTP header).
* **In SQS Consumers and Lambdas (`history`, `maintenance`, `datamarts-builder`):** AWS SQS guarantees *at-least-once* delivery (duplicate events may arrive). Every consumer **MUST** implement the **Idempotent Consumer** pattern:
  * Before applying the business effect, check in a control table/store (`processed_events`) whether the `eventId` has already been processed.
  * If it already exists, record an `INFO` level log (`"Duplicate event ignored"`) with the `correlationId` and acknowledge (ACK) the message without throwing an error or creating duplicate records.

---

## 6. Fault Tolerance, Scalability, and Observability

All generated code must comply with the requirements of sections 7 and 10 of the assignment:

1. **End-to-End Traceability (`correlationId`):**
   * Every incoming HTTP request reads the `X-Correlation-Id` header (or generates a UUID v4 if not provided).
   * This `correlationId` is propagated in **all** structured logs (JSON), outgoing HTTP calls, and the `correlationId` attribute of the envelope for all events published to SNS/SQS/EventBridge.
2. **Structured Log Format (JSON):**
   ```json
   {
     "timestamp": "2026-11-20T14:22:10.123Z",
     "level": "INFO",
     "service": "reservations-service",
     "correlationId": "c9a8b7d6-...",
     "message": "Vehicle reserved successfully",
     "context": { "reservationId": "res-123", "vehicleId": "veh-456", "zoneId": "zone-centro" }
   }
   ```
3. **Mandatory Health Checks:**
   * Every HTTP microservice must expose `GET /health` returning `200 OK` with the service status and its connectivity so Docker Compose and AWS ECS can automatically restart degraded containers.
4. **Communication Resilience:**
   * Every network call must have an **explicit timeout** (e.g., $\le 3\text{ s}$). Making HTTP calls without a timeout is forbidden.
   * Implement **retries with Exponential Backoff + Jitter** for transient failures.
   * **Dead-Letter Queues (DLQ):** Every SQS queue and asynchronous Lambda must have an associated DLQ configured in Terraform (`maxReceiveCount = 3`) and a documented mechanism to reprocess failed messages (Failure Scenario 2).
   * **Graceful Degradation:** A failure in non-critical components (`notifications`, `history`, `analytics`) must **NEVER** prevent a user from reserving a vehicle, starting, or finishing a ride.

---

## 7. Analytical Layer: Data Lake and the 7 Data Marts

Block C (`history`, `analytics`, `analytics-ingestion`, `datamarts-builder`, `relocation-engine`) must strictly respect this S3 structure (both in LocalStack and in AWS):

### 7.1. S3 Data Lake Partitioning
* **Raw Layer:** Immutable events exactly as they arrive from the bus.
  * Path: `s3://urbanmove-datalake/raw/year=YYYY/month=MM/day=DD/source=<service>/`
* **Trusted Layer:** Events validated against the AsyncAPI schema, deduplicated, and normalized.
  * Path: `s3://urbanmove-datalake/trusted/event_type=<EventType>/year=YYYY/month=MM/day=DD/`
* **Curated Layer:** Enriched data ready for analytical aggregation.
  * Path: `s3://urbanmove-datalake/curated/domain=<domain>/`

### 7.2. The 7 Mandatory Data Marts
The `datamarts-builder` Lambda must calculate, incrementally or through time windows, the following 7 Data Marts consumed by the `analytics` service and the `operational-dashboard`:
1. **`dm_users` (Users):** Active users per period, ranking of users with the most rides or cancellations.
2. **`dm_reservations` (Reservations):** Expired reservation rate, zones where resources are reserved but then not used.
3. **`dm_rides` (Rides):** Most frequent origin-destination matrix, average duration, and average cost of rides per time slot.
4. **`dm_fleet` (Fleet):** Near real-time distribution of `AVAILABLE`, `RESERVED`, `IN_USE`, `CHARGING`, `REPAIRING`, `WITHDRAWN`, and `RETIRED` resources by zone and vehicle type.
5. **`dm_maintenance` (Maintenance):** Resources pending recharge/repair, mean time out of service (*MTTR*).
6. **`dm_operations` (Operations):** Relocation orders created vs completed, and average execution delay.
7. **`dm_demand` (Demand):** Resource deficit or surplus by zone, time slot, and vehicle type (directly feeds the `relocation-engine` Lambda).

---

## 8. Critical Infrastructure Constraints (LocalStack and AWS Academy)

Infrastructure code in `infra/terraform/` must work reproducibly both against **LocalStack** and in **AWS Academy Learner Lab**. The agent **MUST** comply with these rules when writing Terraform or deployment scripts:

1. **Exclusive use of `LabRole` in AWS Academy:**
   * AWS Academy **prohibits creating new IAM roles** (`aws_iam_role`). Every ECS resource (`execution_role_arn`, `task_role_arn`) and every AWS Lambda function (`role`) **MUST** reference the ARN of the pre-existing `LabRole` obtained via a `data "aws_iam_role" "lab_role" { name = "LabRole" }`.
2. **Prohibition of NAT Gateways (Budget Protection):**
   * **NEVER** provision an `aws_nat_gateway` or associated `aws_eip`, as they consume the AWS Academy budget on an hourly basis. ECS Fargate tasks must be deployed in public subnets with `assign_public_ip = true` protected by restrictive Security Groups (Principle of Least Privilege in networking).
3. **Minimal Sizing in Fargate and Lambda:**
   * Configure ECS Fargate tasks with minimal required consumption (`cpu = "256"`, `memory = "512"`) and Lambdas with `memory_size = 128` or `256` MB and bounded `timeout`.
4. **Ephemerality and Cleanup (`terraform destroy`):**
   * All S3 buckets in Terraform must include `force_destroy = true` (in academic/demo environments) so that `terraform destroy` at the end of each session destroys 100% of the resources without blocking.
5. **Zero Secrets in Repository:**
   * **NEVER** write AWS keys (`aws_access_key_id`, `aws_secret_access_key`, `aws_session_token`), JWT secrets, or passwords into source code, Terraform files, Dockerfiles, or CI/CD logs. Always use injected environment variables or GitHub Actions secrets.

---

## 9. Agent Action Protocol and Definition of Done (DoD)

Whenever a team developer (A, B, C, or D) asks the agent to implement or modify functionality, the agent must follow this verification sequence:

1. **Contract Verification:** Does the contract in `docs/contracts/openapi/` or `docs/contracts/asyncapi/` exist or is it up to date? If the change alters an existing contract, warn that it requires cross-team approval.
2. **Isolation and Architecture:** Verify that changes respect the layers `domain` $\leftarrow$ `application` $\leftarrow$ `infrastructure`/`interfaces`, the Law of Demeter, SOLID, and established patterns (State, Strategy, Observer, Idempotent Consumer).
3. **Observability and Idempotency:** Ensure that the `/health` endpoint is operational, logs include `correlationId`, and any event handler is idempotent against repeated `eventId`s.
4. **Automated Testing:** Always generate unit tests for domain logic/use cases (including edge cases and invalid state transitions) and integration/contract tests.
5. **Living Documentation (`docs/`):** Documentation is not left for the end. Update the corresponding section in `docs/` (Mermaid/C4 diagrams, sequences, data ownership tables, or ADRs) alongside the code.
