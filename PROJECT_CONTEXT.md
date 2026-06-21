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
| `PriceCache` | `ConcurrentHashMap<String, PriceEntry>` | Market Data Layer | Latest bid/ask prices per trading pair |
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
| Network Monitor Thread | Pings Bitget REST API every 30s, pauses/resumes engine if latency crosses threshold |

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
| `min.profit.bps` | `5` | Minimum net profit in basis points (target take-home margin *above* fees) |
| `max.position.usdt` | `1000` | Maximum position size in USDT |
| `exchange.fee.rate` | `0.001` | Per-trade fee (0.10% default, 0.0008 with BGB) |
| `exchange.fee.legs` | `3` | Number of legs (for total fee calculation, i.e., 3 legs = 0.30% default fee) |
| `triangles` | `SOL/BTC/USDT,SOL/USDC/USDT,...` | Comma-separated triangle definitions |
| `network.max.latency.ms` | `400` | Max acceptable round-trip latency (ms) |
| `network.check.interval.s` | `30` | Interval between runtime latency checks |
| `network.preflight.samples` | `5` | Number of pings during startup preflight |
| `network.runtime.samples` | `3` | Number of pings per runtime check |
| `network.ping.endpoint` | `/api/v2/public/time` | Lightweight endpoint for latency measurement |

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

## 8.5 Target Triangles

The bot targets **altcoin triangles** where opportunity windows last 5–30+ seconds,
avoiding major pairs (BTC/ETH/USDT) where HFT bots dominate with 2–5 second windows.
By generalizing the `Triangle` model, the bot now supports any intermediary coin (BTC, USDC, ETH), unlocking massive liquidity across new pairs.

| Intermediary | Target Triangles |
|---|---|
| **BTC** | SOL, BGB, ETH |
| **USDC** | SOL, XRP, ADA, DOGE, LINK, BCH, AVAX, LTC, BGB, BNB, UNI, AAVE, SUI, PEPE |
| **ETH** | TRX, BGB |

Triangles are configurable in `config.properties` so new ones can be added without code changes.

---

## 8.6 Latency Budget 

| Phase | Latency |
|---|---|
| WebSocket detect | ~0ms (streaming) |
| Java signal calculation | ~0.1ms (JIT compiled) |
| REST leg 1 (round-trip) | ~150ms |
| Fill confirmation wait | ~50–100ms |
| REST leg 2 (round-trip) | ~150ms |
| Fill confirmation wait | ~50–100ms |
| REST leg 3 (round-trip) | ~150ms |
| Fill confirmation wait | ~50–100ms |
| **Total (best case)** | **~600ms** |
| **Total (average case)** | **~900ms** |
| **Total (with connection pooling)** | **~650ms** |

> The floor is set by network physics, not code. Connection pooling is the biggest
> free optimization (~180ms saved).

## 9. Safety Invariants

These must **never** be violated (and are continuously verified by the `Safety Net Test` suite):

1. **No stale data trading** — If `HeartbeatMonitor` detects no price update for 5s, the engine is killed immediately.
2. **No unhedged positions** — If leg 2 fails after leg 1 fills, `AbortHandler` fires an immediate market-sell.
3. **Clean startup** — `StartupChecker` cancels all stale orders before the engine starts.
4. **Rejected signals are re-queued, never dropped** — A risk rejection is temporary; the condition may clear on the next tick.
5. **BigDecimal everywhere** — No `double` or `float` for any financial calculation.
6. **No high-latency trading** — If average round-trip to Bitget exceeds `network.max.latency.ms`, the engine is paused until latency recovers. At startup, the bot refuses to run at all.

---

## 10. Directory Structure

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
│                       │   ├── RouteResult.java
│                       │   └── SignalQueue.java
│                       ├── risk/
│                       │   ├── RiskGate.java
│                       │   ├── StartupChecker.java
│                       │   └── NetworkChecker.java
│                       ├── executor/
│                       │   ├── OrderExecutor.java
│                       │   ├── TradeExecutionService.java
│                       │   ├── BitgetApiClient.java
│                       │   ├── PaperExecutor.java
│                       │   ├── SandboxExecutor.java
│                       │   ├── LiveExecutor.java
│                       │   └── AbortHandler.java
│                       └── model/
│                           ├── Signal.java
│                           ├── OrderResult.java
│                           ├── TradingPair.java
│                           ├── RouteDirection.java
│                           ├── Side.java
│                           ├── PriceEntry.java
│                           └── Triangle.java
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
