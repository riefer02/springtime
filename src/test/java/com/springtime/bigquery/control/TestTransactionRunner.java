package com.springtime.bigquery.control;

import java.util.function.Supplier;

/** Test-only synchronous transaction runner. */
public final class TestTransactionRunner implements TransactionRunner {

    @Override
    public <T> T run(Supplier<T> action) {
        return action.get();
    }
}
