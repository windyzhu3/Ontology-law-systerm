package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.util.*;
import java.sql.SQLException;

/** Object reads and rendering occur between two short, audited business snapshots. */
final class R2ContractGenerationService {
    @FunctionalInterface interface Snapshot {ContractWorkflowService.Generation read()throws SQLException;}
    private final MaterialObjectStore objects;private final ContractGenerationProof proofs;
    R2ContractGenerationService(MaterialObjectStore objects,ContractProtection protection){this.objects=objects;this.proofs=protection==null?null:new ContractGenerationProof(protection);}
    Map<String,Object> generate(Actor actor,UUID opportunity,Snapshot snapshot)throws SQLException {
        if(objects==null||proofs==null)throw new R1HttpFailure("SERVICE_UNAVAILABLE");
        var before=snapshot.read();byte[] pdf;String preview;
        try{
            pdf=ContractDocumentRenderer.render(objects.read(before.template().objectVersion(),before.template().sha256()),before.fields());
            try(var document=org.apache.pdfbox.Loader.loadPDF(pdf)){preview=new org.apache.pdfbox.text.PDFTextStripper().getText(document);}
        }catch(java.io.IOException unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}catch(IllegalArgumentException invalid){throw new R1HttpFailure("VALIDATION_FAILED");}
        var after=snapshot.read();if(!before.basisDigest().equals(after.basisDigest())||!before.template().equals(after.template()))throw new R1HttpFailure("CONTRACT_VERSION_BASIS_CHANGED");
        String sha;try{sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pdf));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        return Map.of("pdfBase64",Base64.getEncoder().encodeToString(pdf),"previewText",preview,"bodySha256",sha,"generationProof",proofs.issue(actor.tenantId(),opportunity,actor.appointmentId(),after.basisDigest(),sha,after.expiresAt()),"expiresAt",after.expiresAt().toString());
    }
}
