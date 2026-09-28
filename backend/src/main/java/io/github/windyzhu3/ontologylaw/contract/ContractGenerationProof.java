package io.github.windyzhu3.ontologylaw.contract;
import java.time.Instant;import java.util.*;
/** Short-lived candidate provenance, never a substitute for current command authorization. */
public final class ContractGenerationProof {
    private final ContractProtection protection;
    public ContractGenerationProof(ContractProtection protection){this.protection=Objects.requireNonNull(protection);}
    public String issue(UUID tenant,UUID opportunity,UUID actor,String basis,String sha,Instant expires){
        digest(basis);digest(sha);Objects.requireNonNull(actor);Objects.requireNonNull(expires);var id=UUID.randomUUID();
        var clear=actor+"\n"+basis+"\n"+sha+"\n"+expires;
        return id+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(protection.seal(tenant,opportunity,id,ContractProtection.Kind.GENERATION,clear));
    }
    public void verify(String token,UUID tenant,UUID opportunity,UUID actor,String basis,String sha,Instant now){
        try{digest(basis);digest(sha);if(token==null||token.length()>2048)throw invalid();var parts=token.split("\\.",-1);if(parts.length!=2)throw invalid();
            var clear=protection.open(tenant,opportunity,UUID.fromString(parts[0]),ContractProtection.Kind.GENERATION,Base64.getUrlDecoder().decode(parts[1]));var fields=clear.split("\n",-1);
            if(fields.length!=4||!actor.toString().equals(fields[0])||!basis.equals(fields[1])||!sha.equals(fields[2])||!now.isBefore(Instant.parse(fields[3])))throw invalid();
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static void digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid contract generation proof");}
}
