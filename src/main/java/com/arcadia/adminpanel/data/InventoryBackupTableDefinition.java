package com.arcadia.adminpanel.data;

import com.arcadia.lib.data.TableDefinition;

import java.util.List;

/**
 * SQL schema for the daily inventory backups added in 1.3.2.
 *
 * <p>These do not share the generic record table. A record payload is a short JSON object written
 * once and read on every menu build; an inventory is a compressed NBT blob that can reach tens of
 * kilobytes on a heavy modpack, and cramming one into the shared {@code TEXT} column would both
 * overflow it and drag the whole record cache into memory at boot. A dedicated table lets the panel
 * list backups by their header alone and fetch a payload only when somebody actually opens one.</p>
 *
 * <p>{@code capture_reason} rather than {@code trigger}: the latter is a reserved word in both
 * MySQL and MariaDB and would need quoting in every statement that touches it.</p>
 *
 * @author vyrriox
 */
public final class InventoryBackupTableDefinition implements TableDefinition {

    @Override
    public String moduleId() {
        return "arcadia-adminpanel-invbackups";
    }

    @Override
    public List<String> createTableStatements() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS arcadia_inventory_backups (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(32) NOT NULL,
                    server_id VARCHAR(64) NOT NULL DEFAULT 'server1',
                    created_at BIGINT NOT NULL,
                    capture_reason VARCHAR(16) NOT NULL,
                    dimension VARCHAR(96) NOT NULL,
                    xp_level INT NOT NULL,
                    item_count INT NOT NULL,
                    payload MEDIUMTEXT NOT NULL,
                    INDEX idx_player_created (player_uuid, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """
        );
    }
}
