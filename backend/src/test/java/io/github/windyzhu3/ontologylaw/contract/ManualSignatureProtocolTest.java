package io.github.windyzhu3.ontologylaw.contract;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.contract.ManualSignatureProtocol.*;

class ManualSignatureProtocolTest {
    final ManualSignaturePlanTest fixture=new ManualSignaturePlanTest();
    @Test void submission_does_not_confirm_signature_and_drafts_do_not_advance(){
        assertEquals(Stage.COLLECT,after(Stage.ARRANGE,Action.CONFIRM_ARRANGEMENT));
        for(var stage:List.of(Stage.ARRANGE,Stage.COLLECT,Stage.SUPPLEMENT,Stage.PARTIAL))assertEquals(stage,after(stage,Action.SAVE_DRAFT));
        assertEquals(Stage.AWAIT_VERIFICATION,after(Stage.COLLECT,Action.SUBMIT_EVIDENCE));
        assertThrows(IllegalArgumentException.class,()->after(Stage.AWAIT_VERIFICATION,Action.SUBMIT_EVIDENCE));
        assertThrows(IllegalArgumentException.class,()->after(Stage.ARRANGE,Action.SUBMIT_EVIDENCE));
    }
    @Test void supplement_and_content_change_have_distinct_paths(){
        var p=fixture.plan();
        assertEquals(Stage.SUPPLEMENT,afterVerification(Stage.AWAIT_VERIFICATION,Outcome.NEED_EVIDENCE,p,List.of()));
        assertEquals(Stage.AWAIT_VERIFICATION,after(Stage.SUPPLEMENT,Action.SUBMIT_EVIDENCE));
        assertEquals(Stage.REVISION_REQUIRED,afterVerification(Stage.AWAIT_VERIFICATION,Outcome.CONTENT_CHANGED,p,List.of()));
        for(var action:Action.values())assertThrows(IllegalArgumentException.class,()->after(Stage.REVISION_REQUIRED,action));
    }
    @Test void every_required_slot_and_seal_precedes_archive_and_completion(){
        var p=fixture.plan();var client=fixture.verified(p,1,true);var firm=fixture.verified(p,2,true);
        assertEquals(Stage.PARTIAL,afterVerification(Stage.AWAIT_VERIFICATION,Outcome.VERIFIED,p,List.of(client)));
        assertThrows(IllegalArgumentException.class,()->after(Stage.PARTIAL,Action.CONFIRM_ARCHIVE));
        assertEquals(Stage.ARCHIVE,afterVerification(Stage.AWAIT_VERIFICATION,Outcome.VERIFIED,p,List.of(client,firm)));
        assertEquals(Stage.SIGNATURE_COMPLETE,after(Stage.ARCHIVE,Action.CONFIRM_ARCHIVE));
        for(var action:Action.values())assertThrows(IllegalArgumentException.class,()->after(Stage.SIGNATURE_COMPLETE,action));
    }
    @Test void fabricated_or_out_of_stage_verification_cannot_advance(){
        var p=fixture.plan();assertThrows(IllegalArgumentException.class,()->afterVerification(Stage.COLLECT,Outcome.VERIFIED,p,List.of(fixture.verified(p,1,true))));
        assertThrows(IllegalArgumentException.class,()->afterVerification(Stage.AWAIT_VERIFICATION,Outcome.VERIFIED,p,List.of()));
        assertThrows(IllegalArgumentException.class,()->afterVerification(Stage.AWAIT_VERIFICATION,null,p,List.of()));
        assertThrows(IllegalArgumentException.class,()->after(null,Action.SAVE_DRAFT));
    }
    @Test void changed_arrangement_returns_to_existing_contract_revision_not_direct_resubmission(){
        assertEquals(Stage.REVISION_REQUIRED,after(Stage.ARRANGE,Action.RETURN_FOR_REVISION));
        assertEquals(Stage.REVISION_REQUIRED,after(Stage.ARCHIVE,Action.RETURN_FOR_REVISION));
        assertThrows(IllegalArgumentException.class,()->after(Stage.REVISION_REQUIRED,Action.CONFIRM_ARRANGEMENT));
    }
    @Test void correcting_a_registration_returns_to_arrangement_without_changing_approved_body(){
        var p=fixture.plan();var prior=fixture.verified(p,1,true);
        assertEquals(Stage.ARRANGE,afterVerification(Stage.AWAIT_VERIFICATION,Outcome.ARRANGEMENT_CORRECTION,p,List.of(prior)));
        var corrected=new ManualSignaturePlan(p.basis(),ManualSignaturePlanTest.REVIEW,p.slots());
        assertEquals(p.basis().bodySha256(),corrected.basis().bodySha256());
        assertThrows(IllegalArgumentException.class,()->corrected.remainingRequired(List.of(prior)));
    }
}
