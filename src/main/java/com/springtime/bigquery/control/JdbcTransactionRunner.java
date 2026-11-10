package com.springtime.bigquery.control;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** One transaction around a control-plane state change and its outbox insert. */
@Component
public class JdbcTransactionRunner implements TransactionRunner {

    private final TransactionTemplate transactionTemplate;

    public JdbcTransactionRunner(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T run(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }
}
