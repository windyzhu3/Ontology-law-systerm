package io.github.windyzhu3.ontologylaw.contract;

import java.util.List;
import java.util.Set;

/** Stage vocabulary only. Owner must revalidate exact facts, task ownership and authority. */
public final class ManualSignatureProtocol {
    private ManualSignatureProtocol() {}
    public enum Stage { ARRANGE,COLLECT,AWAIT_VERIFICATION,SUPPLEMENT,PARTIAL,ARCHIVE,SIGNATURE_COMPLETE,REVISION_REQUIRED }
    public enum Action { CONFIRM_ARRANGEMENT,SAVE_DRAFT,SUBMIT_EVIDENCE,CONFIRM_ARCHIVE,RETURN_FOR_REVISION }
    public enum Outcome { VERIFIED,NEED_EVIDENCE,CONTENT_CHANGED,ARRANGEMENT_CORRECTION }
    public static Stage after(Stage stage,Action action){
        if(stage==null||action==null)throw invalid();
        return switch(action){
            case CONFIRM_ARRANGEMENT -> {if(stage!=Stage.ARRANGE)throw invalid();yield Stage.COLLECT;}
            case SAVE_DRAFT -> {if(!Set.of(Stage.ARRANGE,Stage.COLLECT,Stage.SUPPLEMENT,Stage.PARTIAL).contains(stage))throw invalid();yield stage;}
            case SUBMIT_EVIDENCE -> {if(!Set.of(Stage.COLLECT,Stage.SUPPLEMENT,Stage.PARTIAL).contains(stage))throw invalid();yield Stage.AWAIT_VERIFICATION;}
            case CONFIRM_ARCHIVE -> {if(stage!=Stage.ARCHIVE)throw invalid();yield Stage.SIGNATURE_COMPLETE;}
            case RETURN_FOR_REVISION -> {if(!Set.of(Stage.ARRANGE,Stage.COLLECT,Stage.SUPPLEMENT,Stage.PARTIAL,Stage.ARCHIVE).contains(stage))throw invalid();yield Stage.REVISION_REQUIRED;}
        };
    }
    /** verified must be an Owner projection of exact, current persisted facts, not client booleans. */
    public static Stage afterVerification(Stage stage,Outcome outcome,ManualSignaturePlan plan,List<ManualSignaturePlan.VerifiedSlot> verified){
        if(stage!=Stage.AWAIT_VERIFICATION||outcome==null||plan==null||verified==null)throw invalid();
        var remaining=plan.remainingRequired(verified);
        return switch(outcome){
            case NEED_EVIDENCE -> Stage.SUPPLEMENT;
            case CONTENT_CHANGED -> Stage.REVISION_REQUIRED;
            case ARRANGEMENT_CORRECTION -> Stage.ARRANGE;
            case VERIFIED -> {if(verified.isEmpty())throw invalid();yield remaining.isEmpty()?Stage.ARCHIVE:Stage.PARTIAL;}
        };
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid manual signature transition");}
}
