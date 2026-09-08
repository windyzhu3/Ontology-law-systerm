package io.github.windyzhu3.ontologylaw.evidence.internal.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubmissionHashTest {
    @Test void immutable_seven_field_jcs_vector_includes_escaping_unicode_and_six_digit_utc(){
        UUID tenant=id(1),submission=id(2),object=id(3),submitter=id(4);
        // Independent SHA-256 over a literal seven-property JSON vector, computed outside this encoder.
        assertEquals("QcILjypepYAFgsKmaTjdnecnRNCTtAkK2VB22GDuWOc",SubmissionHash.hash(tenant,submission,object,"Q\"\\\n\t中",7,submitter,Instant.parse("2026-09-05T00:00:00.123456Z")));
        assertNotEquals(SubmissionHash.hash(tenant,submission,object,"X",7,submitter,Instant.EPOCH),SubmissionHash.hash(tenant,submission,object,"X",8,submitter,Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,()->SubmissionHash.hash(tenant,submission,object,"X",1,submitter,Instant.ofEpochSecond(0,1)));
    }
    private UUID id(int n){return UUID.fromString("01900000-0000-7000-8000-"+String.format("%012d",n));}
}
