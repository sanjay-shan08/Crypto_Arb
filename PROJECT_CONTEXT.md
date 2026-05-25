# PROJECT_CONTEXT.md
> Project memory — stack, flows, dependencies, operational modes.
> Last updated: 2025-05-25

---

## 1. Project Identity

| Field | Value |
|---|---|
| **Name** | Bitget Triangular Arbitrage Bot |
| **Language** | Java 21+ |
| **Build Tool** | Maven (pom.xml) |
| **Purpose** | Detect and execute triangular arbitrage opportunities across Bitget spot markets in real time |
| **Repo Root** | `Bitget_Arb/` |

---

## 2. Technology Stack

### Core Runtime
| Component | Technology | Why |
|---|---|---|
| Language | Java 21 (LTS) | Type safety, strong concurrency primitives, BigDecimal precision |
| Build | Maven 3.9+ | Standard dependency management, reproducible builds |
| HTTP Client | OkHttp 4.x | Connection pooling, HTTP/2, low-latency order execution |
| WebSocket | OkHttp WebSocket | Same library, unified connection management |
| JSON | Gson or Jackson | Parsing Bitget API responses and WebSocket messages |
| Logging | SLF4J + Logback | Structured logging with configurable levels |
| Scheduling | `ScheduledExecutorService` | 100ms tick engine, no external framework needed |
| Concurrency | `java.util.concurrent` | `LinkedBlockingQueue`, `ConcurrentHashMap`, `AtomicReference` |
| Math | `BigDecimal` | Zero floating-point errors on financial calculations |

### External Dependencies
| Dependency | Purpose |
|---|---|
| Bitget REST API v2 | Order placement, account queries, order cancellation |
| Bitget WebSocket API | Real-time orderbook/ticker price feeds |
| OkHttp | HTTP + WebSocket transport |
| Gson / Jackson | JSON serialization/deserialization |
| SLF4J + Logback | Logging facade + implementation |
| JUnit 5 + Mockito | Unit and integration testing |

### No External Frameworks
- No Spring, no Guice, no Dagger.
- Dependency injection is **manual** — `Main.java` wires everything based on `--mode`.
- This keeps the bot lightweight, fast to start, and easy to reason about.

---

## 3. Operational Modes

The bot supports three modes, controlled by a single `--mode` flag in `Main.java`:

| Mode | `--mode` value | Executor | API Calls? | Real Money? |
|---|---|---|---|---|
| Paper | `paper` | `PaperExecutor` | ❌ No | ❌ No |
| Sandbox | `sandbox` | `SandboxExecutor` | ✅ Yes (testnet) | ❌ No |
| Live | `live` | `LiveExecutor` | ✅ Yes (production) | ✅ Yes |

### Mode Switching Rule
> **Only `Main.java` changes behavior based on mode.**
> Every other class receives its dependencies through constructor injection and is completely mode-agnostic.

---

## 4. Data Flow (End to End)

```
Bitget WebSocket API
        │
        ▼
BitgetWebSocketClient ──► PriceCache (ConcurrentHashMap)
        │                       │
   HeartbeatMonitor             │
   ReconnectHandler             │
                                ▼
                        ArbitrageEngine (100ms tick)
                                │
                        RouteCalculator (BigDecimal math)
                                │
                         profitable? ──► SignalQueue (LinkedBlockingQueue)
                                              │
                                        Risk Layer (gate)
                                         │          │
                                      reject      accept
                                     (re-queue)      │
                                              OrderExecutor.execute()
                                                │          │
                                            leg 1 OK    leg 2 FAIL
                                                │          │
                                              leg 2    AbortHandler
                                                │     (market-sell)
                                              done        │
                                                        flatten
```

---

## 5. Key Data Structures

| Structure | Type | Owner | Purpose |
|---|---|---|---|
| `PriceCache` | `ConcurrentHashMap<String, BigDecimal>` | Market Data Layer | Latest prices per trading pair |
| `SignalQueue` | `LinkedBlockingQueue<Signal>` | Engine Layer | Decouples detection from execution |
| `Signal` | Record/POJO | Engine Layer | Contains route, prices, expected profit, timestamp |
| `OrderResult` | Record/POJO | Executor Layer | Contains orderId, status, filled quantity, price |

---

## 6. Threading Model

| Thread | Responsibility |
|---|---|
| WebSocket Read Thread | Receives price updates, writes to `PriceCache` |
| Heartbeat Thread | Monitors last-update timestamp, kills engine if stale > 5s |
| Engine Thread | `ScheduledExecutorService` — fires every 100ms, reads `PriceCache`, calculates routes, enqueues signals |
| Executor Thread | Blocks on `SignalQueue.take()`, processes signals sequentially |
| Reconnect Thread | Handles exponential backoff reconnection logic |

---

## 7. Configuration

Configuration is loaded from environment variables and/or a `config.properties` file:

| Property | Example | Description |
|---|---|---|
| `bitget.api.key` | `bg_xxx...` | API key (live/sandbox) |
| `bitget.api.secret` | `xxx...` | API secret |
| `bitget.api.passphrase` | `xxx...` | API passphrase |
| `bitget.ws.url` | `wss://ws.bitget.com/v2/ws/public` | WebSocket endpoint |
| `bitget.rest.url` | `https://api.bitget.com` | REST API base URL |
| `engine.tick.ms` | `100` | Engine tick interval |
| `heartbeat.timeout.ms` | `5000` | Stale data timeout |
| `min.profit.bps` | `5` | Minimum profit in basis points |
| `max.position.usdt` | `1000` | Maximum position size in USDT |

---

## 8. API Endpoints Used

### REST API (v2)
| Endpoint | Method | Purpose |
|---|---|---|
| `/api/v2/spot/trade/place-order` | POST | Place a spot order |
| `/api/v2/spot/trade/cancel-order` | POST | Cancel an open order |
| `/api/v2/spot/trade/open-orders` | GET | Query open orders (StartupChecker) |
| `/api/v2/spot/account/assets` | GET | Query account balances |

### WebSocket
| Channel | Purpose |
|---|---|
| `ticker` | Real-time best bid/ask prices |
| `books5` / `books15` | Orderbook snapshots (if depth needed) |

---

## 9. Safety Invariants

These must **never** be violated:

1. **No stale data trading** — If `HeartbeatMonitor` detects no price update for 5s, the engine is killed immediately.
2. **No unhedged positions** — If leg 2 fails after leg 1 fills, `AbortHandler` fires an immediate market-sell.
3. **Clean startup** — `StartupChecker` cancels all stale orders before the engine starts.
4. **Rejected signals are re-queued, never dropped** — A risk rejection is temporary; the condition may clear on the next tick.
5. **BigDecimal everywhere** — No `double` or `float` for any financial calculation.

---

## 10. Directory Structure (Planned)

```
Bitget_Arb/
├── pom.xml
├── PROJECT_CONTEXT.md
├── ARCHITECTURE.md
├── CODING_RULES.md
├── FEATURE_LOG.md
├── src/
│   └── main/
│       └── java/
│           └── com/
│               └── arb/
│                   └── bitget/
│                       ├── Main.java
│                       ├── config/
│                       │   └── AppConfig.java
│                       ├── market/
│                       │   ├── BitgetWebSocketClient.java
│                       │   ├── HeartbeatMonitor.java
│                       │   ├── ReconnectHandler.java
│                       │   └── PriceCache.java
│                       ├── engine/
│                       │   ├── ArbitrageEngine.java
│                       │   ├── RouteCalculator.java
│                       │   └── SignalQueue.java
│                       ├── risk/
│                       │   ├── RiskGate.java
│                       │   └── StartupChecker.java
│                       ├── executor/
│                       │   ├── OrderExecutor.java
│                       │   ├── PaperExecutor.java
│                       │   ├── SandboxExecutor.java
│                       │   ├── LiveExecutor.java
│                       │   └── AbortHandler.java
│                       └── model/
│                           ├── Signal.java
│                           ├── OrderResult.java
│                           └── TradingPair.java
│   └── test/
│       └── java/
│           └── com/
│               └── arb/
│                   └── bitget/
│                       ├── engine/
│                       ├── risk/
│                       └── executor/
└── config/
    ├── application-paper.properties
    ├── application-sandbox.properties
    └── application-live.properties
```
