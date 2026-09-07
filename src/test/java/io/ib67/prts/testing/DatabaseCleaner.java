package io.ib67.prts.testing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Empties every table between tests.
 *
 * <p>{@code @TestTransaction} cannot do this job: the services commit inside
 * {@link io.quarkus.narayana.jta.QuarkusTransaction#requiringNew()}, whose writes outlive the outer
 * rollback. See agent-docs/testing.md.
 */
@ApplicationScoped
public class DatabaseCleaner {

    @Inject
    EntityManager entityManager;

    @Transactional
    public void clean() {
        // One TRUNCATE over all tables at once, so CASCADE settles the foreign-key ordering. The names
        // come back from pg_tables through %I, which is Postgres quoting its own identifiers.
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
