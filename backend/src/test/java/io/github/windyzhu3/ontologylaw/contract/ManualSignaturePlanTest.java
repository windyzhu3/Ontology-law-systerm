package io.github.windyzhu3.ontologylaw.contract;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.contract.ManualSignaturePlan.*;

class ManualSignaturePlanTest {
    static final String BODY="ab".repeat(32),PARTIES="cd".repeat(32),REVIEW="ef".repeat(32),APPROVAL="12".repeat(32),SIGNED="34".repeat(32),AUTHORITY="56".repeat(32);
    final Basis basis=new Basis(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BODY,PARTIES,REVIEW,APPROVAL);
    final Slot client=new Slot(1,UUID.randomUUID(),UUID.randomUUID(),"CLIENT_MANUAL",true,true,true,"Approved clause 8");
    final Slot firm=new Slot(2,UUID.randomUUID(),UUID.randomUUID(),"FIRM_MANUAL",true,true,true,"Approved clause 8");
    final Instant now=Instant.parse("2026-09-23T06:00:00Z");
    ManualSignaturePlan plan(){return new ManualSignaturePlan(basis,PARTIES,List.of(client,firm));}
    Submission submission(ManualSignaturePlan p,Instant signed){return new Submission(basis,p.digest(),1,UUID.randomUUID(),SIGNED,UUID.randomUUID(),AUTHORITY,"合成授权代表",signed);}
    VerifiedSlot verified(ManualSignaturePlan p,int n,boolean seal){return new VerifiedSlot(p.digest(),n,UUID.randomUUID(),BODY,SIGNED,true,seal);}

    @Test void plan_is_immutable_and_canonical_slot_order_is_stable(){
        var slots=new ArrayList<>(List.of(firm,client));var p=new ManualSignaturePlan(basis,PARTIES,slots);slots.clear();assertEquals(2,p.slots().size());assertEquals(plan().digest(),p.digest());assertThrows(UnsupportedOperationException.class,()->p.slots().clear());
    }
    @Test void empty_duplicate_and_all_optional_plans_cannot_claim_complete(){
        assertThrows(IllegalArgumentException.class,()->new ManualSignaturePlan(basis,PARTIES,List.of()));
        assertThrows(IllegalArgumentException.class,()->new ManualSignaturePlan(basis,PARTIES,List.of(client,client)));
        var duplicateAuthority=new Slot(2,UUID.randomUUID(),UUID.randomUUID(),client.authoritySlot(),true,true,false,"Approved clause 8");
        assertThrows(IllegalArgumentException.class,()->new ManualSignaturePlan(basis,PARTIES,List.of(client,duplicateAuthority)));
        assertThrows(IllegalArgumentException.class,()->new ManualSignaturePlan(basis,PARTIES,List.of(new Slot(1,client.participationId(),client.partyId(),"OPTIONAL",false,true,false,"Approved clause 8"))));
    }
    @Test void changing_any_approval_binding_or_requirement_changes_plan_identity(){
        var other=new Basis(basis.tenantId(),basis.contractId(),basis.revisionId(),UUID.randomUUID(),BODY,PARTIES,REVIEW,APPROVAL);
        assertNotEquals(plan().digest(),new ManualSignaturePlan(other,PARTIES,List.of(client,firm)).digest());
        assertNotEquals(plan().digest(),new ManualSignaturePlan(basis,REVIEW,List.of(client,firm)).digest());
        var noSeal=new Slot(1,client.participationId(),client.partyId(),client.authoritySlot(),true,true,false,"Approved clause 8");
        assertNotEquals(plan().digest(),new ManualSignaturePlan(basis,PARTIES,List.of(noSeal,firm)).digest());
    }
    @Test void signed_bytes_may_differ_from_unsigned_body_but_submission_must_bind_exact_plan(){
        var p=plan();var s=submission(p,now.minusSeconds(3600));assertNotEquals(BODY,s.materialSha256());assertDoesNotThrow(()->s.requireCurrent(p,basis,now));
        var newer=new Basis(basis.tenantId(),basis.contractId(),UUID.randomUUID(),basis.readinessId(),BODY,PARTIES,REVIEW,APPROVAL);
        assertThrows(IllegalArgumentException.class,()->s.requireCurrent(p,newer,now));
        assertThrows(IllegalArgumentException.class,()->s.requireCurrent(new ManualSignaturePlan(basis,REVIEW,List.of(client,firm)),basis,now));
    }
    @Test void future_time_invalid_unicode_missing_authority_and_unknown_slot_are_rejected(){
        var p=plan();assertThrows(IllegalArgumentException.class,()->submission(p,now.plusSeconds(1)).requireCurrent(p,basis,now));
        assertThrows(IllegalArgumentException.class,()->new Submission(basis,p.digest(),1,UUID.randomUUID(),SIGNED,null,AUTHORITY,"代表",now));
        assertThrows(IllegalArgumentException.class,()->new Submission(basis,p.digest(),1,UUID.randomUUID(),SIGNED,UUID.randomUUID(),AUTHORITY,"\ud800",now));
        var unknown=new Submission(basis,p.digest(),9,UUID.randomUUID(),SIGNED,UUID.randomUUID(),AUTHORITY,"代表",now);
        assertThrows(IllegalArgumentException.class,()->unknown.requireCurrent(p,basis,now));
    }
    @Test void partial_verification_or_missing_seal_never_satisfies_the_whole_plan(){
        var p=plan();assertEquals(Set.of(1,2),p.remainingRequired(List.of()));assertEquals(Set.of(2),p.remainingRequired(List.of(verified(p,1,true))));
        assertEquals(Set.of(1),p.remainingRequired(List.of(verified(p,1,false),verified(p,2,true))));
        assertTrue(p.remainingRequired(List.of(verified(p,2,true),verified(p,1,true))).isEmpty());
    }
    @Test void stale_content_foreign_plan_and_duplicate_signature_facts_cannot_satisfy_slots(){
        var p=plan();var a=verified(p,1,true);
        assertThrows(IllegalArgumentException.class,()->p.remainingRequired(List.of(a,a)));
        assertThrows(IllegalArgumentException.class,()->p.remainingRequired(List.of(a,new VerifiedSlot(p.digest(),2,a.signatureId(),BODY,SIGNED,true,true))));
        assertThrows(IllegalArgumentException.class,()->p.remainingRequired(List.of(new VerifiedSlot(p.digest(),1,UUID.randomUUID(),REVIEW,SIGNED,true,true))));
        assertThrows(IllegalArgumentException.class,()->p.remainingRequired(List.of(new VerifiedSlot(REVIEW,1,UUID.randomUUID(),BODY,SIGNED,true,true))));
    }
    @Test void seal_only_and_handwritten_requirements_are_distinct_and_never_implicitly_satisfied(){
        var sealOnly=new Slot(1,client.participationId(),client.partyId(),"CLIENT_MANUAL",true,false,true,"Approved clause 8");
        var sealed=new ManualSignaturePlan(basis,PARTIES,List.of(sealOnly));
        assertTrue(sealed.remainingRequired(List.of(new VerifiedSlot(sealed.digest(),1,UUID.randomUUID(),BODY,SIGNED,false,true))).isEmpty());
        var handwritten=new ManualSignaturePlan(basis,PARTIES,List.of(client));
        assertEquals(Set.of(1),handwritten.remainingRequired(List.of(new VerifiedSlot(handwritten.digest(),1,UUID.randomUUID(),BODY,SIGNED,false,true))));
        assertNotEquals(sealed.digest(),handwritten.digest());
        assertThrows(IllegalArgumentException.class,()->new Slot(1,client.participationId(),client.partyId(),"CLIENT_MANUAL",true,false,false,"Approved clause 8"));
    }
    @Test void clause_basis_is_required_and_bound_to_the_confirmed_arrangement(){
        assertThrows(IllegalArgumentException.class,()->new Slot(1,client.participationId(),client.partyId(),client.authoritySlot(),true,true,true," "));
        var changed=new Slot(1,client.participationId(),client.partyId(),client.authoritySlot(),true,true,true,"Approved clause 9");
        assertNotEquals(plan().digest(),new ManualSignaturePlan(basis,PARTIES,List.of(changed,firm)).digest());
    }
    @Test void the_confirmed_arrangement_rejects_duplicate_parties_even_with_different_slots(){
        var duplicate=new Slot(2,UUID.randomUUID(),client.partyId(),"ANOTHER_SLOT",true,false,true,"Approved clause 8");
        assertThrows(IllegalArgumentException.class,()->new ManualSignaturePlan(basis,PARTIES,List.of(client,duplicate)));
    }
    @Test void one_approved_participation_cannot_be_reused_for_a_different_party(){
        var inconsistent=new Slot(2,client.participationId(),firm.partyId(),"FIRM_MANUAL",true,false,true,"Approved clause 8");
        assertThrows(IllegalArgumentException.class,()->new ManualSignaturePlan(basis,PARTIES,List.of(client,inconsistent)));
    }
    @Test void sensitive_details_are_not_exposed_in_default_logging(){
        assertFalse(submission(plan(),now).toString().contains("合成授权代表"));assertFalse(plan().toString().contains(basis.contractId().toString()));
    }
}
