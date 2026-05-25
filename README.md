# Bitget Triangular Arbitrage Bot

A high-performance, failsafe Java-based trading bot designed to detect and execute triangular arbitrage opportunities on the Bitget spot market. 

This bot is specifically optimized to target **altcoin triangles** (e.g., SOL, XRP, DOGE, TRX, BGB) where opportunity windows last 5–30+ seconds, avoiding highly competitive major pairs (BTC/ETH).

## 🚀 Key Features

* **Capital Preservation First**: Built with aggressive risk management. The `AbortHandler` immediately liquidates positions if a leg fails, preventing unhedged exposure.
* **Network Quality Gate**: Refuses to trade if your local internet latency to Bitget Singapore exceeds 400ms (`NetworkChecker`).
* **Stale Data Prevention**: Kills the engine instantly if WebSocket price updates pause for >5 seconds (`HeartbeatMonitor`).
* **Multi-Triangle Engine**: Scans multiple configurable altcoin triangles concurrently.
* **Latency Optimized**: Uses explicit OkHttp `ConnectionPool` configurations to save ~180ms per triangular execution.
* **Precision Math**: Enforces strict `BigDecimal` math (no `float` or `double`) to prevent exchange rejection due to rounding errors.

## 📋 System Requirements

* **Runtime**: Java 21+ (LTS)
* **Build Tool**: Maven 3.9+
* **Network**: Wired Ethernet connection (no Wi-Fi) for minimal packet jitter.
* **Bitget Account**:
  * API Key with `Read` and `Trade` permissions (**Withdrawal permissions MUST BE DISABLED**).
  * IP Whitelisted API Key.
  * **BGB Tokens Held**: Crucial for fee reduction (from 0.10% to 0.08%), which significantly boosts arbitrage profitability.

## ⚙️ Operational Modes

The bot operates in three distinct modes, controlled via a single `--mode` flag at startup. The business logic remains identical across all modes.

1. **Paper Mode** (`--mode paper`): No real API calls. Simulates ~200ms network latency per leg to produce realistic timing and P&L logs.
2. **Sandbox Mode** (`--mode sandbox`): Connects to Bitget's Testnet/Sandbox environment. Makes real HTTP calls but uses fake funds.
3. **Live Mode** (`--mode live`): Connects to production Bitget endpoints. Trades real money.

## 📚 Documentation

For deep-dive technical details, please refer to the core project documents:

* [ARCHITECTURE.md](./ARCHITECTURE.md) - Layer breakdowns, dependency graphs, and startup/shutdown sequences.
* [PROJECT_CONTEXT.md](./PROJECT_CONTEXT.md) - Threading models, configuration properties, and safety invariants.
* [CODING_RULES.md](./CODING_RULES.md) - Strict project standards, banned patterns, and formatting rules.
* [FEATURE_LOG.md](./FEATURE_LOG.md) - Chronological log of architectural decisions and features.

## 🛠️ Getting Started

*(Note: The project is currently in the architectural design phase. Development will follow the directory structures defined in `PROJECT_CONTEXT.md`)*
