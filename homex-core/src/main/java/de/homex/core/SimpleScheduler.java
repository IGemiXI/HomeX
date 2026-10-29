package de.homex.core;

import de.homex.api.device.ControlSource;
import de.homex.api.plugin.JavaPlugin;
import de.homex.api.scheduler.Scheduler;
import de.homex.api.scheduler.Task;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SimpleScheduler implements Scheduler {

    private final ScheduledExecutorService executor;
    private final Map<JavaPlugin, Set<SimpleTask>> tasks = new ConcurrentHashMap<>();
    private final Logger log;

    SimpleScheduler(Logger log) {
        this.log = log;
        AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "HomeX-Scheduler-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    private final class SimpleTask implements Task {
        final JavaPlugin owner;
        volatile Future<?> future;
        volatile boolean cancelled;

        SimpleTask(JavaPlugin owner) { this.owner = owner; }

        @Override public void cancel() {
            cancelled = true;
            if (future != null) future.cancel(false);
            Set<SimpleTask> set = tasks.get(owner);
            if (set != null) set.remove(this);
        }
        @Override public boolean isCancelled() { return cancelled; }
    }

    private Runnable wrap(SimpleTask task, Runnable r, boolean once) {
        return () -> {
            if (task.cancelled) return;
            try {
                r.run();
            } catch (Throwable t) {
                log.log(Level.SEVERE, "Fehler in Task von " + task.owner.getName(), t);
            } finally {
                if (once) task.cancel();
            }
        };
    }

    private SimpleTask create(JavaPlugin owner) {
        if (!owner.isEnabled()) throw new IllegalStateException(owner.getName() + " ist nicht aktiv");
        SimpleTask t = new SimpleTask(owner);
        tasks.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(t);
        return t;
    }

    @Override
    public Task runAsync(JavaPlugin owner, Runnable r) {
        SimpleTask t = create(owner);
        t.future = executor.submit(wrap(t, ControlSource.wrap(r), true));   // Auslöser mitnehmen
        return t;
    }

    @Override
    public Task runLater(JavaPlugin owner, Runnable r, long delay, TimeUnit unit) {
        SimpleTask t = create(owner);
        t.future = executor.schedule(wrap(t, ControlSource.wrap(r), true), delay, unit);
        return t;
    }

    @Override
    public Task runRepeating(JavaPlugin owner, Runnable r, long initialDelay, long period, TimeUnit unit) {
        SimpleTask t = create(owner);
        t.future = executor.scheduleAtFixedRate(wrap(t, r, false), initialDelay, period, unit);
        return t;
    }

    @Override
    public void cancelAll(JavaPlugin owner) {
        Set<SimpleTask> set = tasks.remove(owner);
        if (set != null) set.forEach(SimpleTask::cancel);
    }

    void shutdown() {
        executor.shutdownNow();
    }
}
