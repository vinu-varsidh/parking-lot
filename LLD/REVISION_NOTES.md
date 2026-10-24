# Parking Lot LLD - Master Revision Notes

Use this note to revise the design, not merely the code. For every component, remember four things:

1. What state does it own?
2. Which invariant does it protect?
3. Why is that responsibility not in another class?
4. What requirement would force the design to change?

## 1. The 60-second mental model

`ParkingLotService` is the use-case orchestrator. It does not decide which spot is best, how much parking costs, or how a payment is processed. Those policies are injected through `SpotAssignmentStrategy`, `PricingStrategy`, and `PaymentService`.

The physical hierarchy is:

```text
ParkingLot -> ParkingFloor -> ParkingSpot -> current Vehicle
```

The business session is represented by `ParkingTicket`:

```text
ACTIVE -> PAYMENT_PENDING -> CLOSED
   ^              |
   +--------------+ payment failure
```

The central consistency rule is:

> An active ticket, the active-plate index, and an occupied spot must describe the same parking session.

## 2. Core design decisions

| Decision | Why it was chosen | Tradeoff |
|----------|-------------------|----------|
| Service orchestrates; domain objects protect local state | Keeps workflow separate from spot and ticket invariants. | Correctness across several objects still depends on service ordering. |
| Strategy interfaces for assignment and pricing | These rules are expected to vary independently. | Adds interfaces even though each currently has one implementation. |
| Payment behind a port | External gateway details stay outside the domain. | The minimal result cannot explain rich failure reasons. |
| Explicit ticket state machine | Prevents close-before-payment and double exit. | More states require deliberate transition design. |
| Two ticket indexes | Ticket ID lookup and duplicate-plate checks are both O(1). | The same ticket is referenced in two maps that must stay consistent. |
| `BigDecimal` for fees | Avoids binary floating-point errors in money. | More verbose and slower than integer arithmetic. |
| Coarse service lock plus local spot/ticket locks | Easy-to-audit atomic workflows with protected local invariants. | Payment happens while holding the service lock, limiting throughput. |
| In-memory collections | Keeps the LLD focused on object collaboration. | State is lost on restart and closed-ticket history grows without bound. |

## 3. Class-by-class revision

### Enums

#### `VehicleType`

- **Owns:** the closed set `MOTORCYCLE`, `CAR`, and `TRUCK`.
- **Used by:** vehicle construction, spot compatibility, and pricing lookup.
- **Choice:** an enum gives exhaustive switch handling and avoids stringly typed rules.
- **Pressure:** adding `BUS` requires a new enum value, vehicle class, compatibility rule, rate, and tests. This is acceptable for a small stable taxonomy, but not for runtime-configured vehicle categories.

#### `SpotType`

- **Owns:** fixed physical capacity: `MOTORCYCLE`, `COMPACT`, or `LARGE`.
- **Invariant:** a spot's type never changes after construction.
- **Choice:** separates capacity from occupancy; `SpotStatus` answers a different question.
- **Pressure:** EV charging, accessibility, covered parking, and valet are orthogonal features. Modeling every combination as another enum value would cause combinatorial growth; use a feature set instead.

#### `SpotStatus`

- **Owns:** `AVAILABLE` or `OCCUPIED`.
- **Invariant:** `AVAILABLE` implies no occupant; `OCCUPIED` implies one occupant.
- **Choice:** makes occupancy explicit instead of inferring it everywhere from a nullable field.
- **Tradeoff:** status duplicates information in `occupant`; `ParkingSpot` must update both atomically.

#### `TicketStatus`

- **Owns:** `ACTIVE`, `PAYMENT_PENDING`, and `CLOSED`.
- **Invariant:** only legal transitions are `ACTIVE -> PAYMENT_PENDING`, `PAYMENT_PENDING -> ACTIVE` on failure, and `PAYMENT_PENDING -> CLOSED` on success.
- **Choice:** payment is modeled as an intermediate state so failure never releases the spot.
- **Pressure:** reservations, refunds, lost tickets, disputes, or manual overrides need additional states and transitions.

### Vehicle model

#### Abstract `Vehicle`

- **Owns:** immutable license plate and `VehicleType`.
- **Invariant:** plate is non-null/non-blank; type is non-null.
- **Choice:** centralizes identity and validation shared by all vehicles. The plate is the business key used by `activeByPlate`.
- **Tradeoff:** identity equality is not overridden; the service deliberately compares plate strings rather than object identity.

#### `Motorcycle`, `Car`, `Truck`

- **Owns:** no extra state; each binds construction to exactly one `VehicleType`.
- **Choice:** `new Car(plate)` is harder to misuse than a public constructor accepting an arbitrary type.
- **Principle:** simple inheritance for type-safe construction, not Template Method; subclasses do not customize an algorithm.
- **Tradeoff:** three nearly empty classes are extra code. They become more valuable if type-specific behavior appears later.

### Physical parking structure

#### `ParkingSpot`

- **Owns:** immutable spot ID/type and mutable status/occupant.
- **Invariant:** status and occupant always agree; only a compatible vehicle can occupy the spot; release must present the occupant's plate.
- **Key methods:**
  - `canFit` applies the compatibility matrix without mutation.
  - `tryOccupy` atomically checks and claims under the spot monitor.
  - `release` validates ownership before atomically clearing occupancy.
- **Choice:** the object that owns occupancy also owns its lock and invariant. The service cannot directly set status.
- **Tradeoff:** compatibility is hard-coded in a switch. A data-driven compatibility policy would be better if vehicle/spot categories were configurable.

Compatibility matrix:

| Vehicle / spot | Motorcycle | Compact | Large |
|----------------|------------|---------|-------|
| Motorcycle | Yes | Yes | Yes |
| Car | No | Yes | Yes |
| Truck | No | No | Yes |

#### `ParkingFloor`

- **Owns:** floor ID and its ordered collection of spots.
- **Invariant:** callers cannot mutate the returned spot list directly.
- **Choice:** models the physical hierarchy while remaining a simple container.
- **Gap:** duplicate spot IDs and mutation after service startup are not prevented. A production aggregate would validate uniqueness and freeze configuration.

#### `ParkingLot`

- **Owns:** lot ID and its ordered collection of floors.
- **Role:** root of the physical aggregate; its ID prefixes generated ticket IDs.
- **`getAllSpots`:** flattens floors so the assignment policy need not traverse the hierarchy.
- **Tradeoff:** flattening and first-fit search are O(N) in total spots and discard floor metadata. Large lots need indexes or a richer candidate model.

### Assignment and pricing policies

#### `SpotAssignmentStrategy`

- **Contract:** select an available compatible spot without mutating it.
- **Pattern:** Strategy; the service depends on the policy abstraction.
- **Important distinction:** selection is not reservation. `tryOccupy` is still the authority that claims a spot.
- **Pressure:** floor preference, nearest gate, VIP, EV, or accessibility requirements may need a context object rather than only vehicle plus flat spot list.

#### `FirstAvailableSpotStrategy`

- **Algorithm:** scan in floor insertion order and then spot insertion order; return the first available compatible spot.
- **Complexity:** O(N) worst case.
- **Strength:** deterministic, tiny, and easy to verify.
- **Tradeoff:** it optimizes neither walking distance nor future capacity. For example, a motorcycle can consume a large spot that a truck may later need.

#### `PricingStrategy`

- **Contract:** calculate a fee from vehicle and entry/exit times.
- **Pattern:** Strategy; pricing changes do not alter exit orchestration.
- **Choice:** the service asks for a price but does not know tariff rules.
- **Pressure:** promotions, occupancy-based pricing, subscriptions, and taxes require a richer `PricingContext`.

#### `HourlyPricingStrategy`

- **Owns:** an immutable rate map keyed by `VehicleType`.
- **Algorithm:** bill at least one hour and round every started hour upward.
- **Formula:** `hours = max(1, ceil(seconds / 3600))`.
- **Invariant:** exit cannot precede entry and a rate must exist for the vehicle type.
- **Choice:** `BigDecimal` plus scale 2 and `HALF_UP` makes monetary rounding explicit.
- **Tradeoff:** rates are fixed for the strategy instance; dynamic rates need configuration refresh or another implementation.

### Ticket lifecycle

#### `ParkingTicket`

- **Owns immutable session data:** ticket ID, vehicle, spot, entry time.
- **Owns mutable lifecycle data:** status, exit time, and fee.
- **Invariant:** a closed ticket has passed through payment pending and records fee/exit time.
- **Pattern:** explicit state machine implemented with guarded transition methods, not the GoF State pattern.
- **Why behavior belongs here:** callers cannot arbitrarily assign status; the ticket validates its own lifecycle.
- **Tradeoff:** no event history, payment reference, failure reason, or retry count is retained.

### Payment boundary

#### `PaymentService`

- **Contract:** attempt payment for a ticket and amount.
- **Pattern:** dependency inversion / outbound port. A real gateway adapter can replace the mock without changing the domain workflow.
- **Assumption:** ticket ID acts as the payment correlation/idempotency key, but idempotency is not enforced by the interface.
- **Pressure:** real integrations need timeout, idempotency, gateway status, and asynchronous completion semantics.

#### `PaymentResult`

- **Owns:** immutable success flag and optional payment reference.
- **Choice:** expected payment decline is data, not necessarily an exception.
- **Invariant:** success requires a reference; failure has none.
- **Tradeoff:** no decline code or human-readable reason. Prefer a richer sealed result when callers need different recovery paths.

#### `MockPaymentService`

- **Role:** test double and demo adapter with controllable success/failure.
- **Pattern:** fake/stub-like test double, not production payment logic.
- **Strength:** makes both exit outcomes deterministic without network dependencies.
- **Tradeoff:** it does not model latency, retries, duplicate requests, or partial gateway failures.

### Domain failures

#### `ParkingLotFullException`

- **Meaning:** no currently available spot can fit the vehicle.
- **Why custom:** callers can distinguish a normal capacity failure from a programming/configuration error.
- **Tradeoff:** it does not expose availability details or a retry estimate.

#### `VehicleAlreadyParkedException`

- **Meaning:** the plate already has an entry in the active-ticket index.
- **Invariant protected:** one active parking session per plate per service instance.
- **Tradeoff:** uniqueness is local to one in-memory service, not global across multiple lots or processes.

### Application service

#### `ParkingLotService`

- **Owns collaborators:** lot, assignment strategy, pricing strategy, and payment port.
- **Owns indexes:** all tickets by ID and active tickets by plate.
- **Owns sequencing:** monotonically increasing ticket suffix per service instance.
- **Pattern:** application service/orchestrator with constructor injection. It is not a pure domain entity and its maps are not a full Repository abstraction.
- **Main invariant:** ticket, active-plate index, and spot occupancy move together under one service lock.

`park(vehicle)`:

1. Reject null or a plate already active.
2. Flatten the lot and ask the strategy for a candidate.
3. Atomically claim the candidate with `tryOccupy`.
4. Generate an ID and create an `ACTIVE` ticket.
5. Store it in both indexes.

`exit(ticketId)`:

1. Resolve the ticket and reject unknown/closed tickets.
2. Move it to `PAYMENT_PENDING`.
3. calculate the fee and call the payment port.
4. On payment failure or exception, move back to `ACTIVE`; keep the spot occupied.
5. On success, release the spot, close the ticket, and remove the plate from the active index.

Key tradeoffs:

- All public operations are synchronized. This makes multi-object transitions easy to reason about but serializes the entire lot.
- The payment call occurs while the lock is held. A slow gateway blocks unrelated entry and exit operations.
- If a selected spot loses the race before `tryOccupy`, the service throws instead of asking for another candidate. The single-service lock normally prevents this, but multiple service instances expose the gap.
- Closed tickets remain forever in memory. Persistence/archival is required for a long-running system.

### Executable support classes

#### `ParkingLotDemo`

- **Role:** composition root and happy-path example.
- **Teaches:** manual dependency injection - construct lot, strategies, payment adapter, and service at the application edge.
- **Not responsible for:** business rules; it only wires and invokes the model.

#### `ParkingLotVerification`

- **Role:** dependency-free regression harness for compatibility, pricing, lifecycle, failure, and concurrency scenarios.
- **Choice:** easy to run with only a JDK.
- **Tradeoff:** lacks JUnit discovery, fixtures, parameterized tests, and standard Maven test lifecycle integration.

## 4. End-to-end consistency

### Successful park

```text
plate absent from activeByPlate
    -> strategy selects candidate
    -> spot.tryOccupy succeeds
    -> ACTIVE ticket created
    -> ticket stored by ID and plate
```

Postcondition: one occupied spot, one active ticket, and one active plate entry refer to the same vehicle.

### Successful exit

```text
ACTIVE ticket
    -> PAYMENT_PENDING
    -> fee calculated
    -> payment succeeds
    -> spot released
    -> ticket CLOSED
    -> plate removed from activeByPlate
```

### Failed payment

```text
ACTIVE -> PAYMENT_PENDING -> ACTIVE
spot remains OCCUPIED
activeByPlate entry remains
```

This is the fail-safe choice: the system prefers temporarily retaining a spot over allowing an unpaid vehicle to appear exited.

## 5. Concurrency model

| Layer | Lock | Protects |
|-------|------|----------|
| `ParkingLotService` | Service monitor | Maps, ID sequence usage, and complete park/exit workflows |
| `ParkingSpot` | Spot monitor | Status plus occupant check-and-set |
| `ParkingTicket` | Ticket monitor | Status transitions, exit time, and fee |

Why both coarse and local locks?

- The service lock protects invariants spanning several objects.
- Spot and ticket locks protect each object's invariant even if they are used through another service path later.
- With the current single service instance, local locks are partly defensive; they become important if orchestration is made more concurrent.

Production evolution:

1. Do not hold a global lot lock across network payment.
2. Persist ticket/spot state in one transactional store.
3. Use optimistic locking or version columns for spot claims.
4. Make payment idempotent using ticket ID.
5. Complete exit through a retryable workflow or outbox/saga when payment and parking state live in different systems.

## 6. Complexity and scale limits

| Operation | Current cost | Scaling concern |
|-----------|--------------|-----------------|
| `park` | O(N) spots | Full scan and serialized service lock |
| `exit` | O(1) local work plus payment latency | External call holds service lock |
| `findTicket` | O(1) average | Historical map grows without bound |
| Duplicate-plate check | O(1) average | Valid only within one service instance |
| Fee calculation | O(1) | Missing richer pricing context, not CPU cost |
| Lot storage | O(N) spots | Static configuration is fully in memory |
| Ticket storage | O(T) lifetime tickets | Needs archive/retention policy |

For a large lot, maintain availability indexes by spot type/floor rather than rebuilding and scanning a flat list. Be careful: an index introduces another invariant that must be updated atomically with spot occupancy.

## 7. Patterns: say exactly what is present

| Pattern/principle | Present where | Do not overclaim |
|-------------------|---------------|------------------|
| Strategy | Spot assignment and pricing | Payment is better described as a port; its implementations may be strategies/adapters. |
| Dependency inversion | Service depends on three interfaces | Constructor injection is manual; no DI framework is needed. |
| State machine | `ParkingTicket` transition methods | This is not the GoF State pattern because there are no state objects. |
| Aggregate/composition | Lot contains floors; floors contain spots | `ParkingLot` does not enforce every aggregate invariant such as unique IDs. |
| Application service | `ParkingLotService` | It coordinates entities; it should not absorb spot compatibility or pricing rules. |
| Value objects | Enums and `PaymentResult` record | `Vehicle` and `ParkingTicket` have identity and lifecycle; they are entities. |
| Test double | `MockPaymentService` | It is not a production adapter. |

Patterns intentionally not used:

- **Factory/Builder:** construction is currently simple and readable.
- **Observer:** no requirement publishes occupancy events.
- **Repository:** maps are implementation details; no persistence abstraction exists yet.
- **Singleton:** lifecycle should be controlled by application wiring, not global state.

## 8. Weaknesses an interviewer should expect you to find

1. Payment under the service lock blocks unrelated work.
2. Spot selection and claim are separate; there is no retry after a lost race.
3. Closed-ticket storage is unbounded and volatile.
4. Linear spot search does not scale.
5. Floor and spot IDs are not checked for uniqueness.
6. Configuration can still be mutated after service startup.
7. One active plate is enforced only inside one service instance.
8. Payment result lacks failure detail and durable idempotency.
9. Success payment followed by a release failure has no compensation path.
10. `Instant.now()` is hard-coded, making time-based tests less controllable than injecting a `Clock`.

## 9. Requirement-to-design evolution

| New requirement | Smallest responsible change |
|-----------------|-----------------------------|
| Nearest entry/exit spot | Add location metadata and a new assignment strategy. |
| EV/accessibility spots | Add spot features and assignment request context. |
| Reservations | Add reservation ownership/expiry and a reserved spot state. |
| Daily cap/weekend rates | Add another pricing strategy or richer pricing context. |
| Real gateway | Implement `PaymentService` with timeout and idempotency. |
| Multiple service instances | Move consistency to transactional persistence/distributed coordination. |
| Faster assignment | Maintain availability indexes per floor/type. |
| Audit/reporting | Persist immutable ticket/payment events outside the service lock. |
| Testable time | Inject `java.time.Clock` into service and pricing workflow. |
| Refunds/disputes | Extend ticket/payment lifecycle explicitly; do not overload `CLOSED`. |

## 10. Interview revision questions

Be able to answer these without reading the code:

1. Which class owns the invariant `AVAILABLE <=> occupant == null`, and why?
2. Why does the ticket need `PAYMENT_PENDING` instead of only active/closed?
3. Why are `tickets` and `activeByPlate` separate maps?
4. What happens to every object when payment fails?
5. Why is strategy selection not equivalent to reserving a spot?
6. What exact race does `tryOccupy` prevent?
7. Why is holding the service lock during payment safe but slow?
8. Which policies are genuinely variable and therefore interfaces?
9. When would the current O(N) assignment become unacceptable?
10. How would you preserve consistency after moving state to a database?
11. Where would idempotency be required in park, exit, and payment?
12. Which current classes are entities, value objects, services, ports, and test doubles?

## 11. Final recall sheet

- **Spot owns occupancy.** Service never mutates spot fields directly.
- **Ticket owns lifecycle.** Service requests transitions; ticket validates them.
- **Service owns workflow and indexes.** It keeps cross-object state aligned.
- **Strategies own changing policies.** Assignment and pricing vary independently.
- **Payment is a boundary.** Failure restores `ACTIVE` and keeps the spot occupied.
- **Coarse locking buys correctness.** Its price is serialized work and blocked payment latency.
- **Current design is intentionally in-memory.** Production needs persistence, idempotency, retries, and observability.
- **Do not add patterns speculatively.** Add an abstraction when a real variation point appears.