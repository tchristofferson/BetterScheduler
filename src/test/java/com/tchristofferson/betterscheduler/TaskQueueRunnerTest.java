package com.tchristofferson.betterscheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TaskQueueRunnerTest {

    private static Plugin plugin;

    @BeforeAll
    public static void beforeAll() {
        plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(TaskQueueRunnerTest.class.getSimpleName()));
    }

    @Test
    public void testSubmitSyncTaskFromAsyncTask() throws ExecutionException, InterruptedException, TimeoutException {
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            //Simulate scheduling sync thread from non-primary thread
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);

            BSCallable<Boolean> callable = new BSCallable<>() {
                @Override
                protected Boolean execute() {
                    return true;
                }
            };

            TaskQueueRunner taskQueueRunner = new TaskQueueRunner();
            Future<Boolean> future = taskQueueRunner.submitSyncTask(callable);
            assertTrue(taskQueueRunner.hasSyncTasks());
            taskQueueRunner.run();
            assertFalse(taskQueueRunner.hasSyncTasks());
            assertTrue(future.get(0, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testShutdown() throws InterruptedException {
        TaskQueueRunner taskQueueRunner = new TaskQueueRunner();
        CountDownLatch syncTaskScheduled = new CountDownLatch(1);
        CountDownLatch finishSyncTask = new CountDownLatch(1);

        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            taskQueueRunner.scheduleAsyncTask(new BSAsyncTask(plugin) {
                @Override
                public void run() throws Exception {
                    taskQueueRunner.waitForShutdown();
                    Future<Boolean> future;

                    try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
                        bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);

                        future = taskQueueRunner.submitSyncTask(new BSCallable<>() {
                            @Override
                            protected Boolean execute() throws InterruptedException {
                                assertTrue(finishSyncTask.await(1, TimeUnit.SECONDS));
                                return true;
                            }
                        });
                    }

                    syncTaskScheduled.countDown();
                    assertTrue(future.get(1, TimeUnit.SECONDS));
                }
            });
        }

        Thread syncThread = new Thread(() -> {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
                taskQueueRunner.shutdown();
            }
        });
        syncThread.start();

        assertTrue(syncTaskScheduled.await(1, TimeUnit.SECONDS));
        assertTrue(taskQueueRunner.hasInProgressAsyncTasks());
        finishSyncTask.countDown();
        syncThread.join(Duration.ofSeconds(1).toMillis());
    }

    @Test
    public void testShutdownFromWrongThreadThrowsException() {
        TaskQueueRunner taskQueueRunner = new TaskQueueRunner();

        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class, taskQueueRunner::shutdown);
        }
    }

    @Test
    public void testShutdownRunsSyncTasksThatWereScheduledBeforeShutdown() {
        TaskQueueRunner taskQueueRunner = new TaskQueueRunner();

        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            taskQueueRunner.submitSyncTask(new BSCallable<>() {
                @Override
                protected Boolean execute() {
                    return true;
                }
            });

            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertTrue(taskQueueRunner.hasSyncTasks());
            taskQueueRunner.shutdown();
            assertFalse(taskQueueRunner.hasSyncTasks());
        }
    }
}