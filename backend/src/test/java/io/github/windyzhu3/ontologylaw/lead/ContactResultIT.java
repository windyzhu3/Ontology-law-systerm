package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class ContactResultIT extends LeadBusinessFixture {
    private Map<String,Object> values(TaskFactory.Task task,String result)throws Exception {
        var values=new TreeMap<String,Object>();
        values.put("leadAssignmentId",scalar("select current_assignment_id from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),task.lead().id()));
        values.put("leadAssignmentRevision",0L);values.put("contactChannelCode","PHONE");values.put("resultCode",result);
        if(result.equals("CONNECTED_VALID"))values.put("legalNeed","Confirmed independent legal need");
        return values;
    }
    @Test void connected_result_completes_task_with_one_opportunity_and_two_notifications()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,true);
        run(capture("contact-connected",true));var task=task("CONTACT_LEAD");var command=command(task,values(task,"CONNECTED_VALID"));
        var lower=java.time.OffsetDateTime.parse(scalar("select clock_timestamp()::text").replace(' ','T'));var before=counts();var receipt=run(command);completed(task,receipt);
        delta(before,List.of(0L,0L,0L,0L,0L,1L,1L,2L,2L,1L));
        assertEquals("1",scalar("select count(*) from lead.lead_contact_result where tenant_id=? and contact_task_id=? and contact_no=1 and result_code='CONNECTED_VALID'",seed.tenant(),task.selector().id()));
        assertEquals("1",scalar("select count(*) from opportunity.opportunity where tenant_id=? and source_contact_result_id=? and source_lead_id=? and revision=0",seed.tenant(),receipt.resultFact().id(),task.lead().id()));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{try(var p=x.prepareStatement("select o.legal_need_ciphertext,o.legal_need_digest,l.legal_need_summary_ciphertext,r.resulted_at,r.created_at,t.completed_at,clock_timestamp() from opportunity.opportunity o join lead.lead l on l.tenant_id=o.tenant_id and l.lead_id=o.source_lead_id join lead.lead_contact_result r on r.tenant_id=o.tenant_id and r.lead_contact_result_id=o.source_contact_result_id join responsibility.task_occurrence t on t.tenant_id=r.tenant_id and t.task_occurrence_id=r.contact_task_id where o.tenant_id=?")){p.setObject(1,seed.tenant());try(var r=p.executeQuery()){assertTrue(r.next());byte[] cipher=r.getBytes(1),key=new byte[32];Arrays.fill(key,(byte)0x51);var aes=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");aes.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,Arrays.copyOfRange(cipher,1,13)));aes.updateAAD(("{\"field\":\"OPPORTUNITY_LEGAL_NEED\",\"profile\":\"R1_LEAD_AES_GCM_V1\",\"tenantId\":\""+seed.tenant()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals("Confirmed independent legal need",new String(aes.doFinal(Arrays.copyOfRange(cipher,13,cipher.length)),java.nio.charset.StandardCharsets.UTF_8));assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest("Confirmed independent legal need".getBytes(java.nio.charset.StandardCharsets.UTF_8)),r.getBytes(2));assertFalse(Arrays.equals(cipher,r.getBytes(3)));var at=r.getObject(4,java.time.OffsetDateTime.class);assertFalse(at.isBefore(lower));assertFalse(at.isAfter(r.getObject(7,java.time.OffsetDateTime.class)));assertEquals(at,r.getObject(5,java.time.OffsetDateTime.class));assertEquals(at,r.getObject(6,java.time.OffsetDateTime.class));}}catch(java.security.GeneralSecurityException ex){throw new java.sql.SQLException(ex);}return null;});}
        var after=counts();assertEquals(receipt,run(command));assertEquals(after,counts());
    }
    @Test void first_unconnected_result_creates_waiting_retry_with_receipt()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,true);
        run(capture("contact-retry",true));var task=task("CONTACT_LEAD");var command=command(task,values(task,"NOT_CONNECTED"));
        var before=counts();var receipt=run(command);completed(task,receipt);
        delta(before,List.of(0L,0L,0L,1L,1L,1L,1L,1L,1L,1L));
        var successor=task("CONTACT_LEAD");assertEquals("WAITING",successor.state());assertEquals(1L,successor.selector().revision());
        assertNotEquals(task.selector().id(),successor.selector().id());
        assertEquals("CONTACT_RETRY_V1",scalar("select wait_contract_code from responsibility.wait_receipt where tenant_id=? and task_occurrence_id=?",seed.tenant(),successor.selector().id()));
    }
    @Test void suspect_result_creates_one_supervisor_review_without_wait()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,true);
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_VALIDITY_REVIEW',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org());
        run(capture("contact-suspect",true));var task=task("CONTACT_LEAD");var command=command(task,values(task,"SUSPECT_INVALID"));
        var before=counts();var receipt=run(command);completed(task,receipt);
        delta(before,List.of(0L,0L,0L,0L,1L,1L,1L,1L,1L,1L));assertEquals("OPEN",task("REVIEW_LEAD_VALIDITY").state());
    }
}
