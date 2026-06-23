package com.arb.bitget.risk;

import com.arb.bitget.executor.BitgetApiClient;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches and caches account balances on startup.
 * Provides pre-flight verification to ensure sufficient funds exist before executing a trade.
 */
public class BalanceManager {

    private static final Logger log = LoggerFactory.getLogger(BalanceManager.class);

    private final BitgetApiClient apiClient;
    private final ConcurrentHashMap<String, BigDecimal> balances = new ConcurrentHashMap<>();

    public BalanceManager(BitgetApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Initializes the balance cache by querying the API.
     */
    public void initialize() {
        if (apiClient == null) {
            log.info("BalanceManager: No API client (Paper mode), skipping balance fetch");
            return;
        }
        
        try {
            List<JsonObject> assets = apiClient.getBalances();
            for (JsonObject asset : assets) {
                String coin = asset.has("coin") ? asset.get("coin").getAsString() : "";
                String availableStr = asset.has("available") ? asset.get("available").getAsString() : "0";
                
                if (!coin.isEmpty()) {
                    BigDecimal available = new BigDecimal(availableStr);
                    if (available.compareTo(BigDecimal.ZERO) > 0) {
                        balances.put(coin, available);
                        log.info("Cached balance: {} = {}", coin, available);
                    }
                }
            }
            log.info("BalanceManager initialized. Cached {} non-zero balances.", balances.size());
        } catch (Exception e) {
            log.error("Failed to initialize BalanceManager", e);
            throw new RuntimeException("Could not fetch initial balances", e);
        }
    }

    /**
     * Checks if the account has at least the specified amount of the coin.
     * In paper mode (apiClient == null), always returns true.
     */
    public boolean hasSufficientBalance(String coin, BigDecimal requiredAmount) {
        if (apiClient == null) {
            return true;
        }
        BigDecimal available = balances.getOrDefault(coin, BigDecimal.ZERO);
        return available.compareTo(requiredAmount) >= 0;
    }
}
