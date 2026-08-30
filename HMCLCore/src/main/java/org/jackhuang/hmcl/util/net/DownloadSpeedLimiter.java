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
package org.jackhuang.hmcl.util.net;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global download speed limiter using token bucket algorithm.
 * 
 * Ported from PCL: ModNet.vb NetTaskSpeedLimitHigh / NetTaskSpeedLimitLeft
 * 
 * How it works (matching PCL's logic exactly):
 * - Global token pool (NetTaskSpeedLimitLeft) starts at 0
 * - Every 100ms the refiller thread adds (limit / 10) tokens to the pool
 * - Each download thread consumes tokens when reading data
 * - If pool is empty (left <= 0), threads sleep for 16ms and retry
 * - When limit <= 0 (unlimited), no throttling is applied
 * 
 * Token refill rate: limit bytes per second, distributed as limit/10 every 100ms
 * 
 * NOTE: Speed limit is set by the HMCL main module via setSpeedLimitBytesPerSec().
 *       HMCLCore has no dependency on Config/settings.
 */
public final class DownloadSpeedLimiter {

    /** Global token counter, matching PCL's NetTaskSpeedLimitLeft. */
    private static final AtomicLong tokens = new AtomicLong(-1);

    /** The current speed limit in bytes/sec, <= 0 means unlimited. */
    private static volatile long speedLimitBytesPerSec = -1;

    /** Refiller thread that adds tokens every 100ms. */
    private static volatile Thread refillerThread = null;
    private static volatile boolean running = false;

    private DownloadSpeedLimiter() {}

    /**
     * Set the speed limit in bytes/sec.
     * Call this from HMCL main module when user changes speed setting.
     * 
     * Ported from PCL: ModNet.UpdateNetTaskSpeedLimitHigh()
     * 
     * @param bytesPerSec speed limit; <= 0 means unlimited
     */
    public static void setSpeedLimitBytesPerSec(long bytesPerSec) {
        speedLimitBytesPerSec = bytesPerSec;
    }

    /**
     * Start the global refiller thread.
     * Safe to call multiple times (idempotent).
     */
    public static synchronized void start() {
        if (refillerThread != null && refillerThread.isAlive())
            return;

        running = true;
        tokens.set(-1);

        refillerThread = new Thread(() -> {
            long nextTick = System.currentTimeMillis();
            while (running) {
                long limit = speedLimitBytesPerSec;
                if (limit > 0) {
                    long refill = limit / 10; // 100ms worth of tokens
                    if (tokens.get() < 0) {
                        tokens.set(refill);
                    } else {
                        tokens.addAndGet(refill);
                    }
                } else if (tokens.get() < 0) {
                    tokens.set(Long.MAX_VALUE); // unlimited sentinel
                }

                nextTick += 100;
                long sleepTime = nextTick - System.currentTimeMillis();
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        break;
                    }
                } else {
                    nextTick = System.currentTimeMillis();
                }
            }
        }, "DownloadSpeedLimiter-Refiller");
        refillerThread.setDaemon(true);
        refillerThread.start();
    }

    /**
     * Stop the refiller thread.
     */
    public static synchronized void stop() {
        running = false;
        if (refillerThread != null) {
            refillerThread.interrupt();
            refillerThread = null;
        }
    }

    /**
     * Acquire bytes from the token pool, blocking until available.
     * 
     * Ported from PCL: ModNet.vb line ~996-1000
     *   While NetTaskSpeedLimitHigh > 0 AndAlso NetTaskSpeedLimitLeft <= 0
     *       Threading.Thread.Sleep(16)
     *   End While
     *   Interlocked.Add(NetTaskSpeedLimitLeft, -RealDataCount)
     *
     * @param bytes the number of bytes to acquire
     */
    public static void acquire(int bytes) {
        long limit = speedLimitBytesPerSec;
        if (limit <= 0)
            return;

        while (true) {
            long current = tokens.get();
            if (current > 0) {
                long newVal;
                do {
                    if (current <= 0) break;
                    newVal = current - bytes;
                } while (!tokens.compareAndSet(current, Math.max(0, newVal)));
                
                current = tokens.get();
                if (current > 0 || current < 0)
                    break;
            }
            try {
                Thread.sleep(16);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Try to acquire bytes without blocking.
     * @return true if bytes were acquired, false if throttled
     */
    public static boolean tryAcquire(int bytes) {
        long limit = speedLimitBytesPerSec;
        if (limit <= 0)
            return true;

        long current = tokens.get();
        if (current < 0)
            return true;
        if (current <= 0)
            return false;

        long newVal;
        do {
            if (current <= 0) return false;
            newVal = current - bytes;
        } while (!tokens.compareAndSet(current, Math.max(0, newVal)));
        return true;
    }

    /** Current speed limit in bytes/sec, <= 0 means unlimited. */
    public static long getSpeedLimit() {
        return speedLimitBytesPerSec;
    }

    /** True if throttling is active (limit > 0). */
    public static boolean isThrottled() {
        return speedLimitBytesPerSec > 0;
    }
}
