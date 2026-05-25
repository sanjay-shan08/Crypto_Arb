# CODING_RULES.md
> Naming conventions, patterns, formatting rules, and non-negotiable standards.
> Every contributor and AI agent must follow these rules. No exceptions.
> Last updated: 2025-05-25

---

## 1. Language & Version

| Rule | Standard |
|---|---|
| Language | Java 21+ |
| Source encoding | UTF-8 |
| Line endings | LF (Unix-style) |
| Max line length | 120 characters |
| Indentation | 4 spaces (no tabs) |

---

## 2. Naming Conventions

### Packages
```
com.arb.bitget.<layer>
```
- All lowercase, no underscores.
- Layers: `market`, `engine`, `risk`, `executor`, `model`, `config`.

### Classes
| Type | Convention | Example |
|---|---|---|
| Regular class | `PascalCase` | `ArbitrageEngine` |
| Interface | `PascalCase` (no `I` prefix) | `OrderExecutor` |
| Implementation | `PascalCase` with descriptive prefix | `LiveExecutor`, `PaperExecutor` |
| Record | `PascalCase` | `Signal`, `OrderResult` |
| Enum | `PascalCase` | `RouteDirection`, `Side` |
| Exception | `PascalCase` + `Exception` suffix | `AbortException` |

### Methods
| Type | Convention | Example |
|---|---|---|
| Regular method | `camelCase`, verb-first | `calculateRoute()`, `placeOrder()` |
| Boolean method | `is`/`has`/`can` prefix | `isStale()`, `hasFilled()` |
| Factory method | `of`/`from`/`create` prefix | `Signal.of(...)`, `AppConfig.from(path)` |
| Lifecycle method | `start`/`stop`/`kill`/`init` | `engine.start()`, `monitor.kill()` |

### Variables
| Type | Convention | Example |
|---|---|---|
| Local variable | `camelCase` | `bestBid`, `signalCount` |
| Instance field | `camelCase` (no prefix) | `priceCache`, `signalQueue` |
| Constant | `UPPER_SNAKE_CASE` | `MAX_RETRIES`, `TICK_INTERVAL_MS` |
| Thread name | descriptive, hyphenated | `"arb-engine"`, `"heartbeat-monitor"` |

### Files
| Type | Convention | Example |
|---|---|---|
| Java source | `PascalCase.java` | `ArbitrageEngine.java` |
| Config file | `kebab-case.properties` | `application-live.properties` |
| Documentation | `UPPER_SNAKE_CASE.md` | `ARCHITECTURE.md` |

---

## 3. Code Structure Rules

### Class Organization (top to bottom)
```java
public class ClassName {
    // 1. Constants (static final)
    // 2. Static fields
    // 3. Instance fields
    // 4. Constructor(s)
    // 5. Public methods
    // 6. Package-private methods
    // 7. Private methods
    // 8. Inner classes / records / enums
}
```

### Constructor Rules
- **All dependencies injected via constructor.** No setter injection. No field injection.
- Constructor parameters → assigned to `final` fields.
- Constructors do NOT start threads, open connections, or perform I/O.
- Separate `start()` / `init()` methods for lifecycle actions.

```java
// ✅ CORRECT
public class ArbitrageEngine {
    private final PriceCache priceCache;
    private final RouteCalculator calculator;
    private final SignalQueue signalQueue;

    public ArbitrageEngine(PriceCache priceCache, RouteCalculator calculator, SignalQueue signalQueue) {
        this.priceCache = priceCache;
        this.calculator = calculator;
        this.signalQueue = signalQueue;
    }

    public void start() { /* begin ticking */ }
}

// ❌ WRONG — constructor does work
public class ArbitrageEngine {
    public ArbitrageEngine(PriceCache priceCache) {
        this.priceCache = priceCache;
        this.scheduler.scheduleAtFixedRate(...); // NO — move to start()
    }
}
```

### Field Rules
- All injected dependencies are `private final`.
- Mutable state fields must be thread-safe (`Atomic*`, `volatile`, or guarded by synchronization).
- No public fields. Ever.

---

## 4. Financial Math Rules

> **These rules are non-negotiable. Violating them is a critical bug.**

| Rule | Enforcement |
|---|---|
| **All prices, quantities, and profits use `BigDecimal`** | No `double`, no `float` for money |
| **Always specify `RoundingMode`** | Default: `RoundingMode.HALF_UP` |
| **Always specify scale** | Default: scale of 8 for crypto |
| **Use `compareTo()` for comparisons** | Never use `equals()` for BigDecimal value comparison |
| **Create from `String`, not `double`** | `new BigDecimal("0.001")`, NOT `new BigDecimal(0.001)` |

```java
// ✅ CORRECT
BigDecimal fee = amount.multiply(FEE_RATE).setScale(8, RoundingMode.HALF_UP);
if (profit.compareTo(MIN_PROFIT) > 0) { ... }
BigDecimal price = new BigDecimal("50000.00");

// ❌ WRONG
double fee = amount * 0.001;
if (profit.equals(BigDecimal.ZERO)) { ... }
BigDecimal price = new BigDecimal(50000.00);
```

---

## 5. Concurrency Rules

| Rule | Detail |
|---|---|
| **Shared state must be thread-safe** | Use `ConcurrentHashMap`, `AtomicReference`, `volatile`, or `synchronized` |
| **No raw `Thread` creation** | Use `ExecutorService` or `ScheduledExecutorService` |
| **Name all threads** | Use `ThreadFactory` with descriptive names |
| **No busy-wait loops** | Use `BlockingQueue.take()`, `CountDownLatch`, or `CompletableFuture` |
| **Document thread ownership** | Every class javadoc states which thread(s) call its methods |

```java
// ✅ CORRECT — named thread factory
ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "signal-executor");
    t.setDaemon(true);
    return t;
});

// ❌ WRONG — unnamed thread
new Thread(this::processSignals).start();
```

---

## 6. Error Handling Rules

### General
- **Never swallow exceptions.** Every `catch` block must log or re-throw.
- **Never catch `Throwable`** unless you're in a top-level thread handler.
- Use specific exception types. Avoid catching raw `Exception` unless at thread boundaries.

### Logging on Catch
```java
// ✅ CORRECT
try {
    executor.executeLeg(signal);
} catch (ApiException e) {
    log.error("Leg execution failed: pair={}, side={}", pair, side, e);
    abortHandler.abort(legResult, signal);
}

// ❌ WRONG — swallowed
try {
    executor.executeLeg(signal);
} catch (Exception e) {
    // do nothing
}
```

### Critical vs. Recoverable

| Severity | Examples | Action |
|---|---|---|
| CRITICAL | Abort triggered, heartbeat timeout, unhandled exception | Log at ERROR/CRITICAL, take immediate safety action |
| WARN | Reconnection attempt, signal rejected, API rate limit | Log at WARN, continue operation |
| INFO | Trade executed, engine started/stopped | Log at INFO |
| DEBUG | Price update received, queue depth | Log at DEBUG |

---

## 7. Logging Rules

| Rule | Standard |
|---|---|
| Framework | SLF4J facade + Logback implementation |
| Logger declaration | `private static final Logger log = LoggerFactory.getLogger(ClassName.class);` |
| Parameterized messages | Use `{}` placeholders, NOT string concatenation |
| No `System.out.println` | Ever. Use the logger. |
| Log at method boundaries | Entry/exit of critical operations at DEBUG level |

```java
// ✅ CORRECT
log.info("Trade executed: pair={}, side={}, qty={}, price={}", pair, side, qty, price);

// ❌ WRONG
System.out.println("Trade executed: " + pair + " " + side);
log.info("Trade executed: " + pair + " " + side);  // string concat
```

---

## 8. API Client Rules

| Rule | Detail |
|---|---|
| **One `OkHttpClient` instance** per executor | Reuse connection pool |
| **Set timeouts explicitly** | Connect: 5s, Read: 10s, Write: 10s |
| **Close response bodies** | Always use try-with-resources or `.close()` |
| **Sign requests correctly** | HMAC-SHA256 per Bitget API v2 spec |
| **Handle rate limits (HTTP 429)** | Backoff + retry (max 3) |
| **Parse errors defensively** | Always check HTTP status before parsing body |

---

## 9. Testing Rules

| Rule | Standard |
|---|---|
| Framework | JUnit 5 + Mockito |
| Test class naming | `ClassNameTest.java` |
| Test method naming | `methodName_condition_expectedResult()` |
| One assertion per concept | Multiple asserts OK if testing one logical concept |
| Mock external dependencies | All API calls mocked in unit tests |
| No test interdependence | Each test is independent and idempotent |

```java
// ✅ CORRECT
@Test
void calculateRoute_profitableSpread_returnsPositiveProfit() {
    // arrange
    BigDecimal btcPrice = new BigDecimal("50000.00");
    // act
    RouteResult result = calculator.calculate(btcPrice, ethPrice, ...);
    // assert
    assertTrue(result.profit().compareTo(BigDecimal.ZERO) > 0);
}
```

---

## 10. Git & Version Control

| Rule | Standard |
|---|---|
| Branch naming | `feature/<name>`, `fix/<name>`, `refactor/<name>` |
| Commit message format | `<type>: <short description>` |
| Commit types | `feat`, `fix`, `refactor`, `test`, `docs`, `chore` |
| One logical change per commit | Don't mix features with refactors |
| Never commit secrets | API keys, passphrases go in `.env` or env vars, never in source |

```
feat: add RouteCalculator with BigDecimal math for both routes
fix: prevent signal re-queue infinite loop with max retry counter
refactor: extract AbortHandler from LiveExecutor
test: add unit tests for RiskGate rejection logic
docs: update ARCHITECTURE.md with shutdown sequence
```

---

## 11. File Headers

Every Java source file starts with a package declaration and a brief class-level Javadoc:

```java
package com.arb.bitget.engine;

/**
 * Fires every 100ms, reads prices from PriceCache, calculates arbitrage
 * routes, and enqueues profitable signals to SignalQueue.
 *
 * <p>Thread safety: all public methods are called from the engine scheduler thread.
 * The kill() method may be called from the HeartbeatMonitor thread.
 */
public class ArbitrageEngine {
    ...
}
```

---

## 12. Banned Patterns

| ❌ Banned | ✅ Use Instead |
|---|---|
| `double` / `float` for money | `BigDecimal` |
| `new BigDecimal(double)` | `new BigDecimal("string")` |
| `System.out.println` | `log.info(...)` |
| `Thread.sleep()` in production code | `ScheduledExecutorService` |
| Raw `new Thread(...)` | `ExecutorService` with named threads |
| Setter injection | Constructor injection |
| `null` return for errors | Throw specific exception or return `Optional` |
| Wildcard imports `*` | Explicit imports |
| Magic numbers | Named constants |
| `catch (Exception e) {}` | Always log or re-throw |
| `BigDecimal.equals()` for comparison | `BigDecimal.compareTo()` |
