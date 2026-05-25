# FEATURE_LOG.md
> Every feature, change, fix, and decision — tracked chronologically.
> Newest entries at the top.
> Last updated: 2025-05-25

---

## Log Format

Each entry follows this format:

```
### [YYYY-MM-DD] <TYPE>: <Title>
**Status**: ✅ Done | 🔄 In Progress | 📋 Planned | ❌ Reverted
**Files**: list of affected files
**Details**: what changed, why, and any trade-offs

---
```

**Types**: `FEAT` (feature), `FIX` (bug fix), `REFACTOR` (restructure), `DECISION` (architectural decision), `DOCS` (documentation), `CHORE` (build/config), `TEST` (test additions)

---

## Entries

---

### [2025-05-25] DOCS: Project Foundation Documents Created
**Status**: ✅ Done
**Files**: `PROJECT_CONTEXT.md`, `ARCHITECTURE.md`, `CODING_RULES.md`, `FEATURE_LOG.md`
**Details**:
Created the four foundational project documents before any code is written:
- `PROJECT_CONTEXT.md` — project memory capturing stack, flows, dependencies, threading model, configuration, safety invariants, and planned directory structure.
- `ARCHITECTURE.md` — detailed layer breakdown (market data, engine, risk, executor), class responsibilities, data contracts, dependency graph, startup/shutdown sequences, and error handling strategy.
- `CODING_RULES.md` — naming conventions, financial math rules (BigDecimal-only), concurrency rules, logging standards, testing patterns, and banned patterns list.
- `FEATURE_LOG.md` — this file, tracking every change chronologically.

---

### [2025-05-25] DECISION: Manual Dependency Injection (No Framework)
**Status**: ✅ Done
**Files**: N/A (architectural decision)
**Details**:
Decided against Spring, Guice, or Dagger for dependency injection. Rationale:
1. The bot has a small, fixed object graph (~15 classes).
2. Startup time matters — no classpath scanning or annotation processing.
3. `Main.java` is the single wiring point — easy to read, easy to debug.
4. The `--mode` flag is the only switching mechanism; a full DI framework is overkill.

**Trade-off**: More manual wiring code in `Main.java`, but the simplicity and speed gains outweigh this.

---

### [2025-05-25] DECISION: LinkedBlockingQueue for Signal Decoupling
**Status**: ✅ Done
**Files**: N/A (architectural decision)
**Details**:
Chose `LinkedBlockingQueue` over alternatives:
- `ArrayBlockingQueue` — bounded, but we don't want to drop signals on capacity.
- `Disruptor` — higher throughput but unnecessary complexity for our signal rate.
- `ConcurrentLinkedQueue` — non-blocking but requires polling; we want blocking `take()`.

The queue is unbounded because signal objects are small and the risk layer filters stale ones. Monitoring via `size()` will alert if the queue grows unexpectedly.

---

### [2025-05-25] DECISION: Reject = Re-queue, Not Drop
**Status**: ✅ Done
**Files**: N/A (architectural decision)
**Details**:
When `RiskGate` rejects a signal, it goes back into `SignalQueue` instead of being dropped. Rationale:
- A rejection reason like "concurrent execution in-flight" is temporary.
- The signal may still be valid on the next evaluation cycle.
- A re-queue counter prevents infinite loops — signals are dropped after N retries.

---

### [2025-05-25] DECISION: AbortHandler as Separate Class
**Status**: ✅ Done
**Files**: N/A (architectural decision)
**Details**:
Abort logic is extracted into its own class rather than being embedded in `LiveExecutor`. Rationale:
1. Abort logic is the same regardless of mode — even `PaperExecutor` should log what an abort *would* do.
2. Single responsibility — executors handle orders, `AbortHandler` handles failure recovery.
3. Testability — can unit test abort scenarios independently.

---

### [2025-05-25] DECISION: 100ms Engine Tick Rate
**Status**: ✅ Done
**Files**: N/A (architectural decision)
**Details**:
Engine fires every 100ms (10 ticks/second). Rationale:
- Bitget WebSocket updates arrive ~100-500ms apart for active pairs.
- 100ms is fast enough to catch opportunities without burning CPU on redundant ticks.
- The tick reads from `PriceCache` (a `ConcurrentHashMap` read) — extremely cheap.
- Can be tuned via `engine.tick.ms` config without code changes.

---

### [2025-05-25] DECISION: 5-Second Heartbeat Timeout
**Status**: ✅ Done
**Files**: N/A (architectural decision)
**Details**:
If no price update arrives for 5 seconds, `HeartbeatMonitor` kills the engine. Rationale:
- 5 seconds without a price update on an active pair strongly suggests a connection issue.
- Trading on stale data is worse than not trading at all.
- The engine restarts automatically once `ReconnectHandler` restores the WebSocket connection.

---

<!-- 
TEMPLATE FOR NEW ENTRIES:

### [YYYY-MM-DD] TYPE: Title
**Status**: ✅ Done | 🔄 In Progress | 📋 Planned | ❌ Reverted
**Files**: `file1.java`, `file2.java`
**Details**:
Description of what changed, why, and any trade-offs.

---
-->
