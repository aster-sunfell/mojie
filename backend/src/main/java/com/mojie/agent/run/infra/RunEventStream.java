package com.mojie.agent.run.infra;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded SSE subscriptions, independent of agent workers and of slow clients. */
public final class RunEventStream implements AutoCloseable {
    private static final int MAX_CONNECTIONS = 20;
    private final JdbcRunQuery query;
    private final Semaphore capacity = new Semaphore(MAX_CONNECTIONS);
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "mojie-sse-timer"); thread.setDaemon(true); return thread;
    });
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(MAX_CONNECTIONS, MAX_CONNECTIONS,
            0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_CONNECTIONS), runnable -> {
        Thread thread = new Thread(runnable, "mojie-sse-delivery"); thread.setDaemon(true); return thread;
    });

    public RunEventStream(JdbcRunQuery query) {
        this.query = query;
        timer.setRemoveOnCancelPolicy(true);
    }

    public static final class CapacityExceeded extends RuntimeException {
        public CapacityExceeded() { super("Too many active event streams; retry or poll the run snapshot"); }
    }

    public SseEmitter open(String owner, String runId, long cursor) {
        if (!capacity.tryAcquire()) throw new CapacityExceeded();
        Subscription subscription = new Subscription(owner, runId, cursor);
        subscriptions.add(subscription);
        try {
            ScheduledFuture<?> task = timer.scheduleAtFixedRate(subscription::dispatch, 0, 500, TimeUnit.MILLISECONDS);
            subscription.timerTask.set(task);
            if (subscription.closed.get()) task.cancel(false);
            return subscription.emitter;
        } catch (RuntimeException e) {
            subscription.stop();
            throw e;
        }
    }

    private final class Subscription {
        final String owner;
        final String runId;
        final SseEmitter emitter = new SseEmitter(0L);
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean inFlight = new AtomicBoolean();
        final AtomicReference<ScheduledFuture<?>> timerTask = new AtomicReference<>();
        long cursor;
        long lastHeartbeat = System.nanoTime();

        Subscription(String owner, String runId, long cursor) {
            this.owner = owner; this.runId = runId; this.cursor = cursor;
            emitter.onCompletion(this::stop);
            emitter.onTimeout(this::stop);
            emitter.onError(error -> stop());
        }

        void stop() {
            if (!closed.compareAndSet(false, true)) return;
            ScheduledFuture<?> task = timerTask.get();
            if (task != null) task.cancel(false);
            subscriptions.remove(this);
            capacity.release();
        }

        void dispatch() {
            if (closed.get() || !inFlight.compareAndSet(false, true)) return;
            try { delivery.execute(this::send); }
            catch (RejectedExecutionException e) {
                inFlight.set(false);
                stop();
                emitter.completeWithError(e);
            }
        }

        void send() {
            try {
                if (closed.get()) return;
                var batch = query.events(owner, runId, cursor, 100);
                for (JdbcRunQuery.Event event : batch) {
                    if (closed.get()) return;
                    emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.type()).data(Map.of(
                            "schemaVersion", 1, "runId", runId, "seq", event.seq(), "type", event.type(),
                            "occurredAt", event.occurredAt(), "payload", event.payload())));
                    cursor = event.seq();
                }
                if (System.nanoTime() - lastHeartbeat >= TimeUnit.SECONDS.toNanos(15)) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = System.nanoTime();
                }
                Map<String, Object> snapshot = batch.isEmpty() ? query.detail(owner, runId) : null;
                boolean terminalSnapshot = snapshot != null
                        && Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(snapshot.get("status"))
                        && cursor >= ((Number) snapshot.get("lastEventSeq")).longValue();
                if (terminalSnapshot || batch.stream().anyMatch(event ->
                        Set.of("run.succeeded", "run.failed", "run.cancelled").contains(event.type()))) {
                    stop();
                    emitter.complete();
                }
            } catch (Exception e) {
                stop();
                emitter.completeWithError(e);
            } finally { inFlight.set(false); }
        }
    }

    @Override public void close() {
        timer.shutdownNow();
        for (Subscription subscription : subscriptions) {
            subscription.stop();
            subscription.emitter.complete();
        }
        delivery.shutdownNow();
    }
}
