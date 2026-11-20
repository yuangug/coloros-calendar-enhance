package com.lcodexs.coursealarm;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Shares pending acquisition only. Completed values are never retained for a later caller. */
final class InFlightReads<K, V> {
    private final Object lock = new Object();
    private final Map<K, Flight> pending = new HashMap<>();
    private final Executor reader, canceller;

    private final class Flight {
        final FutureTask<V> task;
        final Runnable cancel;
        int waiters;

        Flight(K key, Callable<V> query, Runnable cancel) {
            this.cancel = cancel;
            task = new FutureTask<>(() -> {
                try { return query.call(); }
                finally {
                    synchronized (lock) { if (pending.get(key) == this) pending.remove(key); }
                }
            });
        }
    }

    InFlightReads(Executor reader, Executor canceller) { this.reader = reader; this.canceller = canceller; }

    V read(K key, Callable<V> query, Runnable cancelQuery, BooleanSupplier callerCancelled) throws Exception {
        if (callerCancelled.getAsBoolean()) throw new CancellationException();
        Flight flight;
        synchronized (lock) {
            flight = pending.get(key);
            if (flight == null) {
                flight = new Flight(key, query, cancelQuery);
                pending.put(key, flight);
                try { reader.execute(flight.task); }
                catch (RuntimeException rejected) { pending.remove(key); throw rejected; }
            }
            flight.waiters++;
        }
        try {
            for (;;) {
                if (callerCancelled.getAsBoolean()) throw new CancellationException();
                try {
                    V value = flight.task.get(100, TimeUnit.MILLISECONDS);
                    if (callerCancelled.getAsBoolean()) throw new CancellationException();
                    return value;
                } catch (TimeoutException waiting) {
                    // Each caller can leave independently without replacing another's cancel listener.
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw new IllegalStateException(cause);
                }
            }
        } finally {
            boolean cancel;
            synchronized (lock) {
                cancel = --flight.waiters == 0 && !flight.task.isDone();
                if (cancel && pending.get(key) == flight) pending.remove(key);
            }
            if (cancel) {
                flight.task.cancel(false);
                // A remote CancellationSignal.cancel() may block in Binder; never block the waiter.
                canceller.execute(flight.cancel);
            }
        }
    }

    /** Existing waiters retain their original read; requests after a write must start fresh. */
    void invalidate() { synchronized (lock) { pending.clear(); } }
}
