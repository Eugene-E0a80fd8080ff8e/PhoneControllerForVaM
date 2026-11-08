package local.movementtrackerforvam.movementtrackerforvam;

/** One executing job and one replaceable pending job; close never joins the caller. */
final class LatestWorker implements AutoCloseable {
    private Runnable pending;
    private boolean closed;
    LatestWorker(String name) {
        new Thread(() -> {
            while (true) {
                Runnable job;
                synchronized (this) {
                    while (!closed && pending == null) {
                        try { wait(); } catch (InterruptedException e) { return; }
                    }
                    if (closed) return;
                    job = pending; pending = null;
                }
                job.run();
            }
        }, name).start();
    }
    synchronized void submit(Runnable job) {
        if (!closed) { pending = job; notifyAll(); }
    }
    @Override public synchronized void close() { closed = true; pending = null; notifyAll(); }
}
