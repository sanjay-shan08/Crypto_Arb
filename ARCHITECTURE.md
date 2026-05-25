# ARCHITECTURE.md
> Layers, services, data flow, module boundaries, and design rationale.
> Last updated: 2025-05-25

---

## 1. Architectural Overview

The bot is a **four-layer pipeline** with strict unidirectional data flow:

```
┌─────────────────────────────────────────────────────────────────┐
│                        MARKET DATA LAYER                        │
│  BitgetWebSocketClient → PriceCache                            │
│  HeartbeatMonitor, ReconnectHandler                            │
│  Package: com.arb.bitget.market                                │
└──────────────────────────┬──────────────────────────────────────┘
                           │ reads PriceCache
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                         ENGINE LAYER                            │
│  ArbitrageEngine (100ms tick) → RouteCalculator → SignalQueue  │
│  Package: com.arb.bitget.engine                                │
└──────────────────────────┬──────────────────────────────────────┘
                           │ dequeues Signal
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                          RISK LAYER                             │
│  RiskGate (accept / reject+requeue)                            │
│  StartupChecker (boot-time only)                               │
│  Package: com.arb.bitget.risk                                  │
└──────────────────────────┬──────────────────────────────────────┘
                           │ approved Signal
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                        EXECUTOR LAYER                           │
│  OrderExecutor ← PaperExecutor / SandboxExecutor / LiveExecutor│
│  AbortHandler (leg-2 failure recovery)                         │
│  Package: com.arb.bitget.executor                              │
└─────────────────────────────────────────────────────────────────┘
```

### Design Principles
- **Unidirectional flow**: Data moves top → bottom. No layer calls upward.
- **Decoupled via queue**: Engine and Executor are decoupled by `SignalQueue`.
- **Interface-based injection**: Only `OrderExecutor` is polymorphic; mode is resolved once at startup in `Main.java`.
- **Fail-safe defaults**: Stale data kills the engine. Failed leg 2 triggers abort. Rejected signals are re-queued.

---

## 2. Layer Details

### 2.1 Market Data Layer (`com.arb.bitget.market`)

**Responsibility**: Maintain a live, always-current price feed from Bitget and expose it via `PriceCache`.

#### Classes

##### `BitgetWebSocketClient`
- Connects to Bitget WebSocket API (`wss://ws.bitget.com/v2/ws/public`).
- Subscribes to `ticker` channels for all trading pairs in the triangle.
- On each message: parses JSON → updates `PriceCache` → updates heartbeat timestamp.
- Delegates reconnection to `ReconnectHandler`.

##### `PriceCache`
- Thread-safe price store backed by `ConcurrentHashMap<String, PriceEntry>`.
- `PriceEntry` contains: `bestBid`, `bestAsk`, `timestamp`.
- Provides `getPrice(pair)` — returns an immutable snapshot.
- Provides `getLastUpdateTime()` — used by `HeartbeatMonitor`.

##### `HeartbeatMonitor`
- Runs on a dedicated scheduled thread.
- Every 1 second, checks `PriceCache.getLastUpdateTime()`.
- If `now - lastUpdate > 5000ms` → calls `ArbitrageEngine.kill()`.
- Logs a CRITICAL-level message on trigger.

##### `ReconnectHandler`
- Invoked on WebSocket `onFailure` / `onClosed`.
- Implements exponential backoff: `min(2^attempt * 100ms, 30s)`.
- Resets backoff counter on successful reconnection.
- Logs each reconnection attempt with attempt number and delay.

#### Data Contract (outbound)
```java
// PriceCache exposes:
PriceEntry getPrice(String pair);     // e.g., "BTCUSDT"
long getLastUpdateTime();              // epoch millis
```

---

### 2.2 Engine Layer (`com.arb.bitget.engine`)

**Responsibility**: Detect arbitrage opportunities and produce execution signals.

#### Classes

##### `ArbitrageEngine`
- Runs on `ScheduledExecutorService` at a **fixed rate of 100ms**.
- Each tick:
  1. Reads all relevant prices from `PriceCache`.
  2. Passes prices to `RouteCalculator.calculate()`.
  3. If either route is profitable → creates a `Signal` → enqueues it on `SignalQueue`.
- Exposes `start()`, `stop()`, `kill()` lifecycle methods.
- `kill()` is called by `HeartbeatMonitor` — cancels the scheduled task immediately.

##### `RouteCalculator`
- Pure function — no state, no side effects.
- Calculates two routes for a triangle (e.g., BTC/USDT/ETH):
  - **Route A**: USDT → BTC → ETH → USDT
  - **Route B**: USDT → ETH → BTC → USDT
- All math uses `BigDecimal` with `RoundingMode.HALF_UP` and scale of 8.
- Returns a `RouteResult` containing: route direction, expected profit (bps), leg prices, leg quantities.
- Applies exchange fee deduction (Bitget spot fee: 0.1% maker/taker by default).

##### `SignalQueue`
- Thin wrapper around `LinkedBlockingQueue<Signal>`.
- Producer: `ArbitrageEngine.enqueue(signal)`.
- Consumer: `ExecutorThread.take()` (blocking).
- Capacity: unbounded (signals are cheap; stale ones are filtered by the risk layer).
- Provides `size()` for monitoring.

#### Data Contract (outbound)
```java
// Signal record:
record Signal(
    RouteDirection direction,   // ROUTE_A or ROUTE_B
    BigDecimal expectedProfit,  // in basis points
    BigDecimal[] legPrices,     // [leg1Price, leg2Price, leg3Price]
    BigDecimal[] legQuantities, // [leg1Qty, leg2Qty, leg3Qty]
    long detectedAt             // epoch millis
);
```

---

### 2.3 Risk Layer (`com.arb.bitget.risk`)

**Responsibility**: Gate signals before execution. Ensure no signal passes that violates safety constraints.

#### Classes

##### `RiskGate`
- Consumes signals from `SignalQueue`.
- Applies checks in order:
  1. **Staleness check**: `now - signal.detectedAt < MAX_SIGNAL_AGE_MS` (e.g., 500ms).
  2. **Minimum profit check**: `signal.expectedProfit >= MIN_PROFIT_BPS`.
  3. **Position size check**: total exposure ≤ `MAX_POSITION_USDT`.
  4. **Concurrent execution check**: no other trade currently in-flight.
- **On accept**: passes signal to `OrderExecutor`.
- **On reject**: re-enqueues the signal back to `SignalQueue` (with a re-queue counter to prevent infinite loops; drop after N retries).

##### `StartupChecker`
- Runs **once** during boot, **before** the engine starts.
- Queries Bitget REST API for all open orders.
- Cancels any stale orders from a previous crash.
- Logs every cancelled order with orderId and pair.
- Only after `StartupChecker.run()` completes does `Main.java` start the engine.

#### Rejection Flow
```
SignalQueue
    │
    ▼
RiskGate.evaluate(signal)
    │
    ├── ACCEPT → OrderExecutor.execute(signal)
    │
    └── REJECT → signal.incrementRetry()
                   │
                   ├── retries < MAX → SignalQueue.put(signal)  // re-queue
                   └── retries >= MAX → drop + log warning
```

---

### 2.4 Executor Layer (`com.arb.bitget.executor`)

**Responsibility**: Execute approved signals as actual trades (or simulated ones).

#### Interface

```java
public interface OrderExecutor {
    OrderResult executeLeg(String pair, Side side, BigDecimal quantity, BigDecimal price);
}
```

All three implementations are **drop-in replacements** — same interface, same method signatures.

#### Implementations

##### `PaperExecutor`
- Logs the trade to console/file.
- Returns a synthetic `OrderResult` with `FILLED` status.
- Useful for strategy validation without any API calls.
- Simulates fill at the requested price (no slippage simulation).

##### `SandboxExecutor`
- Makes **real HTTP calls** to Bitget's sandbox/testnet endpoints.
- Uses sandbox API credentials.
- Validates the full request/response cycle without risking real funds.
- Same OkHttp client configuration as `LiveExecutor`.

##### `LiveExecutor`
- Production executor — real money, real orders.
- Uses **OkHttp connection pooling** (`ConnectionPool` with keep-alive) for minimum latency.
- Signs requests with HMAC-SHA256 per Bitget API spec.
- Implements retry logic for transient HTTP errors (429, 500, 503) with limited attempts.

##### `AbortHandler`
- **The most critical safety class in the entire bot.**
- Triggered when: leg 1 filled successfully, but leg 2 fails (timeout, API error, insufficient balance, etc.).
- Action: immediately places a **market sell** order to flatten the position acquired in leg 1.
- Does NOT wait, does NOT retry the original leg 2 — it prioritizes capital preservation.
- Logs the abort event at CRITICAL level with full trade context.

#### Execution Flow
```
Signal (approved by RiskGate)
    │
    ▼
OrderExecutor.executeLeg(leg1)
    │
    ├── SUCCESS → OrderExecutor.executeLeg(leg2)
    │                 │
    │                 ├── SUCCESS → OrderExecutor.executeLeg(leg3)
    │                 │                 │
    │                 │                 ├── SUCCESS → log profit, done
    │                 │                 └── FAIL → AbortHandler.abort(leg2Result, leg3Attempt)
    │                 │
    │                 └── FAIL → AbortHandler.abort(leg1Result, leg2Attempt)
    │
    └── FAIL → log failure, no position taken, safe
```

---

## 3. Module Dependency Graph

```
Main.java
    │
    ├── config/AppConfig
    │
    ├── market/
    │     ├── BitgetWebSocketClient ──► PriceCache
    │     ├── HeartbeatMonitor ──► PriceCache, ArbitrageEngine
    │     └── ReconnectHandler ──► BitgetWebSocketClient
    │
    ├── engine/
    │     ├── ArbitrageEngine ──► PriceCache, RouteCalculator, SignalQueue
    │     └── RouteCalculator (stateless)
    │
    ├── risk/
    │     ├── RiskGate ──► SignalQueue, OrderExecutor
    │     └── StartupChecker ──► Bitget REST API
    │
    └── executor/
          ├── OrderExecutor (interface)
          ├── PaperExecutor
          ├── SandboxExecutor ──► OkHttp, Bitget REST API
          ├── LiveExecutor ──► OkHttp, Bitget REST API
          └── AbortHandler ──► OrderExecutor
```

### Dependency Rules
1. **No circular dependencies.** Dependency flow is strictly top-to-bottom (market → engine → risk → executor).
2. **Cross-layer communication** is via `PriceCache` (read) and `SignalQueue` (produce/consume) only.
3. **AbortHandler** depends on `OrderExecutor` — it uses the same executor to place the market-sell.
4. **No layer imports from a layer above it.** The executor layer never imports from the engine layer.

---

## 4. Startup Sequence

```
Main.main(String[] args)
    │
    ├── 1. Parse --mode flag
    ├── 2. Load AppConfig (properties file for the mode)
    ├── 3. Instantiate PriceCache
    ├── 4. Instantiate OrderExecutor (based on --mode)
    ├── 5. Instantiate AbortHandler (wraps OrderExecutor)
    ├── 6. Instantiate SignalQueue
    ├── 7. Instantiate RouteCalculator
    ├── 8. Instantiate RiskGate (references SignalQueue, OrderExecutor)
    ├── 9. Instantiate ArbitrageEngine (references PriceCache, RouteCalculator, SignalQueue)
    ├── 10. Instantiate HeartbeatMonitor (references PriceCache, ArbitrageEngine)
    ├── 11. Instantiate ReconnectHandler
    ├── 12. Instantiate BitgetWebSocketClient (references PriceCache, ReconnectHandler)
    │
    ├── 13. Run StartupChecker.run()          ← blocks until stale orders cleaned
    ├── 14. Connect BitgetWebSocketClient     ← blocks until first price received
    ├── 15. Start HeartbeatMonitor
    ├── 16. Start RiskGate consumer thread
    └── 17. Start ArbitrageEngine             ← 100ms ticks begin
```

---

## 5. Shutdown Sequence

```
Shutdown Hook (SIGINT / SIGTERM)
    │
    ├── 1. Stop ArbitrageEngine (cancel scheduled task)
    ├── 2. Stop HeartbeatMonitor
    ├── 3. Close BitgetWebSocketClient
    ├── 4. Drain SignalQueue (discard remaining signals)
    ├── 5. Wait for in-flight execution to complete (with timeout)
    ├── 6. Shut down ExecutorService pools
    └── 7. Log shutdown complete
```

---

## 6. Error Handling Strategy

| Error Type | Handler | Action |
|---|---|---|
| WebSocket disconnect | `ReconnectHandler` | Exponential backoff reconnect |
| Stale price data (>5s) | `HeartbeatMonitor` | Kill engine immediately |
| Leg 2/3 execution failure | `AbortHandler` | Market-sell to flatten position |
| API rate limit (HTTP 429) | `LiveExecutor` | Retry with backoff (max 3 attempts) |
| API server error (5xx) | `LiveExecutor` | Retry with backoff (max 3 attempts) |
| Invalid signal (stale) | `RiskGate` | Drop signal, log warning |
| Startup stale orders | `StartupChecker` | Cancel all open orders before engine start |
| Unhandled exception | `Thread.UncaughtExceptionHandler` | Log CRITICAL, attempt graceful shutdown |

---

## 7. Monitoring & Observability

| Metric | Source | Log Level |
|---|---|---|
| Price update rate | `PriceCache` | DEBUG |
| Heartbeat status | `HeartbeatMonitor` | INFO / CRITICAL |
| Signals detected per minute | `ArbitrageEngine` | INFO |
| Signals accepted / rejected | `RiskGate` | INFO |
| Trade execution result | `OrderExecutor` | INFO |
| Abort events | `AbortHandler` | CRITICAL |
| Reconnection attempts | `ReconnectHandler` | WARN |
| Queue depth | `SignalQueue` | DEBUG |
