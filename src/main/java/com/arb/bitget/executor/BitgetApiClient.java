package com.arb.bitget.executor;

import com.arb.bitget.config.AppConfig;
import com.arb.bitget.model.OrderResult;
import com.arb.bitget.model.Side;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Shared HTTP utility class for the Bitget REST API.
 * Handles request signing (HMAC-SHA256), retry logic, and response parsing.
 * Used by Live/SandboxExecutor, StartupChecker, and NetworkChecker.
 *
 * <p>Thread safety: the underlying OkHttpClient is thread-safe.
 * This class itself should be called sequentially from the executor thread.
 */
public class BitgetApiClient {

    private static final Logger log = LoggerFactory.getLogger(BitgetApiClient.class);
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json");
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 200;

    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String apiSecret;
    private final String passphrase;
    private final Gson gson;

    public BitgetApiClient(OkHttpClient httpClient, AppConfig config) {
        this.httpClient = httpClient;
        this.baseUrl = config.getRestUrl();
        this.apiKey = config.getApiKey();
        this.apiSecret = config.getApiSecret();
        this.passphrase = config.getApiPassphrase();
        this.gson = new Gson();
    }

    // =========================================================================
    // Public API (no authentication required)
    // =========================================================================

    /**
     * Pings the server time endpoint and returns the round-trip latency in ms.
     * Used by NetworkChecker for latency measurement.
     */
    public long getServerTime() throws Exception {
        String path = "/api/v2/public/time";
        long startMs = System.currentTimeMillis();
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            long rtt = System.currentTimeMillis() - startMs;
            if (!response.isSuccessful()) {
                throw new RuntimeException("Server time request failed: HTTP " + response.code());
            }
            return rtt;
        }
    }

    // =========================================================================
    // Private API (authenticated)
    // =========================================================================

    /**
     * Places a spot order on Bitget.
     *
     * @param symbol    trading pair (e.g. "SOLUSDT")
     * @param side      BUY or SELL
     * @param orderType "limit" or "market"
     * @param size      order quantity in base currency
     * @param price     limit price (null for market orders)
     * @param force     time-in-force: "gtc", "ioc", "fok", "post_only"
     * @return OrderResult with fill status
     */
    public OrderResult placeOrder(String symbol, Side side, String orderType,
                                  BigDecimal size, BigDecimal price, String force) throws Exception {
        String path = "/api/v2/spot/trade/place-order";

        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("side", side.toApiString());
        body.addProperty("orderType", orderType);
        body.addProperty("size", size.toPlainString());
        body.addProperty("force", force);
        if (price != null && "limit".equals(orderType)) {
            body.addProperty("price", price.toPlainString());
        }
        body.addProperty("clientOid", "arb_" + System.currentTimeMillis());

        String bodyStr = gson.toJson(body);

        return executeWithRetry(() -> {
            Request request = buildSignedRequest("POST", path, "", bodyStr);
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                JsonObject json = gson.fromJson(responseBody, JsonObject.class);

                String code = json.has("code") ? json.get("code").getAsString() : "";
                if (!"00000".equals(code)) {
                    String msg = json.has("msg") ? json.get("msg").getAsString() : "unknown";
                    log.error("Order placement failed: code={}, msg={}, symbol={}, side={}",
                            code, msg, symbol, side);
                    return new OrderResult(null, null, OrderResult.Status.FAILED,
                            BigDecimal.ZERO, BigDecimal.ZERO, symbol, side);
                }

                JsonObject data = json.getAsJsonObject("data");
                String orderId = data.has("orderId") ? data.get("orderId").getAsString() : "";
                String clientOid = data.has("clientOid") ? data.get("clientOid").getAsString() : "";

                log.info("Order placed: orderId={}, symbol={}, side={}, type={}, size={}, price={}",
                        orderId, symbol, side, orderType, size, price);

                return new OrderResult(orderId, clientOid, OrderResult.Status.FILLED,
                        size, price != null ? price : BigDecimal.ZERO, symbol, side);
            }
        });
    }

    /**
     * Cancels an open order.
     */
    public boolean cancelOrder(String symbol, String orderId) throws Exception {
        String path = "/api/v2/spot/trade/cancel-order";
        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("orderId", orderId);
        String bodyStr = gson.toJson(body);

        Request request = buildSignedRequest("POST", path, "", bodyStr);
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            String code = json.has("code") ? json.get("code").getAsString() : "";
            if ("00000".equals(code)) {
                log.info("Order cancelled: orderId={}, symbol={}", orderId, symbol);
                return true;
            }
            log.warn("Cancel order failed: code={}, orderId={}, symbol={}", code, orderId, symbol);
            return false;
        }
    }

    /**
     * Queries unfilled (open) orders for a specific symbol.
     * Returns the raw JSON data array.
     */
    public List<JsonObject> getUnfilledOrders(String symbol) throws Exception {
        String path = "/api/v2/spot/trade/unfilled-orders";
        String queryString = symbol != null && !symbol.isEmpty() ? "symbol=" + symbol : "";

        Request request = buildSignedRequest("GET", path, queryString, "");
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            String code = json.has("code") ? json.get("code").getAsString() : "";
            List<JsonObject> orders = new ArrayList<>();
            if ("00000".equals(code) && json.has("data")) {
                JsonArray data = json.getAsJsonArray("data");
                for (int i = 0; i < data.size(); i++) {
                    orders.add(data.get(i).getAsJsonObject());
                }
            }
            return orders;
        }
    }

    // =========================================================================
    // Request Signing (HMAC-SHA256 + Base64)
    // =========================================================================

    /**
     * Builds a signed HTTP request per Bitget API v2 spec.
     *
     * <p>Prehash format:
     * <ul>
     *   <li>No query string: {@code timestamp + METHOD + path + body}</li>
     *   <li>With query string: {@code timestamp + METHOD + path + "?" + queryString + body}</li>
     * </ul>
     */
    private Request buildSignedRequest(String method, String path, String queryString, String body) {
        String timestamp = String.valueOf(System.currentTimeMillis());

        String message;
        if (queryString.isEmpty()) {
            message = timestamp + method + path + body;
        } else {
            message = timestamp + method + path + "?" + queryString + body;
        }

        String signature = sign(message);
        String url = baseUrl + path + (queryString.isEmpty() ? "" : "?" + queryString);

        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("ACCESS-KEY", apiKey)
                .header("ACCESS-SIGN", signature)
                .header("ACCESS-TIMESTAMP", timestamp)
                .header("ACCESS-PASSPHRASE", passphrase)
                .header("Content-Type", "application/json")
                .header("locale", "en-US");

        if ("POST".equals(method)) {
            builder.post(RequestBody.create(body, JSON_MEDIA));
        } else {
            builder.get();
        }

        return builder.build();
    }

    private String sign(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign request", e);
        }
    }

    // =========================================================================
    // Retry Logic
    // =========================================================================

    @FunctionalInterface
    private interface ApiCall<T> {
        T execute() throws Exception;
    }

    /**
     * Executes an API call with retry on transient failures (429, 5xx).
     */
    private <T> T executeWithRetry(ApiCall<T> call) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return call.execute();
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    long delay = RETRY_BASE_DELAY_MS * attempt;
                    log.warn("API call failed (attempt {}/{}), retrying in {}ms: {}",
                            attempt, MAX_RETRIES, delay, e.getMessage());
                    Thread.sleep(delay);
                }
            }
        }
        throw lastException;
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }
}
