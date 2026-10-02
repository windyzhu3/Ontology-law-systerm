package io.github.windyzhu3.ontologylaw.execution;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
class RuntimeSchemaVersionTest {
    @Test void additive_contract_source_schema_is_registered_without_weakening_expected_digests() {
        byte[] digest=new byte[32];Arrays.fill(digest,(byte)1);
        assertDoesNotThrow(()->new RuntimeDatabase.Expected("52-plus-2-r2-v12",digest,digest));
        assertDoesNotThrow(()->new RuntimeDatabase.Expected("52-plus-2-r2-v13",digest,digest));
        assertThrows(IllegalArgumentException.class,()->new RuntimeDatabase.Expected("52-plus-2-r2-v13",new byte[32],digest));
        assertDoesNotThrow(()->new RuntimeDatabase.Expected("52-plus-2-r2-v19",digest,digest));
        assertDoesNotThrow(()->new RuntimeDatabase.Expected("52-plus-2-r2-v20",digest,digest));
        assertDoesNotThrow(()->new RuntimeDatabase.Expected("52-plus-2-r2-v21",digest,digest));
        assertDoesNotThrow(()->new RuntimeDatabase.Expected("52-plus-2-r2-v22",digest,digest));
        assertThrows(IllegalArgumentException.class,()->new RuntimeDatabase.Expected("52-plus-2-r2-v23",digest,digest));
        assertThrows(IllegalArgumentException.class,()->new RuntimeDatabase.Expected("52-plus-2-r2-v22",new byte[32],digest));
        assertThrows(IllegalArgumentException.class,()->new RuntimeDatabase.Expected("52-plus-2-r2-v22",digest,new byte[31]));
        assertThrows(IllegalArgumentException.class,()->new RuntimeDatabase.Expected("52-plus-2-r2-v21",new byte[32],digest));
        assertThrows(IllegalArgumentException.class,()->new RuntimeDatabase.Expected("52-plus-2-r2-v21",digest,new byte[31]));
        assertThrows(IllegalArgumentException.class,()->new RuntimeDatabase.Expected("52-plus-2-r2-v20",new byte[32],digest));
        assertThrows(IllegalArgumentException.class,()->new RuntimeDatabase.Expected("52-plus-2-r2-v20",digest,new byte[31]));
        assertThrows(IllegalArgumentException.class,()->new RuntimeDatabase.Expected("52-plus-2-r2-v12",new byte[32],digest));
        assertThrows(IllegalArgumentException.class,()->new RuntimeDatabase.Expected("52-plus-2-r2-v12",digest,new byte[31]));
    }
}
