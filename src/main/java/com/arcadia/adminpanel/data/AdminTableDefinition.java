package com.arcadia.adminpanel.data;

import com.arcadia.lib.data.TableDefinition;

import java.util.List;

/**
 * SQL schema for the 1.3.0 record store.
 *
 * <p>Every append-only feature added in 1.3.0 (audit log, staff notes, sanction history, offline
 * mail, bans, watchlist) shares one generic table instead of getting a bespoke one. The payload is
 * a JSON blob owned by the feature; the columns are only what the store needs to index and to sync
 * across servers. That keeps the migration surface at exactly one table no matter how many record
 * kinds get added later, and it means a new feature needs zero DDL.</p>
 *
 * <p>{@code id} is the cross-server cursor: each server polls for rows with a higher id than the
 * highest it has seen, so remote records converge without a full table scan.</p>
 *
 * @author vyrriox
 */
public final class AdminTableDefinition implements TableDefinition {

    @Override
    public String moduleId() {
        return "arcadia-adminpanel-records";
    }

    @Override
    public List<String> createTableStatements() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS arcadia_admin_records (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    kind VARCHAR(32) NOT NULL,
                    subject VARCHAR(36) NOT NULL,
                    payload TEXT NOT NULL,
                    server_id VARCHAR(64) NOT NULL DEFAULT 'server1',
                    created_at BIGINT NOT NULL,
                    INDEX idx_kind_subject (kind, subject),
                    INDEX idx_kind_created (kind, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """
        );
    }
}
