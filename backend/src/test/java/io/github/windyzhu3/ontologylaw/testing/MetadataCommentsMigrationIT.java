package io.github.windyzhu3.ontologylaw.testing;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetadataCommentsMigrationIT {
    @Test void v1080_preserves_existing_facts_definitions_and_acl_and_adds_chinese_metadata() throws Exception {
        try (var database = PostgresIntegrationTest.Database.start("1070")) {
            AuthorizationServiceIT.seed(database, "HUMAN", "LEAD_CAPTURE");
            try (var connection = database.adminConnection()) {
                var before = snapshot(connection);
                database.migrations(null).migrate();
                database.migrations(null).validate();
                assertEquals(before, snapshot(connection));
                assertEquals("52-plus-2-r2-v22", scalar(connection, "select schema_contract_version from platform_meta.deployment_state where deployment_state_key='PRIMARY'"));
                assertEquals("43", scalar(connection, "select count(*) from platform_meta.flyway_schema_history where version is not null"));
                assertEquals("0", scalar(connection, "select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in ('identity','audit','responsibility','execution','external_action','evidence','party','lead','opportunity','conflict','contract','transfer','platform_meta') and coalesce(obj_description(p.oid,'pg_proc'),'') !~ '[一-龥]'"));
                assertEquals("0", scalar(connection, "select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where not t.tgisinternal and n.nspname in ('identity','audit','responsibility','execution','external_action','evidence','party','lead','opportunity','conflict','contract','transfer','platform_meta') and coalesce(obj_description(t.oid,'pg_trigger'),'') !~ '[一-龥]'"));
                assertTrue(scalar(connection, "select col_description('contract.negotiation_disposition'::regclass, attnum) from pg_attribute where attrelid='contract.negotiation_disposition'::regclass and attname='kind'").contains("协商处置类型"));
            }
        }
    }

    private static Map<String,List<String>> snapshot(Connection connection) throws Exception {
        var result = new TreeMap<String,List<String>>();
        String namespaces="('identity','audit','responsibility','execution','external_action','evidence','party','lead','opportunity','conflict','contract','transfer','platform_meta')";
        result.put("functions", rows(connection, "select p.oid::text,pg_get_functiondef(p.oid),coalesce(p.proacl::text,'') from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in "+namespaces));
        result.put("triggers", rows(connection, "select t.oid::text,pg_get_triggerdef(t.oid) from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname in "+namespaces));
        result.put("constraints", rows(connection, "select c.oid::text,pg_get_constraintdef(c.oid) from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname in "+namespaces));
        result.put("columns", rows(connection, "select a.attrelid::text,a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull::text,coalesce(a.attacl::text,'') from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace where a.attnum>0 and not a.attisdropped and n.nspname in "+namespaces));
        result.put("relationACL", rows(connection, "select c.oid::text,coalesce(c.relacl::text,'') from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname in "+namespaces));
        for (String name : rows(connection, "select n.nspname||'.'||c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where c.relkind='r' and n.nspname in "+namespaces+" and n.nspname<>'platform_meta'")) {
            result.put(name, rows(connection, "select to_jsonb(t)::text from "+name+" t"));
        }
        return result;
    }
    private static List<String> rows(Connection connection,String sql) throws Exception {
        var values = new ArrayList<String>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)) {
            while(rows.next()) {
                var cells=new ArrayList<String>();
                for(int i=1;i<=rows.getMetaData().getColumnCount();i++)cells.add(rows.getString(i));
                values.add(cells.size()==1?cells.getFirst():cells.toString());
            }
        }
        Collections.sort(values);return values;
    }
    private static String scalar(Connection connection,String sql) throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)){assertTrue(rows.next());return rows.getString(1);}
    }
}
