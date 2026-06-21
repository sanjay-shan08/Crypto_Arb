# Bitget Triangular Arbitrage Bot

A high-performance, failsafe Java-based trading bot designed to detect and execute triangular arbitrage opportunities on the Bitget spot market. 

This bot is specifically optimized to target **altcoin triangles** (e.g., SOL, XRP, DOGE, TRX, BGB) where opportunity windows last 5–30+ seconds, avoiding highly competitive major pairs (BTC/ETH).

---

## 🚀 Key Features

* **Capital Preservation First**: Built with aggressive risk management. The `AbortHandler` immediately liquidates positions if a leg fails, preventing unhedged exposure.
* **Network Quality Gate**: Refuses to trade if your local internet latency to Bitget Singapore exceeds 400ms (`NetworkChecker`).
* **Stale Data Prevention**: Kills the engine instantly if WebSocket price updates pause for >5 seconds (`HeartbeatMonitor`).
* **Multi-Triangle Engine**: Scans multiple configurable altcoin triangles concurrently.
* **Latency Optimized**: Uses explicit OkHttp `ConnectionPool` configurations to save ~180ms per triangular execution.
* **Precision Math**: Enforces strict `BigDecimal` math (no `float` or `double`) to prevent exchange rejection due to rounding errors.
* **Fee-Aware Profit Calculation**: Dynamically deducts exchange fees (including BGB discount) before signal generation — never executes a trade where fees would wipe the spread.

---

## 📋 System Requirements

* **Runtime**: Java 21+ (LTS)
* **Build Tool**: Maven 3.9+
* **Network**: Wired Ethernet connection (no Wi-Fi) for minimal packet jitter.
* **Bitget Account**:
  * API Key with `Read` and `Trade` permissions (**Withdrawal permissions MUST BE DISABLED**).
  * IP Whitelisted API Key.
  * **BGB Tokens Held**: Crucial for fee reduction (from 0.10% to 0.08%), which significantly boosts arbitrage profitability.

---

## 🏗️ Architecture

The bot uses a **four-layer pipeline** with strict unidirectional data flow:

```
Market Data Layer  →  Engine Layer  →  Risk Layer  →  Executor Layer
(WebSocket + Cache)   (100ms tick)     (Gate/Filter)   (Order Execution)
```

| Layer | Package | Responsibility |
|---|---|---|
| **Market Data** | `com.arb.bitget.market` | WebSocket feed, PriceCache, HeartbeatMonitor, ReconnectHandler |
| **Engine** | `com.arb.bitget.engine` | ArbitrageEngine (100ms tick), RouteCalculator, SignalQueue |
| **Risk** | `com.arb.bitget.risk` | RiskGate, StartupChecker, NetworkChecker |
| **Executor** | `com.arb.bitget.executor` | PaperExecutor, SandboxExecutor, LiveExecutor, AbortHandler |

> For full architectural details, see [ARCHITECTURE.md](./ARCHITECTURE.md).

---

## ⚙️ Operational Modes

The bot operates in three distinct modes, controlled via a single `--mode` flag at startup. The business logic remains identical across all modes.

| Mode | Flag | API Calls | Real Money | Use Case |
|---|---|---|---|---|
| **Paper** | `--mode paper` | ❌ No | ❌ No | Strategy validation with simulated ~200ms latency |
| **Sandbox** | `--mode sandbox` | ✅ Testnet | ❌ No | Full request/response cycle with fake funds |
| **Live** | `--mode live` | ✅ Production | ✅ Yes | Real trading with real money |

---

## 🔧 Getting Started

### 1. Prerequisites

```bash
# Verify Java 21+ is installed
java --version

# Verify Maven 3.9+ is installed
mvn --version
```

### 2. Clone the Repository

```bash
git clone https://github.com/sanjay-shan08/Crypto_Arb.git
cd Bitget_Arb
```

### 3. Configure Credentials

Set your Bitget API credentials as environment variables (**never hardcode them**):

```bash
# Linux / macOS
export BITGET_API_KEY="your_api_key"
export BITGET_API_SECRET="your_api_secret"
export BITGET_API_PASSPHRASE="your_api_passphrase"

# Windows (PowerShell)
$env:BITGET_API_KEY="your_api_key"
$env:BITGET_API_SECRET="your_api_secret"
$env:BITGET_API_PASSPHRASE="your_api_passphrase"
```

> ⚠️ **Security**: The `.gitignore` is configured to exclude credential files (`config/application-live.properties`, `config/application-sandbox.properties`) and all `.env` files. **Never commit API keys to version control.**

### 4. Build

```bash
mvn clean package
```

### 5. Run

```bash
# Paper mode (safe — no API calls)
java -jar target/bitget-arb.jar --mode paper

# Sandbox mode (testnet)
java -jar target/bitget-arb.jar --mode sandbox

# Live mode (REAL MONEY)
java -jar target/bitget-arb.jar --mode live
```

---

## 🎯 Target Triangles

The bot targets **altcoin triangles** where opportunity windows last 5–30+ seconds, avoiding major pairs (BTC/ETH/USDT) where HFT bots dominate with 2–5 second windows.
The underlying routing math natively supports **any intermediary coin** (e.g., BTC, USDC, ETH) as long as it acts as the quote for the altcoin and the base against USDT.

| Intermediary | Supported Altcoins |
|---|---|
| **BTC** | SOL, BGB, ETH |
| **USDC** | SOL, XRP, ADA, DOGE, LINK, BCH, AVAX, LTC, BGB, BNB, UNI, AAVE, SUI, PEPE |
| **ETH** | TRX, BGB |

Triangles are configurable in `config/application-*.properties` — new ones can be added without code changes.

---

## 🛡️ Safety Invariants

These rules are **never** violated, and are continuously verified by a comprehensive JUnit `Safety Net Test` suite:

1. **No stale data trading** — Engine killed if no price update for 5 seconds.
2. **No unhedged positions** — `AbortHandler` fires immediate market-sell if leg 2/3 fails.
3. **Clean startup** — All stale orders cancelled before engine starts.
4. **No high-latency trading** — Engine paused if round-trip latency exceeds 400ms.
5. **BigDecimal everywhere** — No `double` or `float` for financial math.
6. **Rejected signals re-queued** — Never dropped (with retry limit to prevent infinite loops).

---

## 📁 Project Structure

```
Bitget_Arb/
├── pom.xml                              # Maven build config
├── .gitignore                           # Credential & build artifact exclusions
├── README.md                            # This file
├── ARCHITECTURE.md                      # Layer breakdowns, dependency graphs
├── PROJECT_CONTEXT.md                   # Threading models, config properties
├── CODING_RULES.md                      # Strict project standards
├── FEATURE_LOG.md                       # Chronological decision log
├── config/
│   ├── application-paper.properties     # Paper mode config (no credentials)
│   ├── application-sandbox.properties   # Sandbox mode config (⚠️ gitignored)
│   └── application-live.properties      # Live mode config (⚠️ gitignored)
└── src/
    ├── main/
    │   ├── java/com/arb/bitget/
    │   │   ├── Main.java                # Entry point, wires all dependencies
    │   │   ├── config/                  # AppConfig
    │   │   ├── market/                  # WebSocket, PriceCache, Heartbeat
    │   │   ├── engine/                  # ArbitrageEngine, RouteCalculator
    │   │   ├── risk/                    # RiskGate, NetworkChecker
    │   │   ├── executor/                # Order executors, AbortHandler
    │   │   └── model/                   # Signal, OrderResult, TradingPair
    │   └── resources/
    │       └── logback.xml              # Logging configuration
    └── test/                            # JUnit 5 + Mockito tests
```

---

## ⚡ Latency Budget 

| Phase | Latency |
|---|---|
| WebSocket detect | ~0ms (streaming) |
| Signal calculation | ~0.1ms (JIT compiled) |
| REST leg round-trip (×3) | ~150ms each |
| Fill confirmation (×3) | ~50–100ms each |
| **Total (with connection pooling)** | **~650ms** |

> Connection pooling is the biggest free optimization — saves ~180ms by reusing TCP connections.

---

## 📚 Documentation

| Document | Description |
|---|---|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Layer breakdowns, dependency graphs, startup/shutdown sequences |
| [PROJECT_CONTEXT.md](./PROJECT_CONTEXT.md) | Threading models, configuration properties, safety invariants |
| [CODING_RULES.md](./CODING_RULES.md) | Strict project standards, banned patterns, formatting rules |
| [FEATURE_LOG.md](./FEATURE_LOG.md) | Chronological log of architectural decisions and features |

---

## ⚠️ Disclaimer

This bot trades real cryptocurrency. Use at your own risk. The author is not responsible for any financial loss. Always start with **paper mode** to validate your strategy before risking real funds.

NOTE : The bot is under the testing stage right now and observed great performance in the paper mode. It is expected to perform well in the sandbox mode as well. The live mode is yet to be tested. **DO NOT USE BOT UNTIL ALL STAGES OF TESTING ARE OVER!!!**

---

## 📄 License

This project is private and not licensed for public use.

<div align="center">
  <sub><i>Crafted by <a href="https://www.linkedin.com/in/sanjay-shan">Sanjay Shan</a></i></sub>
</div>