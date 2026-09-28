package io.github.windyzhu3.ontologylaw.contract;

import java.time.Instant;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.contract.ContractInputValidation.*;

/** Structural rules only; not an approval, authority, material scan or persisted signature proof. */
public record ManualSignaturePlan(Basis basis,String requirementsDigest,List<Slot> slots) {
    public ManualSignaturePlan {
        required(basis);ContractInputValidation.digest(requirementsDigest);
        if(slots==null||slots.isEmpty()||slots.size()>100||slots.stream().anyMatch(Objects::isNull))throw invalid();
        slots=slots.stream().sorted(Comparator.comparingInt(Slot::number)).toList();
        if(slots.stream().noneMatch(Slot::required)||slots.stream().map(Slot::number).distinct().count()!=slots.size()||slots.stream().map(Slot::authoritySlot).distinct().count()!=slots.size())throw invalid();
        // N1 registers each approved party once, with its combined signing/sealing requirements.
        if(slots.stream().map(Slot::partyId).distinct().count()!=slots.size()
                ||slots.stream().map(Slot::participationId).distinct().count()!=slots.size())throw invalid();
    }
    public record Basis(UUID tenantId,UUID contractId,UUID revisionId,UUID readinessId,String bodySha256,String partySnapshotDigest,String reviewDigest,String approvalSetDigest) {
        public Basis {required(tenantId);required(contractId);required(revisionId);required(readinessId);ContractInputValidation.digest(bodySha256);ContractInputValidation.digest(partySnapshotDigest);ContractInputValidation.digest(reviewDigest);ContractInputValidation.digest(approvalSetDigest);}
        Map<String,Object> canonical(){return Map.of("tenantId",tenantId.toString(),"contractId",contractId.toString(),"revisionId",revisionId.toString(),"readinessId",readinessId.toString(),"bodySha256",bodySha256,"partySnapshotDigest",partySnapshotDigest,"reviewDigest",reviewDigest,"approvalSetDigest",approvalSetDigest);}
        @Override public String toString(){return "ManualSignatureBasis[protected]";}
    }
    public record Slot(int number,UUID participationId,UUID partyId,String authoritySlot,boolean required,boolean signatureRequired,boolean sealRequired,String clauseBasis) {
        public Slot {if(number<1||number>100||authoritySlot==null||!authoritySlot.matches("[A-Z][A-Z0-9_]{0,63}")||!signatureRequired&&!sealRequired)throw invalid();ContractInputValidation.required(participationId);ContractInputValidation.required(partyId);clauseBasis=text(clauseBasis,2000);}
        Map<String,Object> canonical(){return Map.of("number",number,"participationId",participationId.toString(),"partyId",partyId.toString(),"authoritySlot",authoritySlot,"required",required,"signatureRequired",signatureRequired,"sealRequired",sealRequired,"clauseBasis",clauseBasis);}
        @Override public String toString(){return "ManualSignatureSlot[protected]";}
    }
    public record Submission(Basis basis,String planDigest,int slotNumber,UUID materialVersionId,String materialSha256,UUID authorityMaterialVersionId,String authorityMaterialSha256,String signerName,Instant signedAt) {
        public Submission {required(basis);ContractInputValidation.digest(planDigest);if(slotNumber<1||slotNumber>100)throw invalid();required(materialVersionId);ContractInputValidation.digest(materialSha256);required(authorityMaterialVersionId);ContractInputValidation.digest(authorityMaterialSha256);signerName=text(signerName,200);time(signedAt);}
        /** Uses Owner-supplied current facts/time. Does not establish authorization or evidence usability. */
        public void requireCurrent(ManualSignaturePlan plan,Basis current,Instant now){
            required(plan);required(current);time(now);
            if(!basis.equals(current)||!basis.equals(plan.basis)||!planDigest.equals(plan.digest())||signedAt.isAfter(now)||plan.slots.stream().noneMatch(s->s.number==slotNumber))throw invalid();
        }
        @Override public String toString(){return "ManualSignatureSubmission[protected]";}
    }
    /** Internal projection of persisted facts, never a client command or a substitute for verification. */
    public record VerifiedSlot(String planDigest,int slotNumber,UUID signatureId,String approvedBodySha256,String signedMaterialSha256,boolean signatureVerified,boolean sealVerified) {
        public VerifiedSlot {ContractInputValidation.digest(planDigest);if(slotNumber<1||slotNumber>100)throw invalid();required(signatureId);ContractInputValidation.digest(approvedBodySha256);ContractInputValidation.digest(signedMaterialSha256);}
        @Override public String toString(){return "ManualSignatureVerification[protected]";}
    }
    public String digest(){return HexFormat.of().formatHex(ContractCanonicalJson.digest(ContractCanonicalJson.encode(Map.of("format","R2_MANUAL_SIGNATURE_V1","basis",basis.canonical(),"requirementsDigest",requirementsDigest,"slots",slots.stream().map(Slot::canonical).toList()))));}
    public Set<Integer> remainingRequired(List<VerifiedSlot> verified){
        if(verified==null||verified.size()>slots.size()||verified.stream().anyMatch(Objects::isNull))throw invalid();
        var bySlot=new HashMap<Integer,VerifiedSlot>();var ids=new HashSet<UUID>();String exact=digest();
        for(var fact:verified){
            if(!exact.equals(fact.planDigest)||!basis.bodySha256.equals(fact.approvedBodySha256)||slots.stream().noneMatch(s->s.number==fact.slotNumber)||bySlot.putIfAbsent(fact.slotNumber,fact)!=null||!ids.add(fact.signatureId))throw invalid();
        }
        var remaining=new TreeSet<Integer>();
        for(var slot:slots){var fact=bySlot.get(slot.number);if(slot.required&&(fact==null||slot.signatureRequired&&!fact.signatureVerified||slot.sealRequired&&!fact.sealVerified))remaining.add(slot.number);}
        return Collections.unmodifiableSet(remaining);
    }
    @Override public String toString(){return "ManualSignaturePlan[protected]";}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid exact manual signature input");}
}
