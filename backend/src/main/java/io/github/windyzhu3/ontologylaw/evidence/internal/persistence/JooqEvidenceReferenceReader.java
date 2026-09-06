package io.github.windyzhu3.ontologylaw.evidence.internal.persistence;

import io.github.windyzhu3.ontologylaw.evidence.EvidenceReferenceReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;
import static io.github.windyzhu3.ontologylaw.evidence.internal.persistence.jooq.Tables.*;

public final class JooqEvidenceReferenceReader implements EvidenceReferenceReader {
    public Reference read(Connection c,UUID tenant,UUID id){
        var db=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));var s=EVIDENCE_SUBMISSION;var b=EVIDENCE_BINDING;
        var submission=db.selectFrom(s).where(s.TENANT_ID.eq(tenant)).and(s.EVIDENCE_SUBMISSION_ID.eq(id)).fetchOne();if(submission==null)return null;
        var binding=db.select(b.EVIDENCE_BINDING_ID,b.REVISION,b.TARGET_TYPE,b.TARGET_ID,b.TARGET_REVISION,b.TARGET_HASH,b.REVOKED_AT)
            .from(b).where(b.TENANT_ID.eq(tenant)).and(b.EVIDENCE_SUBMISSION_ID.eq(id)).fetchOne();if(binding==null)return null;
        String hash=SubmissionHash.hash(submission.get(s.TENANT_ID),id,submission.get(s.RECEIVED_SOURCE_OBJECT_ID),submission.get(s.SUBMISSION_CONTRACT_CODE),
            submission.get(s.SUBMISSION_CONTRACT_VERSION),submission.get(s.SUBMITTED_BY_APPOINTMENT_ID),submission.get(s.SUBMITTED_AT).toInstant());
        byte[] targetHash=binding.get(b.TARGET_HASH);
        return new Reference(new Subject("evidence.evidence_submission",id,null,hash),new Subject("evidence.evidence_binding",binding.get(b.EVIDENCE_BINDING_ID),binding.get(b.REVISION),null),
            new Subject(binding.get(b.TARGET_TYPE),binding.get(b.TARGET_ID),binding.get(b.TARGET_REVISION),targetHash==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(targetHash)),binding.get(b.REVOKED_AT)==null);
    }
}
