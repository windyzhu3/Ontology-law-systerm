package io.github.windyzhu3.ontologylaw.contract;
import java.util.*;
/** Conservative exact-identity candidate scan; never a fuzzy-name or legal conclusion engine. */
public final class ContractConflictSnapshot {
 private ContractConflictSnapshot(){}
 public record Party(UUID sourceId,UUID partyId,String role,String snapshotHash){public Party{Objects.requireNonNull(sourceId);Objects.requireNonNull(partyId);Objects.requireNonNull(role);Objects.requireNonNull(snapshotHash);}}
 public record Candidate(Party scope,Party matched){}
 private static boolean known(Party p){return Set.of("CLIENT","OPPONENT").contains(p.role());}
 public static boolean complete(List<Party> scope,boolean unknown){return !unknown&&!scope.isEmpty()&&scope.stream().anyMatch(p->p.role().equals("CLIENT"))&&scope.stream().allMatch(ContractConflictSnapshot::known);}
 public static boolean complete(List<Party> scope,List<Party> corpus,boolean unknown){return complete(scope,unknown)&&corpus.stream().filter(p->scope.stream().anyMatch(s->s.partyId().equals(p.partyId()))).allMatch(ContractConflictSnapshot::known);}
 public static List<Candidate> candidates(List<Party> scope,List<Party> corpus){var out=new ArrayList<Candidate>();for(var p:scope)for(var match:corpus)if(p.partyId().equals(match.partyId())&&known(p)&&known(match)&&!p.role().equals(match.role()))out.add(new Candidate(p,match));return List.copyOf(out);}
}
