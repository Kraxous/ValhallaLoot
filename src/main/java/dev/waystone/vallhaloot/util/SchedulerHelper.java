package dev.waystone.vallhaloot.util;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;

/**
 * Thread-safe scheduler helper to manage async and sync tasks.
 * Prevents blocking the main thread.
 */
public class SchedulerHelper {
    private final JavaPlugin plugin;

    public SchedulerHelper(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Run a task asynchronously.
     * Safe to use anywhere; result processing must happen via callback.
     */
    public void runAsync(Runnable task) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Run a task on the main thread.
     * MUST ONLY be called from async context or from main thread (safe no-op then).
     */
    public void runSync(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run a task on the main thread and wait for result (with timeout).
     * CAUTION: Only use if you are NOT on the main thread.
     * Do NOT call this from event handlers; use async + callback instead.
     */
    public BukkitTask runTaskSync(Runnable task) {
        return plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /**
     * Run an async task that returns a future.
     * Never block waiting for this on the main thread!
     */
    public <T> CompletableFuture<T> runAsyncFuture(java.util.function.Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runAsync(() -> {
            try {
                T result = task.get();
                // Switch back to sync to complete the future if needed for Bukkit API
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Chain async computation with sync result application.
     * Primary pattern for this plugin: compute async, apply sync.
     */
    public <T> void runAsyncThenSync(java.util.function.Supplier<T> asyncTask,
                                     java.util.function.Consumer<T> syncCallback) {
        runAsync(() -> {
            try {
                T result = asyncTask.get();
                runSync(() -> syncCallback.accept(result));
            } catch (Exception e) {
                plugin.getLogger().warning("Error in async-then-sync task: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
}
