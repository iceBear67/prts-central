package io.ib67.prts.testing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Truncates database tables between test executions.
 *
 * <p>Services commit their own transactions via {@code QuarkusTransaction.requiringNew()},
 * so standard test transaction rollback cannot be used.
 */
@ApplicationScoped
public class DatabaseCleaner {

    @Inject
    EntityManager entityManager;

    @Transactional
    public void clean() {
        // Truncate all tables in the current schema in a single command with CASCADE to handle foreign keys.
        var tables = (String) entityManager.createNativeQuery("""
                select string_agg(format('%I.%I', schemaname, tablename), ', ')
                from pg_tables
                where schemaname = current_schema()
                """).getSingleResult();
        if (tables == null) {
            return;
        }
        entityManager.createNativeQuery("truncate table " + tables + " restart identity cascade")
                .executeUpdate();
    }
}
