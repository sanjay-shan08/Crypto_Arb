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

### [2026-06-22] TEST: Safety Net Test Suite
**Status**: ✅ Done
**Files**: `AbortHandlerTest.java`, `RiskGateTest.java`
**Details**:
Implemented a comprehensive JUnit test suite focusing on the bot's critical safety invariants:
- `AbortHandlerTest`: Validates that failed legs correctly trigger a market order reversal (BUY reversing SELL and vice versa) and gracefully handles executor exceptions during the abort sequence.
- `RiskGateTest`: Mocks the `SignalQueue` and `TradeExecutionService` to verify that stale signals, low-profit signals, outsized positions, and concurrent execution attempts are correctly intercepted and requeued, while valid signals are passed through to the executor.

---

### [2026-06-20] FEAT: Generic Intermediary Support (USDC & ETH Triangles)
**Status**: ✅ Done
**Files**: `Triangle.java`, `RouteCalculator.java`, `TradeExecutionService.java`, `config/*`
**Details**:
Refactored the bot's core mathematical and routing logic from hardcoded BTC/USDT terminology to a generic `ALT / INTER / BASE` model. 
- Discovered that the underlying BigDecimal math and Route A/B routing paths inherently supported *any* intermediary coin acting as the quote for the altcoin and the base against USDT.
- Renamed all record properties (`altUsdtPair` → `altBasePair`), variables, and log statements to use generic terminology.
- Expanded the paper-trading config to include 14 USDC triangles, 2 ETH triangles, and 3 BTC triangles (19 total active pairs).

---

### [2026-05-31] FEAT: Stage 1 Dev - Core Implementation
**Status**: ✅ Done
**Files**: `src/main/java/com/arb/bitget/*`, `pom.xml`, `config/*`
**Details**:
Initial implementation of the core bot architecture, mapping precisely to the foundational design documents.
- Created `TradeExecutionService` to orchestrate 3-leg triangle execution, handling atomic leg state and invoking `AbortHandler` on failure.
- Implemented `BitgetApiClient` for authenticating and executing REST orders using HMAC-SHA256 signatures and OkHttp connection pooling.
- Developed all model and configuration classes (`AppConfig`, `PriceEntry`, `RouteDirection`, `Side`, `Signal`, `Triangle`).
- Created `LiveExecutor`, `SandboxExecutor`, and `PaperExecutor` fulfilling the `OrderExecutor` interface.
- Built market data stream listeners (`BitgetWebSocketClient`, `PriceCache`) and risk validators (`RiskGate`, `NetworkChecker`, `StartupChecker`).
- Set up logging (`logback.xml`) and dependency injection wiring in `Main.java`.

---

### [2026-05-29] DECISION: Dynamic Fee-Aware Net Profit Threshold
**Status**: ✅ Done
**Files**: `ARCHITECTURE.md`, `PROJECT_CONTEXT.md`
**Details**:
Based on user feedback regarding spot trading fees (0.10% per leg / 0.30% total across 3 legs), implemented a **dynamic net profit architecture** rather than a static gross threshold:
- `RouteCalculator` calculates the gross triangular arbitrage spread, then dynamically deducts the total fee cost: `(exchange.fee.rate * exchange.fee.legs)`.
- If the BGB fee discount is enabled (reducing fees to 0.08% per leg / 0.24% total), the bot automatically shifts the threshold downwards to take advantage of more opportunities.
- `RiskGate` checks if the calculated `netExpectedProfit >= MIN_PROFIT_BPS` (where `MIN_PROFIT_BPS` is the user's pure target margin above fees, e.g., 5 bps).
- This ensures the bot *never* executes a trade where the transaction costs would wipe out the arbitrage spread, prioritizing capital preservation.

---

### [2026-05-26] DECISION: Network Quality Gate (NetworkChecker)
**Status**: ✅ Done
**Files**: `NetworkChecker.java`, `ARCHITECTURE.md`, `PROJECT_CONTEXT.md`
**Details**:
Added a two-phase network quality gate to prevent trading on degraded internet:

**Phase 1 — Startup preflight:**
- Pings Bitget's `/api/v2/public/time` endpoint 5 times before engine start.
- If average round-trip > 400ms → refuses to start. Bot exits with error.
- Rationale: with 3 sequential REST legs, 400ms × 3 = 1200ms execution — too slow for even altcoin windows (5–30s) once you factor in fill confirmation and price movement risk.

**Phase 2 — Runtime monitoring:**
- Every 30 seconds, pings 3 times and checks rolling average.
- If latency spikes above threshold → pauses ArbitrageEngine (stops tick scheduler).
- WebSocket stays connected (prices keep updating) so the bot can resume instantly.
- When latency recovers below threshold → resumes engine automatically.

**Design decisions:**
- Uses `GET /api/v2/public/time` — lightweight, no auth, measures real network path.
- Uses the same `OkHttpClient` with connection pooling so measurements reflect actual trading conditions.
- Threshold of 400ms chosen because: 400ms × 3 legs + fill waits ≈ 1500ms total, which approaches the low end of altcoin opportunity windows.
- Pausing (not killing) the engine means zero restart cost when latency recovers.

---

### [2026-05-26] DECISION: Altcoin Triangles as Primary Targets
**Status**: ✅ Done
**Files**: `PROJECT_CONTEXT.md`, `ARCHITECTURE.md`
**Details**:
Based on analysis of opportunity window durations and execution latency from
Tiruppur (~650–900ms), decided to target altcoin triangles (SOL, XRP, DOGE, TRX, BGB)
where windows last 5–30+ seconds. BTC/ETH/USDT triangle (2–5 second windows) is
excluded from default config — too competitive against co-located HFT bots.

---

### [2026-05-26] DECISION: Multi-Triangle Engine Design
**Status**: ✅ Done
**Files**: `ARCHITECTURE.md`
**Details**:
ArbitrageEngine redesigned to iterate over a configurable list of triangles each tick,
rather than a single hardcoded BTC/ETH/USDT triangle. Introduces `Triangle` model class
and makes `RouteCalculator` accept a triangle parameter. This is the core competitive
advantage — scanning obscure altcoin triangles that large players ignore.

---

### [2026-05-26] FEAT: OkHttp Connection Pooling Specification
**Status**: ✅ Done
**Files**: `ARCHITECTURE.md`, `CODING_RULES.md`
**Details**:
Explicitly specified `ConnectionPool(5, 5, TimeUnit.MINUTES)` config for OkHttp.
This is the single biggest free optimization: saves ~60ms per REST call (~180ms total
across 3 legs) by reusing TCP connections instead of establishing new ones.

---

### [2026-05-26] FEAT: PaperExecutor Latency Simulation
**Status**: ✅ Done
**Files**: `ARCHITECTURE.md`, `CODING_RULES.md`
**Details**:
PaperExecutor now simulates ~200ms network delay per leg to produce realistic timing
in paper-trade P&L logs. Added exception to banned `Thread.sleep()` rule specifically
for this use case.

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
