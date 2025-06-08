package com.tchristofferson.betterscheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TaskQueueRunner extends BukkitRunnable {

    private final Logger logger;

    private final CountDownLatch shutdownLatch = new CountDownLatch(1);//Used for testing
    private final AtomicLong asyncRunIdGenerator = new AtomicLong(0);
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private final Queue<BSCallable<?>> syncCallableTaskQueue = new ConcurrentLinkedQueue<>();
    private final Map<Long, BSAsyncTask> inProgressAsyncTaskQueue = Collections.synchronizedMap(new HashMap<>());

    //Callables will be added to this queue only when the executor service is shutdown.
    private final BlockingQueue<BSCallable<?>> blockingSyncCallableQueue = new LinkedBlockingQueue<>();

    public TaskQueueRunner(Plugin plugin) {
        this.logger = plugin.getLogger();
        runTaskTimer(plugin, 1, 1);
    }

    //Used for tests
    TaskQueueRunner() {
        this.logger = Logger.getLogger(getClass().getSimpleName());
    }

    @Override
    public void run() {
        /* This will run on the server's main thread */

        /*
         *  Keep running tasks until the queues are empty.
         *  New sync tasks are typically created from an async task because something needs to run on the main server thread.
         */
        while (!syncCallableTaskQueue.isEmpty()) {
            BSCallable<?> callable = syncCallableTaskQueue.poll();

            try {
                callable.call();
            } catch (Exception e) {
                this.logger.log(Level.WARNING, e.getClass().getSimpleName() + " occurred running sync " + callable.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
        }
    }

    public <T> Future<T> submitSyncTask(BSCallable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        callable.setFuture(future);

        if (Bukkit.isPrimaryThread()) {
            callable.call();
        } else {
            synchronized (executorService) {
                if (executorService.isShutdown()) {
                    blockingSyncCallableQueue.add(callable);
                } else {
                    syncCallableTaskQueue.add(callable);
                }
            }
        }

        return future;
    }

    public void scheduleAsyncTask(BSAsyncTask task) {
        boolean runHere = false;

        synchronized (executorService) {
            if (!executorService.isShutdown() && Bukkit.isPrimaryThread()) {
                long runId = asyncRunIdGenerator.incrementAndGet();
                inProgressAsyncTaskQueue.put(runId, task);

                executorService.execute(() -> {
                    task.execute();
                    inProgressAsyncTaskQueue.remove(runId);
                });
            } else {
                runHere = true;
            }
        }

        if (runHere)
            task.execute();
    }

    //Called when the plugin is disabled
    public void shutdown() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Can only shutdown the task queue runner from the main server thread.");

        synchronized (executorService) {
            executorService.shutdown();
        }

        shutdownLatch.countDown();
        run();//Run any sync tasks that were scheduled before shutdown that haven't had the opportunity to run yet
        Thread mainThread = Thread.currentThread();

        Thread shutdownThread = new Thread(() -> {
            try {
                while (true) {
                    BSAsyncTask task;

                    synchronized (inProgressAsyncTaskQueue) {
                        if (inProgressAsyncTaskQueue.isEmpty())
                            break;

                        task = inProgressAsyncTaskQueue.values().iterator().next();
                    }

                    try {
                        task.waitForCompletion();
                    } catch (InterruptedException e) {
                        logger.severe("Shutdown thread interrupted while waiting for async task to complete. This may result in loss of money or items for players.");
                    }
                }
            } finally {
                logger.info("Shutdown thread completed. Continuing main thread.");
                mainThread.interrupt();
            }
        });

        shutdownThread.start();

        try {
            blockingSyncCallableQueue.take().call();
        } catch (InterruptedException e) {
            logger.info("Main thread continued.");
        }
    }

    /* Methods for tests */

    boolean hasSyncTasks() {
        return !syncCallableTaskQueue.isEmpty();
    }

    boolean hasInProgressAsyncTasks() {
        return !inProgressAsyncTaskQueue.isEmpty();
    }

    void waitForShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

}
