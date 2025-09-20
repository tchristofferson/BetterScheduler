package com.tchristofferson.betterscheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
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
    private final ExecutorService executorService = getExecutorService();

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

    /**
     * Schedule a task to run on the main thread of the server.
     * Better version of {@link org.bukkit.scheduler.BukkitScheduler#callSyncMethod(Plugin, Callable)}.
     * If already on the main thread, the callable will be run immediately on the current thread.
     * @param callable The {@link BSCallable<T> }
     * @return A {@link Future<T>} that will hold the returned object of the callable
     * @param <T> The type of object you are expecting to get back
     */
    public <T> Future<T> submitSyncTask(BSCallable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        callable.setFuture(future);

        if (Bukkit.isPrimaryThread()) {
            callable.call();
        } else {
            synchronized (executorService) {
                //If executor service is shutdown, add the callable to the blocking queue.
                    //Blocking queue callables will be run in the shutdown method
                if (executorService.isShutdown()) {
                    blockingSyncCallableQueue.add(callable);
                } else {//If executor service isn't shut down, add the task to queue to be run in next tick (handled by run() method)
                    syncCallableTaskQueue.add(callable);
                }
            }
        }

        return future;
    }

    /**
     * Schedule an async task.
     * Better version of {@link org.bukkit.scheduler.BukkitScheduler#runTaskAsynchronously(Plugin, Runnable)}.
     * If the TaskQueueRunner is shut down, the task will be run immediately on the current thread.
     * If not on the main thread, the task will be run immediately on the current thread.
     * @param task The {@link BSAsyncTask}
     */
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

    /**
     * This method is intended to run in {@link JavaPlugin#onDisable()}.
     * This method will wait for tasks to complete in a blocking manner.
     * It is not recommended to run this method if the server is not shutting down as it could lag the main thread depending on the tasks scheduled, if any.
     */
    //Called when the plugin is disabled
    public void shutdown() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Can only shutdown the task queue runner from the main server thread.");

        synchronized (executorService) {
            executorService.shutdown();
        }

        shutdownLatch.countDown();//Only used for JUnit tests
        run();//Run any sync tasks that were scheduled before shutdown that haven't had the opportunity to run yet
        Thread mainThread = Thread.currentThread();

        //Shutdown thread will wait for all tasks in progress to complete and then interrupt the main thread to stop waiting for new sync tasks to run
        Thread shutdownThread = new Thread(() -> {
            try {
                while (true) {
                    BSAsyncTask task;

                    synchronized (inProgressAsyncTaskQueue) {
                        //If no more tasks in progress break out of the while loop
                        if (inProgressAsyncTaskQueue.isEmpty())
                            break;

                        task = inProgressAsyncTaskQueue.values().iterator().next();
                    }

                    try {
                        task.waitForCompletion();
                    } catch (InterruptedException e) {
                        logger.warning("Shutdown thread interrupted while waiting for async task to complete.");
                    }
                }
            } finally {
                logger.info("Shutdown thread completed. Continuing main thread.");
                mainThread.interrupt();
            }
        });

        shutdownThread.start();

        try {
            //Wait for any sync tasks scheduled by async tasks and run them
            blockingSyncCallableQueue.take().call();
        } catch (InterruptedException e) {//Shutdown thread will interrupt once all async tasks are complete
            logger.info("Main thread continued.");
        }
    }

    //Will use virtual threads if available, else a cached thread pool
    private ExecutorService getExecutorService() {
        try {
            return (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        } catch (Exception e) {
            return Executors.newCachedThreadPool();
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
