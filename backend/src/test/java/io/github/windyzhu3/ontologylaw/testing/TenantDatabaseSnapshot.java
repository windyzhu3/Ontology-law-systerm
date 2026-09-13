package io.github.windyzhu3.ontologylaw.testing;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Stable test-only content snapshot of every tenant-scoped base table. */
public final class TenantDatabaseSnapshot {
    private TenantDatabaseSnapshot() {
    }

    public static Map<String, String> capture(PostgresIntegrationTest.Database database, UUID tenant)
            throws SQLException {
        var snapshot = new LinkedHashMap<String, String>();
        try (var connection = database.migratorConnection();
             var tables = connection.prepareStatement(
                     "select c.table_schema,c.table_name from information_schema.columns c "
                             + "join information_schema.tables t on t.table_schema=c.table_schema and t.table_name=c.table_name "
                             + "where c.column_name='tenant_id' and t.table_type='BASE TABLE' "
                             + "and c.table_schema not in ('pg_catalog','information_schema') "
                             + "order by c.table_schema,c.table_name");
             var rows = tables.executeQuery()) {
            while (rows.next()) {
                String schema = rows.getString(1);
                String table = rows.getString(2);
                String qualified = '"' + schema.replace("\"", "\"\"") + "\".\""
                        + table.replace("\"", "\"\"") + '"';
                try (var statement = connection.prepareStatement(
                        "select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text "
                                + "from " + qualified + " t where tenant_id=?")) {
                    statement.setObject(1, tenant);
                    try (var content = statement.executeQuery()) {
                        content.next();
                        snapshot.put(schema + "." + table, content.getString(1));
                    }
                }
            }
        }
        return snapshot;
    }
}
