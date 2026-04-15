package com.arcadia.adminpanel.data;

import com.arcadia.lib.data.TableDefinition;

import java.util.List;

/**
 * SQL table definitions for admin panel (warns + jail).
 *
 * @author vyrriox
 */
public final class WarnTableDefinition implements TableDefinition {

    @Override
    public String moduleId() {
        return "arcadia-adminpanel";
    }

    @Override
    public List<String> createTableStatements() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS arcadia_admin_warns (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    reason TEXT NOT NULL,
                    warned_by VARCHAR(64) NOT NULL,
                    server_id VARCHAR(64) NOT NULL DEFAULT 'server1',
                    timestamp BIGINT NOT NULL,
                    INDEX idx_player (player_uuid),
                    INDEX idx_server (server_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """,
                """
                CREATE TABLE IF NOT EXISTS arcadia_admin_jail (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    reason TEXT NOT NULL,
                    jailed_by VARCHAR(64) NOT NULL,
                    server_id VARCHAR(64) NOT NULL DEFAULT 'server1',
                    timestamp BIGINT NOT NULL,
                    duration_ms BIGINT NOT NULL DEFAULT 0,
                    INDEX idx_server (server_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """
        );
    }
}
