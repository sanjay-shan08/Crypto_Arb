package com.arb.bitget.config;

import com.arb.bitget.model.Triangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Loads and exposes all bot configuration from a properties file.
 * Supports environment variable overrides for sensitive credentials.
 *
 * <p>Thread safety: all fields are final and set in the constructor.
 * Safe for concurrent reads after construction.
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final String mode;
    private final String apiKey;
    private final String apiSecret;
    private final String apiPassphrase;
    private final String wsUrl;
    private final String restUrl;
    private final long engineTickMs;
    private final long heartbeatTimeoutMs;
    private final BigDecimal minProfitBps;
    private final BigDecimal maxPositionUsdt;
    private final BigDecimal feeRate;
    private final int feeLegCount;
    private final List<Triangle> triangles;
    private final long maxLatencyMs;
    private final long networkCheckIntervalS;
    private final int preflightSamples;
    private final int runtimeSamples;
    private final String pingEndpoint;
    
    // Safety Net Configs
    private final BigDecimal maxDailyLossUsdt;
    private final int maxConsecutiveAborts;
    private final boolean enableDepthCheck;

    private AppConfig(Properties props, String mode) {
        this.mode = mode;
        this.apiKey = props.getProperty("bitget.api.key", "").trim();
        this.apiSecret = props.getProperty("bitget.api.secret", "").trim();
        this.apiPassphrase = props.getProperty("bitget.api.passphrase", "").trim();
        this.wsUrl = props.getProperty("bitget.ws.url", "wss://ws.bitget.com/v2/ws/public");
        this.restUrl = props.getProperty("bitget.rest.url", "https://api.bitget.com");
        this.engineTickMs = Long.parseLong(props.getProperty("engine.tick.ms", "100"));
        this.heartbeatTimeoutMs = Long.parseLong(props.getProperty("heartbeat.timeout.ms", "5000"));
        this.minProfitBps = new BigDecimal(props.getProperty("min.profit.bps", "5"));
        this.maxPositionUsdt = new BigDecimal(props.getProperty("max.position.usdt", "1000"));
        this.feeRate = new BigDecimal(props.getProperty("exchange.fee.rate", "0.001"));
        this.feeLegCount = Integer.parseInt(props.getProperty("exchange.fee.legs", "3"));
        this.maxLatencyMs = Long.parseLong(props.getProperty("network.max.latency.ms", "400"));
        this.networkCheckIntervalS = Long.parseLong(props.getProperty("network.check.interval.s", "30"));
        this.preflightSamples = Integer.parseInt(props.getProperty("network.preflight.samples", "5"));
        this.runtimeSamples = Integer.parseInt(props.getProperty("network.runtime.samples", "3"));
        this.pingEndpoint = props.getProperty("network.ping.endpoint", "/api/v2/public/time");

        // Safety Net Configs
        this.maxDailyLossUsdt = new BigDecimal(props.getProperty("max.daily.loss.usdt", "50"));
        this.maxConsecutiveAborts = Integer.parseInt(props.getProperty("max.consecutive.aborts", "3"));
        this.enableDepthCheck = Boolean.parseBoolean(props.getProperty("enable.depth.check", "false"));

        String triangleDefs = props.getProperty("triangles", "SOL/BTC/USDT,SOL/USDC/USDT");
        this.triangles = Arrays.stream(triangleDefs.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Triangle::parse)
                .toList();
    }

    /**
     * Loads configuration for the given mode from config/application-{mode}.properties.
     * Environment variables override file values for API credentials.
     *
     * @param mode one of "paper", "sandbox", "live"
     * @return fully loaded AppConfig
     * @throws IOException if the config file cannot be read
     */
    public static AppConfig from(String mode) throws IOException {
        String filename = "application-" + mode + ".properties";
        Path configPath = Path.of("config", filename);
        Properties props = new Properties();

        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                props.load(is);
            }
            log.info("Loaded config from {}", configPath.toAbsolutePath());
        } else {
            log.warn("Config file not found: {} — using defaults", configPath.toAbsolutePath());
        }

        // Environment variables override file values for secrets
        overrideFromEnv(props, "BITGET_API_KEY", "bitget.api.key");
        overrideFromEnv(props, "BITGET_API_SECRET", "bitget.api.secret");
        overrideFromEnv(props, "BITGET_API_PASSPHRASE", "bitget.api.passphrase");

        return new AppConfig(props, mode);
    }

    private static void overrideFromEnv(Properties props, String envKey, String propKey) {
        String value = System.getenv(envKey);
        if (value != null && !value.isEmpty()) {
            props.setProperty(propKey, value);
            log.debug("Config override from env: {}", envKey);
        }
    }

    // --- Getters ---

    public String getMode() {
        return mode;
    }

    public boolean isPaperMode() {
        return "paper".equals(mode);
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public String getApiPassphrase() {
        return apiPassphrase;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public String getRestUrl() {
        return restUrl;
    }

    public long getEngineTickMs() {
        return engineTickMs;
    }

    public long getHeartbeatTimeoutMs() {
        return heartbeatTimeoutMs;
    }

    public BigDecimal getMinProfitBps() {
        return minProfitBps;
    }

    public BigDecimal getMaxPositionUsdt() {
        return maxPositionUsdt;
    }

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public int getFeeLegCount() {
        return feeLegCount;
    }

    public List<Triangle> getTriangles() {
        return triangles;
    }

    public long getMaxLatencyMs() {
        return maxLatencyMs;
    }

    public long getNetworkCheckIntervalS() {
        return networkCheckIntervalS;
    }

    public int getPreflightSamples() {
        return preflightSamples;
    }

    public int getRuntimeSamples() {
        return runtimeSamples;
    }

    public String getPingEndpoint() {
        return pingEndpoint;
    }

    public BigDecimal getMaxDailyLossUsdt() {
        return maxDailyLossUsdt;
    }

    public int getMaxConsecutiveAborts() {
        return maxConsecutiveAborts;
    }

    public boolean isEnableDepthCheck() {
        return enableDepthCheck;
    }
}
