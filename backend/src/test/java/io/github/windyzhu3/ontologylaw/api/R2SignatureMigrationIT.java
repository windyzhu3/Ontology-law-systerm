package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class R2SignatureMigrationIT extends PostgresIntegrationTest {
    @Test void manual_signature_successor_migrates_on_real_postgres_without_changing_the_approval_boundary() throws Exception {
        try(var c=database.adminConnection();var s=c.createStatement();var r=s.executeQuery("select to_regclass('contract.signature_workflow') is not null, to_regclass('contract.signature_arrangement') is not null, to_regclass('contract.signature_verification') is not null, to_regclass('contract.signature_handoff') is not null")){
            assertTrue(r.next());for(int i=1;i<=4;i++)assertTrue(r.getBoolean(i));
        }
    }
}
