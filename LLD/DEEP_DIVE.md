# Parking Lot — Deep Dive

This document explains **how the parking lot works and why every class is shaped the way it is**: the real problem, the end-to-end flows with concrete examples, and for each class its state, invariants, design decisions and subtle details. It assumes no prior knowledge of the design.

Other docs: [README](../README.md) (code reference) · [LLD/README](README.md) (diagrams) · [REVISION_NOTES](REVISION_NOTES.md) (cheat sheet).

---

## Table of contents

1. [The real problem](#1-the-real-problem)
2. [Vocabulary you need first](#2-vocabulary-you-need-first)
3. [The big picture](#3-the-big-picture)
4. [End-to-end walkthroughs](#4-end-to-end-walkthroughs)
5. [Class-by-class: decisions and invariants](#5-class-by-class-decisions-and-invariants)
6. [Cross-cutting decisions](#6-cross-cutting-decisions)
7. [Known flaws, explained in depth](#7-known-flaws-explained-in-depth)
8. [How the pieces would change](#8-how-the-pieces-would-change)
9. [Self-test](#9-self-test)

---

## 1. The real problem

"Park a car, charge it when it leaves" hides four genuinely tricky things:

| Difficulty | Why it is hard |
|------------|----------------|
| **Fit** | Not every vehicle fits every spot (a truck cannot use a compact spot). |
| **Shared state** | Many entrances/exits act at once; two cars must never be given the same spot. |
| **Several records must agree** | A parking session lives in three places at once: the *spot* (occupied), the *ticket* (active) and the *plate index* (this plate is parked). If they disagree you get ghost cars or double-parked cars. |
| **Payment can fail** | The customer is at the exit barrier and the card is declined. The car must stay parked, the spot must stay occupied, and they must be able to try again. |

Everything in this design answers one question: **how do we keep "spot occupied", "ticket active" and "plate indexed" telling the same story, even when payment fails or threads race?**

The central rule (the *master invariant*):

> An **active ticket**, an entry in the **active-plate index**, and an **occupied spot** must describe the same parking session — all present together, or all absent together.

---

## 2. Vocabulary you need first

**Invariant** — a fact that must always be true about an object, regardless of the order of calls. A class is well designed when outside code *cannot* break its invariants.

**Strategy pattern** — a rule that may change (how to choose a spot, how to compute a fee) is hidden behind an interface so a different implementation can be plugged in without touching the workflow. Used for `SpotAssignmentStrategy` and `PricingStrategy`.

**Port** — an interface for something outside the domain (`PaymentService`). The lot only knows "pay this amount and tell me whether it worked".

**State machine** — an object that is in exactly one named state, with only certain transitions legal. `ParkingTicket` is one: `ACTIVE → PAYMENT_PENDING → CLOSED`.

**Check-then-act race (TOCTOU)** — "is the spot free? yes → take it" is unsafe when two threads interleave between the check and the take. The cure is a single *atomic* operation (`tryOccupy`) that checks and takes under one lock.

**Orchestrator** — a class that runs a workflow by calling other classes in the right order but does not contain their rules (`ParkingLotService`).

---

## 3. The big picture

### 3.1 Two hierarchies

**Physical hierarchy (what exists):**

```text
ParkingLot ── has many ──> ParkingFloor ── has many ──> ParkingSpot ── currently holds ──> Vehicle
```

**Business hierarchy (what happened):**

```text
ParkingTicket  = (vehicle, spot, entryTime) + status + (exitTime, fee once closed)
```

The ticket is the *session record*. The spot only knows "someone is here now"; the ticket remembers who, since when, and how it ended.

### 3.2 Who talks to whom

```text
                    Caller (gate software)
                           │  park(vehicle) / exit(ticketId)
                           ▼
                  ┌────────────────────┐
                  │  ParkingLotService │  orchestrator, owns both indexes, one lock
                  └────────────────────┘
        ┌──────────┬───────────┬────────────┬───────────────┐
        ▼          ▼           ▼            ▼               ▼
   ParkingLot  SpotAssignment  Pricing   PaymentService  ParkingTicket
   (floors →   Strategy        Strategy  (port)          (state machine)
    spots)     (interface)     (interface)
        │
        ▼
   ParkingSpot  (atomic occupy / release)
```

Arrows point downward; the domain objects (`Vehicle`, `ParkingSpot`, `ParkingTicket`) know nothing about the service. The service is the only class that sees the whole picture.

### 3.3 Wiring

The service receives its four collaborators through the constructor:

```java
new ParkingLotService(lot, new FirstAvailableSpotStrategy(), new HourlyPricingStrategy(rates), paymentService);
```

Because everything is injected, the verification code can swap in a `MockPaymentService` that fails on demand, and a pricing strategy with fixed rates — no code change inside the service.

### 3.4 Package map

| Package | Question it answers |
|---------|---------------------|
| `enums` | What kinds/states exist? (`VehicleType`, `SpotType`, `SpotStatus`, `TicketStatus`) |
| `vehicle` | What is being parked? |
| `spot` | One physical bay, its fit rule and its atomic claim/release |
| `lot` | How spots are grouped into floors and a lot |
| `strategy` | Replaceable rules: choosing a spot, computing a fee |
| `payment` | The payment port and a mock |
| `ticket` | The session and its lifecycle |
| `service` | The use-cases `park` and `exit` |
| `exception` | Domain-specific failures |

---

## 4. End-to-end walkthroughs

Setup: lot `TEST-01`, one floor `G` with three spots in this order: `G-M1` (MOTORCYCLE), `G-C1` (COMPACT), `G-L1` (LARGE). Hourly rate for CAR = 50.00.

### 4.1 Fit rules (the compatibility matrix)

| Vehicle \ Spot | MOTORCYCLE | COMPACT | LARGE |
|----------------|:----------:|:-------:|:-----:|
| MOTORCYCLE | ✔ | ✔ | ✔ |
| CAR | ✘ | ✔ | ✔ |
| TRUCK | ✘ | ✘ | ✔ |

A bigger spot can hold a smaller vehicle, never the reverse.

### 4.2 Park a car: `park(new Car("CAR-1"))`

1. **Not null.** Reject a null vehicle.
2. **Duplicate check.** Is `"CAR-1"` already in `activeByPlate`? If yes → `VehicleAlreadyParkedException`. (A car cannot be in two places.)
3. **Choose.** `lot.getAllSpots()` flattens all floors into one list `[G-M1, G-C1, G-L1]`. The strategy keeps spots that are *available* and *can fit* and returns the first: `G-M1` is skipped (car doesn't fit), `G-C1` is chosen. If none → `ParkingLotFullException`.
4. **Claim.** `spot.tryOccupy(vehicle)` atomically re-checks "still available and fits" and marks it `OCCUPIED`. (Selection in step 3 was only a *suggestion*; this is the real reservation.)
5. **Ticket.** ID `TEST-01-T1`, entry time = now, status `ACTIVE`.
6. **Index.** Store in `tickets` (by ID) and `activeByPlate` (by plate).
7. Return the ticket.

State afterwards:

| Record | State |
|--------|-------|
| `G-C1` | `OCCUPIED`, occupant `CAR-1` |
| Ticket `TEST-01-T1` | `ACTIVE` |
| `activeByPlate` | `{"CAR-1" → T1}` |

All three say the same thing — the master invariant holds.

### 4.3 Exit and pay: `exit("TEST-01-T1")` after 20 minutes

1. **Find ticket.** Unknown → `IllegalArgumentException`. Already `CLOSED` → `IllegalStateException("already closed")`.
2. **`beginPayment()`** → `ACTIVE → PAYMENT_PENDING`. This marks "money collection in progress".
3. **Fee.** `exitTime = now`. Hourly strategy: `seconds = 1200`; `hours = max(1, (1200 + 3599) / 3600) = max(1, 1) = 1` → `50.00 × 1`, scaled to 2 decimals.
4. **Pay.** `paymentService.pay(ticketId, fee)`.
5. **Success path** (payment OK):
   1. `spot.release("CAR-1")` — spot becomes `AVAILABLE`.
   2. `ticket.close(exitTime, fee)` — `PAYMENT_PENDING → CLOSED`, stores exit time and fee.
   3. Remove `"CAR-1"` from `activeByPlate`.
   4. Return the fee.

After: spot free, ticket `CLOSED`, plate no longer indexed. Again all three agree — the session is over everywhere.

### 4.4 Payment fails

`MockPaymentService.shouldFail = true` → `PaymentResult.failure()`.

- The service calls `ticket.paymentFailed()` → `PAYMENT_PENDING → ACTIVE`.
- Throws `IllegalStateException("Payment failed; vehicle remains parked")`.
- Spot **still occupied**, plate **still indexed**, ticket **ACTIVE**.

The customer can call `exit` again; the test `checkPaymentFailureKeepsSpot` does exactly that and succeeds the second time. Same outcome if `pay()` *throws* (the `catch (RuntimeException)` block does the same rollback and rethrows).

**Why the spot is freed only *after* payment succeeds:** if we freed it first and payment then failed, the lot would show a free spot with a car still sitting in it — a second car could be assigned on top of it.

### 4.5 Other error cases

| Call | Result |
|------|--------|
| `park` same plate twice | `VehicleAlreadyParkedException` |
| `park(truck)` when no LARGE spot is free | `ParkingLotFullException` (even if compact spots are free) |
| `exit` twice on the same ticket | second call → `IllegalStateException` (already closed) |
| `exit("nonsense")` | `IllegalArgumentException` |
| `new Car("  ")` | `IllegalArgumentException` (blank plate) |
| Fee with exit before entry | `IllegalArgumentException` |

### 4.6 Pricing examples (`HourlyPricingStrategy`, car 50.00/hr)

| Dwell time | `(seconds + 3599) / 3600` | Billed hours | Fee |
|-----------:|--------------------------:|-------------:|----:|
| 30 s | 0 | max(1, 0) = **1** | 50.00 |
| 3600 s | 1 | 1 | 50.00 |
| 3601 s | 2 | **2** | 100.00 |
| 2h 10m | 3 | 3 | 150.00 |

`(seconds + 3599) / 3600` is integer **ceiling division** — "every started hour counts".

---

## 5. Class-by-class: decisions and invariants

Each section: *purpose → state → invariants (and what enforces them) → design decisions → subtleties.*

---

### 5.1 Enums

`VehicleType`, `SpotType`, `SpotStatus`, `TicketStatus`.

**Decision — why `VehicleType` and `SpotType` are separate enums.** They are different vocabularies: a vehicle is a CAR; a bay is COMPACT. Their relationship is the compatibility matrix, which lives in one method (`ParkingSpot.canFit`) instead of being implied by identical names. Changing a spot's size classes does not change vehicle classes.

**Decision — `SpotStatus` (AVAILABLE/OCCUPIED) is its own enum** rather than a boolean so the state has a name and could grow (`RESERVED`, `OUT_OF_SERVICE`).

---

### 5.2 `Vehicle`, `Car`, `Motorcycle`, `Truck` (`vehicle`)

**State.** `licensePlate`, `type` — both final.

**Invariants.**
1. A plate is never null or blank (checked in the base constructor; `new Car("  ")` throws).
2. A vehicle's type never changes (final) — a car cannot become a truck mid-stay and suddenly need a different spot.
3. The subclass fixes the type: `Car` always passes `VehicleType.CAR`. You cannot build "a `Car` object that claims to be a `TRUCK`".

**Decisions.**
- *Abstract base + final subclasses* give a safe extension point (e.g. a future `ElectricCar` with charging behaviour) while the base class guarantees identity and validation.
- *Honest tradeoff:* today the subclasses carry no extra behaviour, so a single `Vehicle(plate, type)` class with the enum would be enough. The hierarchy is kept to match the usual domain model and to allow future per-type behaviour; if that never arrives, it is over-engineering.
- No `equals`/`hashCode`: identity of a parked vehicle is tracked **by plate string** in `activeByPlate`, not by object equality.

---

### 5.3 `ParkingSpot` (`spot`)

**State.** `id`, `type` (final); `status` and `occupant` (mutable, guarded by the spot's own monitor).

**Invariants.**
1. **`status == OCCUPIED` ⇔ `occupant != null`.** They are only ever changed together, inside the same synchronized method.
2. **A spot holds at most one vehicle.** `tryOccupy` refuses if not `AVAILABLE`.
3. **Only a compatible vehicle can occupy it** (`canFit` is re-checked inside `tryOccupy`).
4. **Only the occupant can release it** — `release(plate)` verifies the plate matches, so ticket A can never free the spot that vehicle B now occupies.

**Method by method.**

`canFit(vehicle)` — a switch over `VehicleType` returning the matrix in 4.1. It is **not** synchronized: it reads only `final` fields, which never change, so there is nothing to protect.

`isAvailable()` — synchronized read. Without synchronization, another thread's write to `status` might not be visible.

`tryOccupy(vehicle)` — **the atomic check-and-claim**:

```java
public synchronized boolean tryOccupy(Vehicle vehicle) {
    if (status != AVAILABLE || !canFit(vehicle)) return false;   // check
    occupant = vehicle; status = OCCUPIED; return true;           // act — under the same lock
}
```

`release(plate)` — throws `IllegalStateException` if the spot is not occupied by that plate; otherwise clears both fields.

**Decisions.**
- *Why does the spot protect itself instead of relying on the service?* Defence in depth. Even if somebody creates a second service over the same lot, the spot cannot be double-booked; the invariant lives with the data.
- *Why does `tryOccupy` return `boolean` instead of throwing?* "Someone else got it first" is an expected race outcome, not a bug.
- *Why `release` throws?* Releasing a spot that isn't yours means the bookkeeping is already corrupt; fail loudly rather than silently "fix" it.
- *Why do `status` and `occupant` both exist when one could be derived?* `status` gives a readable state name; `occupant` supports the plate check on release. The cost is the paired-update rule in invariant 1.

---

### 5.4 `ParkingFloor` and `ParkingLot` (`lot`)

**State.** An `id` and a list of children (`spots` / `floors`).

**Invariants.**
1. No null spots or floors are added (`requireNonNull`).
2. Callers cannot mutate the internal lists — getters return `Collections.unmodifiableList`.

**Decisions.**
- *Why are they separate classes from the service?* They describe **structure**, not behaviour. The service can then ask one question ("give me all spots") without knowing about floors.
- *`getAllSpots()` flattens floors into one list* so a strategy sees a simple `List<ParkingSpot>`. The strategy interface does not depend on `ParkingFloor`, so a strategy can be written without knowing the building layout. Cost: a new list is built on every `park` (O(total spots)).
- The floors' lists are **not thread-safe for modification**. The assumption: the building is constructed *before* the service starts and not changed afterwards. Adding spots while parking is in progress would need a different design.
- Floor order and spot order define the order the strategy sees — "ground floor first" is just "add the ground floor first".

---

### 5.5 `SpotAssignmentStrategy` / `FirstAvailableSpotStrategy` (`strategy`)

**Contract.** `Optional<ParkingSpot> findSpot(Vehicle, List<ParkingSpot>)` — *suggest* one spot, or empty if none.

**Invariant.** None about state — and that is deliberate: the strategy is **pure advice**. It must not occupy anything. The service owns the claim.

**Decisions.**
- *Why a Strategy?* "Which spot?" is the policy most likely to change (nearest the exit, cheapest level, EV charger, VIP). It is an interface so the service's `park` never changes when it does.
- *`Optional` return* — "no suitable spot" is a normal result, not an exception at this layer; the service converts it to `ParkingLotFullException`.
- *Why the strategy filters with `isAvailable()` and then the service calls `tryOccupy()`?* Two separate looks at the same spot: the first is a cheap, non-binding filter; the second is the binding atomic claim. Selection is **not** reservation.

**Known weakness (important):** `FirstAvailableSpotStrategy` is *first-fit*, not *best-fit*. See 7.2.

---

### 5.6 `PricingStrategy` / `HourlyPricingStrategy` (`strategy`)

**State.** An immutable copy of the rate table (`Map.copyOf`).

**Invariants.**
1. The rate table cannot change after construction (defensive copy).
2. Exit time may not precede entry time → `IllegalArgumentException`.
3. Every fee is at least one hour's rate and is a `BigDecimal` with exactly 2 decimals (`HALF_UP`).
4. A vehicle type without a configured rate fails loudly rather than being charged zero.

**Decisions.**
- *Pricing is a Strategy* because the rule (hourly, daily cap, weekend rates, first-hour-free) changes independently of the parking workflow.
- *`BigDecimal`* — no floating-point rounding errors in money.
- *Takes `entryTime` and `exitTime` as parameters rather than reading the clock.* The strategy is a pure function: the same inputs always give the same fee, so tests use fixed instants (`2026-01-01T10:00:00Z`) with no sleeping. (The service itself calls `Instant.now()`; making that injectable via a `Clock` would let tests control time end to end.)
- *Rates keyed by `VehicleType`* so a motorcycle can be cheaper than a truck without any code.

---

### 5.7 `PaymentService`, `PaymentResult`, `MockPaymentService` (`payment`)

**`PaymentService` is a port**: `PaymentResult pay(ticketId, amount)`. Real implementations (card gateway, UPI) plug in here.

**`PaymentResult` (record).** `(successful, reference)`, immutable. The `success(ref)` / `failure()` factory methods make intent readable and produce well-formed values (a failure carries no reference). *Note:* the record's canonical constructor is still public, so nothing technically stops `new PaymentResult(true, null)`; the factories are a convention, not an enforced invariant.

**Decision — a result object, not an exception, for a declined payment.** A decline is a normal business answer. An exception (`RuntimeException`) from `pay` is still handled by the service for the abnormal case (gateway crash), and both paths roll the ticket back.

**`MockPaymentService`.** Demo-only; its `shouldFail` flag lets tests simulate a decline. The flag isn't `volatile`/synchronized because tests set it from one thread before calling the service.

---

### 5.8 `ParkingTicket` (`ticket`)

**State.** `id`, `vehicle`, `spot`, `entryTime` (final); `status`, `exitTime`, `fee` (mutable, synchronized).

**State machine.**

```text
        beginPayment()                 close(exitTime, fee)
ACTIVE ───────────────> PAYMENT_PENDING ───────────────────> CLOSED   (terminal)
   ^                          │
   └──────── paymentFailed() ─┘
```

**Invariants.**
1. **Only the transitions above are possible.** `requireStatus(expected)` throws `IllegalStateException` for anything else — e.g. `close()` straight from `ACTIVE` (skipping payment) is impossible.
2. **`CLOSED` is terminal** — nothing leaves it, so a ticket cannot be paid or closed twice.
3. **`exitTime` and `fee` are set exactly when `CLOSED`** and never before (they are assigned only inside `close`).
4. Who parked, where, and when never changes (final fields).
5. Status reads/writes are synchronized so another thread sees a consistent state.

**Decisions.**
- *Why a separate `PAYMENT_PENDING` state instead of just ACTIVE → CLOSED?* It records "a payment attempt is in flight". That gives (a) protection against starting a second payment on the same ticket (`beginPayment` demands `ACTIVE`), and (b) a named place to roll back to `ACTIVE` when the payment fails. It would also be the hook for moving payment *outside* the service-wide lock (see 7.1).
- *Why does the ticket enforce its own transitions rather than the service?* The rule "you can't close an unpaid ticket" belongs to the ticket; if enforcement lived in the service, any other caller could bypass it.
- *Why does the ticket reference the `ParkingSpot` object, not a spot ID?* The service needs the spot to release it at exit; keeping the reference avoids a lookup.
- Ticket getters for final fields are not synchronized (immutable); getters for mutable fields are.

---

### 5.9 `ParkingLotService` (`service`)

The orchestrator. It owns **two indexes** and **one lock**.

**State.**

| Field | Purpose |
|-------|---------|
| `tickets` : id → ticket | Look up by ticket ID at exit (and keep history). |
| `activeByPlate` : plate → ticket | Reject a plate that is already parked, in O(1). |
| `ticketSequence` | Source of ticket numbers. |
| `lot`, strategies, payment | Injected collaborators (final). |

**Invariants.**
1. **Master invariant:** active ticket ⇔ `activeByPlate` entry ⇔ occupied spot (4.2, 4.3).
2. **At most one active ticket per plate.**
3. **Ticket IDs are unique** (monotonic sequence under the lock).
4. **A spot is freed only after payment has succeeded.**
5. **Every `exit` that fails leaves the ticket `ACTIVE` and the spot occupied** (failed payments roll back).

**How each method preserves the master invariant — step ordering matters.**

`park`:

```text
check plate not active ──> select spot ──> tryOccupy (claim) ──> create ticket ──> index it
        (nothing changed yet)              (first change)          (…)            (done)
```

Every failure before `tryOccupy` changes nothing. After the claim, the remaining steps cannot fail, so there is no window where the spot is claimed but nobody tracks it.

`exit`:

```text
find ticket ──> beginPayment ──> fee ──> pay ──┬─ fail/throw ─> paymentFailed (→ ACTIVE), nothing else changed
                                               └─ success ───> release spot ──> close ticket ──> unindex plate
```

The irreversible part (releasing the spot, closing the ticket) happens only after the uncertain external call has succeeded.

**Decisions.**
- *One `synchronized` per method — a single service-wide lock.* Simplest policy that is obviously correct: it protects both maps, spot selection, ticket transitions and payment outcome. Because every operation takes the same lock, it is impossible to interleave "half a park" with "half an exit". *Tradeoff:* parks and exits are fully serialised and (7.1) a slow payment blocks the whole lot. The assumption is **one service instance per lot**.
- *Plain `HashMap`s, not `ConcurrentHashMap`* — they are only accessed inside the service lock, so a concurrent map would add cost without adding safety. (`AtomicLong` is redundant under the lock but harmless.)
- *Why two maps?* Different questions: "which ticket is this ID?" and "is this plate already parked?". One map can't answer both in O(1); the price is keeping them consistent — that is exactly what the master invariant and the step ordering are for.
- *Ticket ID `{lotId}-T{n}`* — human-readable, unique per lot.
- *Why check `status == CLOSED` in the service when `beginPayment` would already throw?* To give a clear message ("already closed") instead of the generic "Expected ACTIVE, found CLOSED".
- *Why `park` throws `IllegalStateException` if `tryOccupy` fails after selection?* Under the service lock no other service instance can interfere, so it "can't happen"; if it does, the system is in an unexpected state and should say so. (A multi-instance deployment would retry the next candidate instead — documented in the LLD.)
- `findTicket` / `activeTicketCount` are synchronized for consistent reads.

---

### 5.10 Exceptions (`exception`)

- `ParkingLotFullException` — no compatible free spot.
- `VehicleAlreadyParkedException` — same plate already has an active ticket.

Both are unchecked, final, and named for **business situations**, so gate software can react (show "lot full", sound an alarm for a duplicate plate) without parsing messages. Misuse and illegal states reuse `IllegalArgumentException` / `IllegalStateException`.

---

### 5.11 `ParkingLotDemo` and `ParkingLotVerification`

- `ParkingLotDemo` — builds lot `CHENNAI-01` with three ground-floor spots, wires the service, parks one car and exits it (fee 50.00).
- `ParkingLotVerification` — hand-written assertions (no framework): fit rules, pricing boundaries (30 s, 3601 s, exit-before-entry), plate validation, happy path, duplicate plate, full lot for truck, **payment failure keeps the spot then succeeds on retry**, double exit and unknown ticket.

---

## 6. Cross-cutting decisions

### 6.1 Exceptions vs return values

| Kind of problem | Mechanism | Example |
|-----------------|-----------|---------|
| Normal outcome | return value | `tryOccupy → false`, `findSpot → Optional.empty`, `PaymentResult.failure()` |
| Business refusal | domain exception | `ParkingLotFullException`, `VehicleAlreadyParkedException` |
| Caller misuse | `IllegalArgumentException` | unknown ticket, blank plate |
| Illegal sequence | `IllegalStateException` | double exit, `close` before payment |

### 6.2 Layered protection (belt and braces)

The same rule is protected at more than one level on purpose:

| Rule | Spot | Ticket | Service |
|------|:----:|:------:|:-------:|
| No double occupancy | ✔ `tryOccupy` | | ✔ plate index + lock |
| No double payment/exit | | ✔ state machine | ✔ `CLOSED` check |
| Only the owner frees a spot | ✔ plate check | | ✔ ordering |

### 6.3 Concurrency map

| Object | Mechanism | Why |
|--------|-----------|-----|
| `ParkingLotService` | all public methods `synchronized` | keeps indexes, spots and tickets consistent across steps |
| `ParkingSpot` | `synchronized` check-and-claim / release | the invariant lives with the data even without the service |
| `ParkingTicket` | `synchronized` status methods | safe transitions and visibility |
| `Vehicle`, `ParkingFloor` lists | none (immutable / build-then-use) | no concurrent mutation by design |

Lock order is always service → spot or service → ticket, never the reverse, so there is no deadlock.

### 6.4 Immutability

Identity-like data is `final` (`Vehicle`, spot ID/type, ticket ID/vehicle/spot/entry time, rate table, `PaymentResult`). Mutable state is confined to: spot occupancy, ticket status/exit/fee, the service's two maps.

---

## 7. Known flaws, explained in depth

### 7.1 Payment is called while holding the service-wide lock

`exit` is `synchronized` and calls `paymentService.pay(...)` inside it. A card gateway can take seconds. During that time **no one can park or exit** anywhere in the lot.

*Why it was accepted:* simplicity and obvious correctness for a baseline.

*Fix direction:* the design already contains the tool — `PAYMENT_PENDING`. Under the lock: validate, call `beginPayment()`, compute the fee; **release the lock**; call `pay`; re-take the lock to finish (release spot, close, unindex) or roll back. The `PAYMENT_PENDING` state is what stops a second exit of the same ticket from starting a second payment while the lock is not held.

### 7.2 First-fit can waste big spots

`FirstAvailableSpotStrategy` returns the first fitting free spot in list order. With spots `[G-L1 (LARGE), G-C1 (COMPACT)]`:

| Step | Event | Result |
|------|-------|--------|
| 1 | Car arrives | takes `G-L1` — it is first in the list and fits |
| 2 | Truck arrives | needs LARGE; `G-L1` is taken; **lot full**, although `G-C1` is free and a car could have used it |

A *best-fit* strategy (prefer the smallest spot type that fits) avoids this. This is exactly why assignment is a Strategy — it can be replaced without touching the service.

### 7.3 Payment is not idempotent / uncertain outcomes

If `pay` charges the card and then the network drops, the service sees an exception, rolls the ticket back to `ACTIVE` and the customer is charged but still "parked". Compare with the ATM project, which has an explicit "outcome unknown" state. Here the fee is also recomputed on each retry (exit time moves), so the same `ticketId` can be sent with a different amount. A production version would pass an idempotency key and distinguish "declined" from "unknown".

### 7.4 Stuck ticket if `release` ever fails

After a successful payment the service calls `spot.release(...)` *then* `ticket.close(...)`. If `release` threw (only possible if the invariants were already broken), the customer would have paid but the ticket would stay `PAYMENT_PENDING`, and a retry would fail (`beginPayment` demands `ACTIVE`). Under the current locking this cannot happen; it is a place where a reconciliation/repair path would sit.

### 7.5 Smaller limitations

- All records are in memory; `tickets` keeps closed tickets forever (memory grows, no archival).
- `getAllSpots()` rebuilds a list each `park` — O(spots) per arrival; an index of free spots per type would be O(1).
- The clock is `Instant.now()` inside the service (not injectable).
- Lost-ticket handling, reservations, per-floor capacity displays and multi-gate scaling are out of scope.
- Ticket IDs are sequential and guessable; anyone holding an ID can exit that session.
- `MockPaymentService.shouldFail` is not thread-safe (it is test-only).

---

## 8. How the pieces would change

| New requirement | What changes | What stays |
|-----------------|--------------|------------|
| Nearest-to-exit / EV / VIP spots | new `SpotAssignmentStrategy` | service, spot, ticket |
| Best-fit spot selection | new strategy (fix for 7.2) | everything else |
| Daily cap, weekend rates, grace period | new `PricingStrategy` | service |
| Real payment gateway | new `PaymentService` adapter; add idempotency key | ticket states |
| Don't block the lot during payment | restructure `exit` to pay outside the lock (7.1) | ticket (state already exists) |
| Multiple entrances (several service instances) | shared state must move to a store; `park` retries the next candidate on `tryOccupy` failure | spot's atomic claim |
| Persistence | repository interfaces replace the two maps | ticket state machine |
| New vehicle type (e.g. bus) | enum value, `canFit` row, rate | strategies |
| Injectable time | inject a `Clock` into the service | strategies |

---

## 9. Self-test

1. State the master invariant and name the three places it lives. (1, 4.2)
2. Why is selecting a spot not the same as reserving it, and what makes the claim safe? (5.5, 5.3)
3. In `exit`, why is the spot released only after payment succeeds? (4.4)
4. What does `PAYMENT_PENDING` give you that `ACTIVE → CLOSED` would not? (5.8, 7.1)
5. A truck is refused although a compact spot is free. Is that a bug? What about a car refused while a *large* spot is free? (4.5, 7.2)
6. Compute the fee for 2 h 1 min, car at 50.00/hr. (4.6 → 3 hours → 150.00)
7. Why are the service's maps plain `HashMap`s? (5.9)
8. Which exceptions represent business outcomes and which represent misuse? (6.1)
9. Describe how a slow payment gateway would hurt this design and how you would fix it. (7.1)
10. What could go wrong if payment succeeded but the response was lost? (7.3)
