package com.tchristofferson.betterscheduler;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public abstract class BSCallable<V> implements Callable<V> {

    private CompletableFuture<V> future;

    @Override
    public V call() {
        if (future == null)
            throw new IllegalStateException("Future must be set before calling call() on an " + getClass().getSimpleName() + ".");

        V value;

        try {
            value = execute();
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw new RuntimeException(e.getClass().getSimpleName() + " occurred calling sync " + getClass().getSimpleName() + ": " + e.getMessage(), e);
        }

        future.complete(value);
        return value;
    }

    protected abstract V execute() throws Exception;

    void setFuture(CompletableFuture<V> future) {
        this.future = future;
    }

}
