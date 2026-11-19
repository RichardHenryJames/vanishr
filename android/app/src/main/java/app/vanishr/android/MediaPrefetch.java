package app.vanishr.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Downloads the encrypted pictures of a batch of incoming messages a few at a time, in the order in which the messages will be handled.
 *
 * <p>Handling a message means waiting for its picture, decrypting it and storing it, one message after another. The wait for the network
 * was added to the work for every picture; with this the pictures that come next are already being fetched while the one before is
 * stored, so a row of photos arrives in about the time of one download and the stores, not of all the downloads and the stores.
 *
 * <p>A download here is only an early start. It carries no decision: the message is still checked and decrypted exactly as before, in the
 * same order, on the same thread, and a picture that was fetched early for a message that is then refused is simply dropped. What was
 * fetched is encrypted for the one message (the key is in the message) and is held in memory for as long as the batch takes, at most
 * {@link #PARALLEL} pictures that have not been taken yet. If a download fails, for any reason, {@link #take} does it again the ordinary
 * way, so a failure here never decides what a failure means.
 *
 * <p>One thread asks for the pictures ({@link #expect}, {@link #start}, {@link #take}, {@link #close}); the downloads run on a few
 * threads of their own.
 */
final class MediaPrefetch implements AutoCloseable {
    /** How many pictures are fetched or waiting to be taken at one time. */
    static final int PARALLEL = 3;

    @FunctionalInterface interface Download { byte[] run() throws Exception; }

    private final List<UUID> order = new ArrayList<>();
    private final Map<UUID, Download> downloads = new HashMap<>();
    private final Map<UUID, Future<byte[]>> running = new HashMap<>();
    private ExecutorService pool;
    private int next;
    private boolean closed;

    /** A picture that will be wanted; call in the order the messages are handled. */
    void expect(UUID id, Download download) {
        if (id == null || download == null) throw new IllegalArgumentException("A picture and a way to fetch it are required");
        if (downloads.putIfAbsent(id, download) == null) order.add(id);
    }

    /** Starts fetching the first pictures. */
    void start() { fill(); }

    private void fill() {
        while (!closed && running.size() < PARALLEL && next < order.size()) {
            UUID id = order.get(next++);
            if (pool == null) pool = Executors.newFixedThreadPool(PARALLEL, task -> {
                Thread thread = new Thread(() -> {
                    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
                    catch (RuntimeException unavailable) { /* not on a phone: the default priority is fine */ }
                    task.run();
                }, "vanishr-media");
                thread.setDaemon(true);
                return thread;
            });
            Download download = downloads.get(id);
            running.put(id, pool.submit(download::run));
        }
    }

    /**
     * The picture {@code id}: the one that was fetched early, waiting for it if it is still coming, or, if there is none or it failed,
     * the one {@code ordinary} fetches now.
     */
    byte[] take(UUID id, Download ordinary) throws Exception {
        Future<byte[]> early = running.remove(id);
        fill();
        if (early == null) return ordinary.run();
        try { return early.get(); }
        catch (ExecutionException failure) { return ordinary.run(); }
        catch (InterruptedException interrupted) {
            early.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while fetching a picture");
        }
    }

    /** Drops what was not taken. A download that is under way finishes by itself and its picture is not kept. */
    @Override public void close() {
        closed = true;
        for (Future<byte[]> early : running.values()) early.cancel(true);
        running.clear();
        if (pool != null) pool.shutdownNow();
    }
}
