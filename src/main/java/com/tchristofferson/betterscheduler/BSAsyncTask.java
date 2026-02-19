package com.tchristofferson.betterscheduler;

import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class BSAsyncTask implements IBSTask {

    private final Logger logger;
    private final CountDownLatch completionLatch = new CountDownLatch(1);

    public BSAsyncTask(Plugin plugin) {
        this.logger = plugin.getLogger();
    }

    void execute() {
        try {
            run();
        } catch (Exception e) {
            logger.log(Level.WARNING, e.getClass().getSimpleName() + " occurred running async " + getClass().getSimpleName() + ": " + e.getMessage(), e);
        } finally {
            completionLatch.countDown();
        }
    }

    boolean waitForCompletion(Duration timeout) throws InterruptedException {
        return completionLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
