package io.github.windyzhu3.ontologylaw.contract.internal.persistence;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.Blocked;
class JdbcManualSignatureWorkflowTest {
 @Test void verified_requires_every_independent_attestation(){
  var values=new HashMap<String,Object>();
  for(String key:List.of("contentCorresponds","arrangementComplete","authorityVerified","signatureVerified","materialComplete"))values.put(key,true);
  assertDoesNotThrow(()->JdbcManualSignatureWorkflow.requireVerification(values,true,false));
  for(String key:List.of("contentCorresponds","arrangementComplete","authorityVerified","signatureVerified","materialComplete")){
   var missing=new HashMap<>(values);missing.remove(key);assertThrows(Blocked.class,()->JdbcManualSignatureWorkflow.requireVerification(missing,true,false));
  }
  assertThrows(Blocked.class,()->JdbcManualSignatureWorkflow.requireVerification(values,true,true));
  values.put("sealVerified",true);assertDoesNotThrow(()->JdbcManualSignatureWorkflow.requireVerification(values,true,true));
 }
 @Test void digest_validation(){assertDoesNotThrow(()->JdbcManualSignatureWorkflow.requireDigest("a".repeat(64)));assertThrows(Blocked.class,()->JdbcManualSignatureWorkflow.requireDigest("bad"));}
 @Test void malformed_scalar_and_slot_types_reject_before_owner_casts(){
  assertThrows(Blocked.class,()->JdbcManualSignatureWorkflow.validateValues("SUBMIT_CONTRACT_SIGNATURE",Map.of("slotNumber","1","signerName",false)));
  assertThrows(Blocked.class,()->JdbcManualSignatureWorkflow.validateValues("CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT",Map.of("humanConfirmed",true,"slots",List.of(Map.of("required","true")))));
  assertThrows(Blocked.class,()->JdbcManualSignatureWorkflow.validateValues("RECORD_CONTRACT_SIGNATURE_VERIFICATION",Map.of("decision","VERIFIED","reason",true)));
 }
}
