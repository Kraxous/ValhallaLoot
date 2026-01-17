package dev.waystone.vallhaloot.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple rate limiter to prevent debug spam.
 */
public class RateLimiter {
    private final long intervalMs;
    private final AtomicLong lastExecutionTime = new AtomicLong(0);

    public RateLimiter(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /**
     * Returns true if enough time has passed since last execution.
     */
    public boolean tryExecute() {
        long now = System.currentTimeMillis();
        long last = lastExecutionTime.get();
        
        if (now - last >= intervalMs) {
            lastExecutionTime.set(now);
            return true;
        }
        return false;
    }

    /**
     * Execute a task if rate limit allows it.
     */
    public void execute(Runnable task) {
        if (tryExecute()) {
            task.run();
        }
    }

    /**
     * Reset the rate limiter.
     */
    public void reset() {
        lastExecutionTime.set(0);
    }
}
