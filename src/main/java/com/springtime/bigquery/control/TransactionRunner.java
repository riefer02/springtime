package com.springtime.bigquery.control;

import java.util.function.Supplier;

/** Runs control-plane work in one Postgres transaction. */
public interface TransactionRunner {

    <T> T run(Supplier<T> action);
}
