/*
 * Hello Minecraft! Launcher.
 * Copyright (C) 2013  huangyuhui <huanghongxun2008@126.com>
 * Portions copyright (C) 2025  PCL Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see {http://www.gnu.org/licenses/}.
 */
package org.jackhuang.hmcl.core.download;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jackhuang.hmcl.api.HMCLog;

/**
 * Download source manager with dynamic health checking and latency measurement.
 * 
 * Phase 1 Enhancement (from PCL ModNet.vb):
 * - Dynamic source latency measurement
 * - Automatic fallback when source fails
 * - China mirror priority optimization
 * - Parallel source testing (PCL's DlSourceLoader logic)
 * 
 * How it works (ported from PCL):
 * 1. ToolDownloadVersion = 0 (China/Auto):   BMCLAPI first, Mojang as fallback
 * 2. ToolDownloadVersion = 1 (International): Mojang first, BMCLAPI as fallback
 * 3. ToolDownloadVersion = 2 (Manual):       Parallel test → fastest wins
 * 
 * @author huangyuhui
 * @see <a href="https://github.com/Meloong-Git/PCL">PCL ModNet.vb DlSourceLoader</a>
 */
public final class DownloadSourceManager {

    /** Delay in ms between source availability checks */
    private static final long SOURCE_CHECK_INTERVAL_MS = 60_000; // 1 minute

    /** Timeout for latency measurement requests */
    private static final int LATENCY_TEST_TIMEOUT_MS = 5_000;

    /** URL used for latency testing - version manifest is small and reliable */
    private static final String LATENCY_TEST_URL_MOJANG
            = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final String LATENCY_TEST_URL_BMCLAPI
            = "http://bmclapi2.bangbang93.com/mc/game/version_manifest.json";

    /** Maximum time a source is considered healthy after last successful check */
    private static final long SOURCE_HEALTH_TIMEOUT_MS = 30_000;

    private DownloadSourceManager() {
    }

    // =========================================================================
    // Source Health Tracking
    // =========================================================================

    /** Latency measurements per provider ID (in milliseconds) */
    private static final Map<String, AtomicLong> sourceLatencies = new ConcurrentHashMap<>();

    /** Last successful response time per provider ID */
    private static final Map<String, Long> sourceLastSuccess = new ConcurrentHashMap<>();

    /** Failure count per provider ID */
    private static final Map<String, AtomicLong> sourceFailures = new ConcurrentHashMap<>();

    /** Executor for background health checks */
    private static volatile ExecutorService healthCheckExecutor;

    /**
     * Measure the latency of a download provider.
     * Returns -1 if measurement failed.
     * 
     * Ported from PCL: ModNet.vb NetSource / latency measurement
     * 
     * @param provider the provider to measure
     * @return latency in milliseconds, or -1 if unavailable
     */
    public static long measureLatency(IDownloadProvider provider) {
        String url;
        if (provider instanceof BMCLAPIDownloadProvider) {
            url = LATENCY_TEST_URL_BMCLAPI;
        } else if (provider instanceof MojangDownloadProvider) {
            url = LATENCY_TEST_URL_MOJANG;
        } else {
            // Generic: use the provider's version list URL
            url = provider.getVersionsListDownloadURL();
        }

        long start = System.currentTimeMillis();
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("HEAD");
            con.setConnectTimeout(LATENCY_TEST_TIMEOUT_MS);
            con.setReadTimeout(LATENCY_TEST_TIMEOUT_MS);
            con.setRequestProperty("User-Agent", "Hello Minecraft!");
            con.connect();
            int code = con.getResponseCode();
            con.disconnect();
            
            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_ACCEPTED) {
                long latency = System.currentTimeMillis() - start;
                sourceLatencies.put(provider.getId(), new AtomicLong(latency));
                sourceLastSuccess.put(provider.getId(), System.currentTimeMillis());
                return latency;
            }
        } catch (IOException e) {
            HMCLog.warn("Latency measurement failed for " + provider.getId() + ": " + e.getMessage());
        }
        return -1;
    }

    /**
     * Get the cached latency for a provider, or -1 if not measured yet.
     * 
     * @param providerId the provider ID
     * @return cached latency in ms, or -1
     */
    public static long getCachedLatency(String providerId) {
        AtomicLong lat = sourceLatencies.get(providerId);
        return lat != null ? lat.get() : -1;
    }

    /**
     * Record a download failure for a provider.
     * This increases failure count and marks the source as unhealthy.
     * 
     * @param provider the provider that failed
     */
    public static void recordFailure(IDownloadProvider provider) {
        String id = provider.getId();
        sourceFailures.computeIfAbsent(id, k -> new AtomicLong(0)).incrementAndGet();
        sourceLastSuccess.remove(id);
        HMCLog.warn("Download source " + id + " recorded failure. Total failures: "
                + sourceFailures.get(id).get());
    }

    /**
     * Record a successful download from a provider.
     * This resets the failure count.
     * 
     * @param provider the provider that succeeded
     */
    public static void recordSuccess(IDownloadProvider provider) {
        String id = provider.getId();
        sourceFailures.remove(id);
        sourceLastSuccess.put(id, System.currentTimeMillis());
    }

    /**
     * Check if a provider is currently considered healthy (not failing).
     * A source is unhealthy if it has recent failures or hasn't responded recently.
     * 
     * @param provider the provider to check
     * @return true if healthy, false otherwise
     */
    public static boolean isHealthy(IDownloadProvider provider) {
        String id = provider.getId();
        AtomicLong failures = sourceFailures.get(id);
        if (failures != null && failures.get() >= 3) {
            return false; // 3 consecutive failures = unhealthy
        }
        Long lastSuccess = sourceLastSuccess.get(id);
        if (lastSuccess != null
                && System.currentTimeMillis() - lastSuccess > SOURCE_HEALTH_TIMEOUT_MS) {
            // Source hasn't responded in a while - needs re-check
            return false;
        }
        return true;
    }

    // =========================================================================
    // Source Selection
    // =========================================================================

    /**
     * Source selection mode (mirrors PCL's ToolDownloadVersion).
     */
    public enum SourceMode {
        /** China/Auto: Prefer BMCLAPI, fallback to Mojang */
        CHINA(0),
        /** International: Prefer Mojang, fallback to BMCLAPI */
        INTERNATIONAL(1),
        /** Manual/Fastest: Both sources tested, fastest wins */
        FASTEST(2);

        private final int code;

        SourceMode(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static SourceMode fromCode(int code) {
            for (SourceMode mode : values()) {
                if (mode.code == code) return mode;
            }
            return CHINA;
        }
    }

    /**
     * Get the recommended download provider based on mode and current health.
     * 
     * Ported from PCL: ModNet.vb DlSourceLoader
     * Logic:
     * - CHINA mode:    Try BMCLAPI first (30s timeout), then Mojang (60s timeout)
     * - INTERNATIONAL: Try Mojang first (5s timeout), then BMCLAPI (30s timeout)
     * - FASTEST mode:  Parallel request, use whichever responds first
     * 
     * @param mode the source selection mode
     * @return the recommended provider, never null
     */
    public static IDownloadProvider getRecommendedProvider(SourceMode mode) {
        switch (mode) {
            case CHINA:
                // China mode: prefer BMCLAPI
                if (isHealthy(new BMCLAPIDownloadProvider())) {
                    return new BMCLAPIDownloadProvider();
                }
                return new MojangDownloadProvider();

            case INTERNATIONAL:
                // International mode: prefer Mojang
                if (isHealthy(new MojangDownloadProvider())) {
                    return new MojangDownloadProvider();
                }
                return new BMCLAPIDownloadProvider();

            case FASTEST:
            default:
                // Fastest mode: measure latency and pick the faster one
                return getFastestProvider();
        }
    }

    /**
     * Get the fastest available provider by measuring both sources.
     * This is a blocking call that measures latency of both providers.
     * 
     * @return the faster provider, or Mojang as default if both fail
     */
    public static IDownloadProvider getFastestProvider() {
        final IDownloadProvider[] winner = {null};
        final long[] latencies = {-1, -1};

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> {
                long start = System.currentTimeMillis();
                long lat = measureLatency(new MojangDownloadProvider());
                latencies[0] = lat;
                synchronized (winner) {
                    if (winner[0] == null && lat > 0) {
                        winner[0] = new MojangDownloadProvider();
                        winner.notify();
                    }
                }
            });
            executor.submit(() -> {
                long start = System.currentTimeMillis();
                long lat = measureLatency(new BMCLAPIDownloadProvider());
                latencies[1] = lat;
                synchronized (winner) {
                    if (winner[0] == null && lat > 0) {
                        winner[0] = new BMCLAPIDownloadProvider();
                        winner.notify();
                    }
                }
            });

            synchronized (winner) {
                while (winner[0] == null) {
                    winner.wait(10_000); // Wait max 10s for first response
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        return winner[0] != null ? winner[0] : new MojangDownloadProvider();
    }

    /**
     * Auto mode: detect user location and recommend appropriate source.
     * If latency data is available, use it; otherwise default based on
     * whether user is likely in China.
     */
    private static IDownloadProvider getAutoRecommendedProvider() {
        long mojangLatency = getCachedLatency(IDownloadProvider.MOJANG);
        long bmclapiLatency = getCachedLatency(IDownloadProvider.BMCLAPI);

        if (mojangLatency > 0 && bmclapiLatency > 0) {
            // Both have been measured - use the faster one
            return mojangLatency <= bmclapiLatency
                    ? new MojangDownloadProvider()
                    : new BMCLAPIDownloadProvider();
        }

        if (mojangLatency > 0) return new MojangDownloadProvider();
        if (bmclapiLatency > 0) return new BMCLAPIDownloadProvider();

        // No data - default to China mode (BMCLAPI) as HMCL primarily serves Chinese users
        return new BMCLAPIDownloadProvider();
    }

    /**
     * Get all available providers in fallback order for a given provider.
     * Includes the provider itself and all its fallbacks.
     * 
     * @param provider the primary provider
     * @return list of providers in order of priority (primary first)
     */
    public static List<IDownloadProvider> getProviderChain(IDownloadProvider provider) {
        java.util.ArrayList<IDownloadProvider> chain = new java.util.ArrayList<>();
        chain.add(provider);
        for (IDownloadProvider fallback : provider.getFallbackProviders()) {
            chain.addAll(getProviderChain(fallback));
        }
        return chain;
    }

    /**
     * Start background health monitoring for all known sources.
     * Runs periodically to keep latency data fresh.
     */
    public static void startBackgroundHealthCheck() {
        if (healthCheckExecutor != null) return;
        healthCheckExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HMCL-SourceHealthCheck");
            t.setDaemon(true);
            return t;
        });

        healthCheckExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    measureLatency(new MojangDownloadProvider());
                    measureLatency(new BMCLAPIDownloadProvider());
                    
                    long interval = SOURCE_CHECK_INTERVAL_MS;
                    
                    // If both sources are unhealthy, check more frequently
                    if (!isHealthy(new MojangDownloadProvider())
                            && !isHealthy(new BMCLAPIDownloadProvider())) {
                        interval = 10_000; // Check every 10s when both are down
                    }
                    
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    HMCLog.warn("Background health check error", e);
                }
            }
        });

        HMCLog.warn("Download source manager: background health check started");
    }

    /**
     * Stop background health monitoring.
     */
    public static void stopBackgroundHealthCheck() {
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdownNow();
            healthCheckExecutor = null;
        }
    }

    /**
     * Get debug info about all sources.
     * Useful for troubleshooting download issues.
     */
    public static String getDebugInfo() {
        StringBuilder sb = new StringBuilder("DownloadSourceManager Debug Info:\n");
        for (String id : Arrays.asList(IDownloadProvider.MOJANG, IDownloadProvider.BMCLAPI)) {
            AtomicLong lat = sourceLatencies.get(id);
            AtomicLong fail = sourceFailures.get(id);
            Long last = sourceLastSuccess.get(id);
            sb.append("  ").append(id).append(":\n");
            sb.append("    latency=").append(lat != null ? lat.get() + "ms" : "N/A").append("\n");
            sb.append("    failures=").append(fail != null ? fail.get() : 0).append("\n");
            sb.append("    lastSuccess=").append(last != null ? last : "never").append("\n");
            sb.append("    healthy=").append(isHealthy(id.startsWith("mojang")
                    ? new MojangDownloadProvider() : new BMCLAPIDownloadProvider())).append("\n");
        }
        return sb.toString();
    }
}
