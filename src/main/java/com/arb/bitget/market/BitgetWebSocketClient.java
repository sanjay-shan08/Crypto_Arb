package com.arb.bitget.market;

import com.arb.bitget.model.PriceEntry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Connects to Bitget WebSocket API, subscribes to ticker channels,
 * and feeds price updates into PriceCache.
 *
 * <p>Thread safety: onMessage/onOpen/onFailure/onClosed are called
 * from OkHttp's internal WebSocket thread. PriceCache is thread-safe.
 */
public class BitgetWebSocketClient extends WebSocketListener {

    private static final Logger log = LoggerFactory.getLogger(BitgetWebSocketClient.class);
    private static final long PING_INTERVAL_MS = 25_000;

    private final String wsUrl;
    private final List<String> pairs;
    private final PriceCache priceCache;
    private final ReconnectHandler reconnectHandler;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final CountDownLatch firstPriceLatch;
    private final boolean enableDepthCheck;

    private volatile WebSocket webSocket;
    private ScheduledExecutorService pingScheduler;
    private volatile boolean connected = false;
    private volatile boolean firstPriceReceived = false;

    public BitgetWebSocketClient(String wsUrl, List<String> pairs, PriceCache priceCache,
                                 ReconnectHandler reconnectHandler, OkHttpClient httpClient, boolean enableDepthCheck) {
        this.wsUrl = wsUrl;
        this.pairs = pairs;
        this.priceCache = priceCache;
        this.reconnectHandler = reconnectHandler;
        this.httpClient = httpClient;
        this.gson = new Gson();
        this.firstPriceLatch = new CountDownLatch(1);
        this.enableDepthCheck = enableDepthCheck;
    }

    /**
     * Initiates the WebSocket connection.
     */
    public void connect() {
        log.info("Connecting to WebSocket: {}", wsUrl);
        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = httpClient.newWebSocket(request, this);
    }

    /**
     * Blocks until the first price update is received or timeout expires.
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return true if a price was received, false on timeout
     */
    public boolean awaitFirstPrice(long timeoutMs) throws InterruptedException {
        return firstPriceLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onOpen(WebSocket ws, Response response) {
        log.info("WebSocket connected");
        connected = true;
        reconnectHandler.resetAttempts();
        subscribe(ws);
        startPingScheduler(ws);
    }

    private void subscribe(WebSocket ws) {
        JsonObject sub = new JsonObject();
        sub.addProperty("op", "subscribe");
        JsonArray args = new JsonArray();
        for (String pair : pairs) {
            JsonObject arg = new JsonObject();
            arg.addProperty("instType", "SPOT");
            arg.addProperty("channel", enableDepthCheck ? "books5" : "ticker");
            arg.addProperty("instId", pair);
            args.add(arg);
        }
        sub.add("args", args);
        String message = gson.toJson(sub);
        ws.send(message);
        log.info("Subscribed to {} ticker channels", pairs.size());
    }

    private void startPingScheduler(WebSocket ws) {
        stopPingScheduler();
        pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-ping");
            t.setDaemon(true);
            return t;
        });
        pingScheduler.scheduleAtFixedRate(() -> {
            if (connected) {
                ws.send("ping");
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPingScheduler() {
        if (pingScheduler != null && !pingScheduler.isShutdown()) {
            pingScheduler.shutdownNow();
        }
    }

    @Override
    public void onMessage(WebSocket ws, String text) {
        if ("pong".equals(text)) {
            return;
        }

        try {
            JsonObject json = gson.fromJson(text, JsonObject.class);

            // Handle subscription confirmation / error events
            if (json.has("event")) {
                String event = json.get("event").getAsString();
                if ("subscribe".equals(event)) {
                    log.debug("Subscription confirmed: {}", json);
                } else if ("error".equals(event)) {
                    log.error("WebSocket error event: {}", json);
                }
                return;
            }

            // Parse ticker data
            if (!json.has("data")) {
                return;
            }

            JsonArray dataArray = json.getAsJsonArray("data");
            for (JsonElement element : dataArray) {
                JsonObject data = element.getAsJsonObject();
                String instId = data.has("instId") ? data.get("instId").getAsString() : null;
                // Sometimes instId is in 'arg' block, so check there too
                if (instId == null && json.has("arg")) {
                    JsonObject arg = json.getAsJsonObject("arg");
                    instId = arg.has("instId") ? arg.get("instId").getAsString() : null;
                }
                
                if (instId == null) {
                    continue;
                }

                PriceEntry entry;
                
                if (enableDepthCheck && data.has("bids") && data.has("asks")) {
                    JsonArray bidsArr = data.getAsJsonArray("bids");
                    JsonArray asksArr = data.getAsJsonArray("asks");
                    
                    if (bidsArr.size() == 0 || asksArr.size() == 0) continue;

                    BigDecimal[] bids = new BigDecimal[bidsArr.size()];
                    BigDecimal[] bidSizes = new BigDecimal[bidsArr.size()];
                    for (int i = 0; i < bidsArr.size(); i++) {
                        JsonArray level = bidsArr.get(i).getAsJsonArray();
                        bids[i] = new BigDecimal(level.get(0).getAsString());
                        bidSizes[i] = new BigDecimal(level.get(1).getAsString());
                    }

                    BigDecimal[] asks = new BigDecimal[asksArr.size()];
                    BigDecimal[] askSizes = new BigDecimal[asksArr.size()];
                    for (int i = 0; i < asksArr.size(); i++) {
                        JsonArray level = asksArr.get(i).getAsJsonArray();
                        asks[i] = new BigDecimal(level.get(0).getAsString());
                        askSizes[i] = new BigDecimal(level.get(1).getAsString());
                    }
                    
                    long ts = data.has("ts") ? Long.parseLong(data.get("ts").getAsString()) : System.currentTimeMillis();
                    entry = new PriceEntry(bids[0], asks[0], bidSizes[0], askSizes[0], ts, bids, bidSizes, asks, askSizes);
                } else if (data.has("bidPr") && data.has("askPr")) {
                    BigDecimal bidPr = new BigDecimal(data.get("bidPr").getAsString());
                    BigDecimal askPr = new BigDecimal(data.get("askPr").getAsString());
                    BigDecimal bidSz = data.has("bidSz") ? new BigDecimal(data.get("bidSz").getAsString()) : BigDecimal.ZERO;
                    BigDecimal askSz = data.has("askSz") ? new BigDecimal(data.get("askSz").getAsString()) : BigDecimal.ZERO;
                    long ts = data.has("ts") ? Long.parseLong(data.get("ts").getAsString()) : System.currentTimeMillis();

                    entry = new PriceEntry(bidPr, askPr, bidSz, askSz, ts, null, null, null, null);
                } else {
                    continue; // unknown format
                }
                priceCache.update(instId, entry);

                if (!firstPriceReceived) {
                    firstPriceReceived = true;
                    firstPriceLatch.countDown();
                    log.info("First price received: {} bid={} ask={}", instId, entry.bestBid(), entry.bestAsk());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse WebSocket message: {}", text, e);
        }
    }

    @Override
    public void onFailure(WebSocket ws, Throwable t, Response response) {
        log.error("WebSocket failure", t);
        connected = false;
        stopPingScheduler();
        reconnectHandler.scheduleReconnect(this);
    }

    @Override
    public void onClosed(WebSocket ws, int code, String reason) {
        log.warn("WebSocket closed: code={}, reason={}", code, reason);
        connected = false;
        stopPingScheduler();
        reconnectHandler.scheduleReconnect(this);
    }

    /**
     * Gracefully closes the WebSocket connection.
     */
    public void close() {
        connected = false;
        stopPingScheduler();
        if (webSocket != null) {
            webSocket.close(1000, "Shutdown");
        }
        log.info("BitgetWebSocketClient closed");
    }

    public boolean isConnected() {
        return connected;
    }
}
