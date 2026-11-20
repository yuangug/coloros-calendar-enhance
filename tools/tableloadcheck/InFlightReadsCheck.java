package com.lcodexs.coursealarm;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Concurrency behavior checks; no Android stubs, database fixtures, or completed-value cache. */
public final class InFlightReadsCheck {
    private static int checks;

    public static void main(String[] args) throws Exception {
        sharesOnlyPending();
        independentKeys();
        cancellationIsolation(true);
        cancellationIsolation(false);
        lastWaiterCancels();
        invalidationStartsFresh();
        failureDoesNotPoison();
        rejectedSubmissionDoesNotPoison();
        alreadyCancelledDoesNotRead();
        System.out.println("InFlightReadsCheck PASS " + checks + " assertions");
    }

    private static void sharesOnlyPending() throws Exception {
        try (Harness h = new Harness()) {
            AtomicInteger calls = new AtomicInteger(); CountDownLatch release = new CountDownLatch(1);
            Probe first = new Probe(), second = new Probe();
            Future<Integer> a = h.callers.submit(() -> h.reads.read("table", () -> { calls.incrementAndGet(); await(release); return 7; }, () -> {}, first));
            await(first.joined);
            Future<Integer> b = h.callers.submit(() -> h.reads.read("table", () -> { calls.incrementAndGet(); return 8; }, () -> {}, second));
            await(second.joined); release.countDown();
            check(a.get(3, TimeUnit.SECONDS) == 7 && b.get(3, TimeUnit.SECONDS) == 7, "same pending read result");
            check(calls.get() == 1, "only one concurrent query");
            int fresh = h.reads.read("table", () -> { calls.incrementAndGet(); return 9; }, () -> {}, () -> false);
            check(fresh == 9 && calls.get() == 2, "later call starts a new query");
        }
    }

    private static void independentKeys() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
            Future<Integer> a = h.callers.submit(() -> h.reads.read("local:table1", () -> { started.countDown(); await(release); return 1; }, () -> {}, () -> false));
            Future<Integer> b = h.callers.submit(() -> h.reads.read("merged:table1", () -> { started.countDown(); await(release); return 2; }, () -> {}, () -> false));
            await(started); release.countDown();
            check(a.get(3, TimeUnit.SECONDS) == 1 && b.get(3, TimeUnit.SECONDS) == 2, "backend/table keys stay independent");
        }
    }

    private static void cancellationIsolation(boolean cancelLeader) throws Exception {
        try (Harness h = new Harness()) {
            AtomicInteger cancels = new AtomicInteger(); CountDownLatch release = new CountDownLatch(1);
            Probe first = new Probe(), second = new Probe();
            Future<Integer> a = h.callers.submit(() -> h.reads.read("table", () -> { await(release); return 42; }, cancels::incrementAndGet, first));
            await(first.joined);
            Future<Integer> b = h.callers.submit(() -> h.reads.read("table", () -> 99, () -> { throw new AssertionError("follower's query was never used"); }, second));
            await(second.joined);
            (cancelLeader ? first : second).cancelled.set(true);
            expectFailure(cancelLeader ? a : b, CancellationException.class);
            check(cancels.get() == 0, "one cancelled waiter cannot cancel shared provider work");
            release.countDown();
            check((cancelLeader ? b : a).get(3, TimeUnit.SECONDS) == 42, "other waiter still gets complete result");
        }
    }

    private static void lastWaiterCancels() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), cancelled = new CountDownLatch(1);
            AtomicInteger cancels = new AtomicInteger(); Probe caller = new Probe();
            Future<Integer> old = h.callers.submit(() -> h.reads.read("table", () -> { started.countDown(); await(release); return 1; },
                    () -> { cancels.incrementAndGet(); release.countDown(); cancelled.countDown(); }, caller));
            await(started); await(caller.joined); caller.cancelled.set(true);
            expectFailure(old, CancellationException.class); await(cancelled);
            check(cancels.get() == 1, "last waiter cancels query once");
            check(h.reads.read("table", () -> 2, () -> {}, () -> false) == 2, "cancelled flight cannot be rejoined");
        }
    }

    private static void invalidationStartsFresh() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch release = new CountDownLatch(1), releaseNew = new CountDownLatch(1);
            Probe oldCaller = new Probe(), newCaller = new Probe(), joinedNew = new Probe();
            AtomicInteger newQueries = new AtomicInteger();
            Future<Integer> old = h.callers.submit(() -> h.reads.read("table", () -> { await(release); return 1; }, () -> {}, oldCaller));
            await(oldCaller.joined); h.reads.invalidate();
            Future<Integer> newer = h.callers.submit(() -> h.reads.read("table", () -> { newQueries.incrementAndGet(); await(releaseNew); return 2; }, () -> {}, newCaller));
            await(newCaller.joined);
            release.countDown();
            check(old.get(3, TimeUnit.SECONDS) == 1, "pre-change caller retains original acquisition semantics");
            Future<Integer> joining = h.callers.submit(() -> h.reads.read("table", () -> { newQueries.incrementAndGet(); return 99; }, () -> {}, joinedNew));
            await(joinedNew.joined); releaseNew.countDown();
            check(newer.get(3, TimeUnit.SECONDS) == 2 && joining.get(3, TimeUnit.SECONDS) == 2,
                    "old completion cannot erase a newer pending flight");
            check(newQueries.get() == 1, "new callers join only the post-invalidation query");
            check(h.reads.read("table", () -> 3, () -> {}, () -> false) == 3, "old completion cannot remove or cache another result");
        }
    }

    private static void failureDoesNotPoison() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch release = new CountDownLatch(1); Probe first = new Probe(), second = new Probe();
            AtomicInteger calls = new AtomicInteger();
            Future<Integer> a = h.callers.submit(() -> h.reads.read("table", () -> { calls.incrementAndGet(); await(release); throw new IOException("provider died"); }, () -> {}, first));
            await(first.joined);
            Future<Integer> b = h.callers.submit(() -> h.reads.read("table", () -> 4, () -> {}, second));
            await(second.joined); release.countDown();
            expectFailure(a, IOException.class); expectFailure(b, IOException.class);
            check(calls.get() == 1, "provider failure shared, no partial second query");
            check(h.reads.read("table", () -> 5, () -> {}, () -> false) == 5, "later load retries after failure");
        }
    }

    private static void rejectedSubmissionDoesNotPoison() throws Exception {
        AtomicBoolean reject = new AtomicBoolean(true);
        InFlightReads<String, Integer> reads = new InFlightReads<>(task -> {
            if (reject.getAndSet(false)) throw new java.util.concurrent.RejectedExecutionException();
            task.run();
        }, Runnable::run);
        try { reads.read("key", () -> 1, () -> {}, () -> false); throw new AssertionError("submission should fail"); }
        catch (java.util.concurrent.RejectedExecutionException expected) { checks++; }
        check(reads.read("key", () -> 2, () -> {}, () -> false) == 2, "rejected task removed from active reads");
    }

    private static void alreadyCancelledDoesNotRead() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InFlightReads<String, Integer> reads = new InFlightReads<>(Runnable::run, Runnable::run);
        try { reads.read("key", () -> calls.incrementAndGet(), () -> {}, () -> true); throw new AssertionError("must cancel"); }
        catch (CancellationException expected) { checks++; }
        check(calls.get() == 0, "cancelled caller does not start acquisition");
    }

    private static void expectFailure(Future<?> future, Class<? extends Throwable> type) throws Exception {
        try { future.get(3, TimeUnit.SECONDS); throw new AssertionError("Expected " + type.getSimpleName()); }
        catch (ExecutionException error) { check(type.isInstance(error.getCause()), "preserve " + type.getSimpleName()); }
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("concurrent read timed out");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); checks++;
    }
    private static final class Probe implements BooleanSupplier {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch joined = new CountDownLatch(1);
        private final AtomicInteger checks = new AtomicInteger();
        @Override public boolean getAsBoolean() {
            if (checks.incrementAndGet() >= 2) joined.countDown();
            return cancelled.get();
        }
    }
    private static final class Harness implements AutoCloseable {
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final ExecutorService cancel = Executors.newSingleThreadExecutor();
        final ExecutorService callers = Executors.newFixedThreadPool(2);
        final InFlightReads<String, Integer> reads = new InFlightReads<>(workers, cancel);
        public void close() { callers.shutdownNow(); workers.shutdownNow(); cancel.shutdownNow(); }
    }
}
