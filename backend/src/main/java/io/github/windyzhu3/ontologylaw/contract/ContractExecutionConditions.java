package io.github.windyzhu3.ontologylaw.contract;

import java.util.*;
import static io.github.windyzhu3.ontologylaw.contract.ContractInputValidation.required;

/** Calculation over Owner-resolved facts only. This neither authorizes an actor nor creates
 * execution, receipt, task or transfer facts. Never accept these projections from an API caller. */
public final class ContractExecutionConditions {
    private static final long MAX_MINOR=9007199254740991L;
    private ContractExecutionConditions() {}

    public enum Status { AWAIT_CONDITION_VERIFICATION, AWAIT_REQUIRED_RECEIPT, READY_FOR_TRANSFER_PREPARATION }

    /** Exact archived version, resolved under the Contract Owner transaction lock. */
    public record Basis(UUID tenantId,UUID contractId,UUID revisionId,UUID archiveId,UUID handoffId) {
        public Basis {required(tenantId);required(contractId);required(revisionId);required(archiveId);required(handoffId);}
    }

    /** Persisted, verified RECEIPT projection; uploaded evidence alone is never a Receipt. */
    public record Receipt(UUID id,UUID tenantId,UUID contractId,UUID revisionId,String currency,long amountMinor) {
        public Receipt {
            required(id);required(tenantId);required(contractId);required(revisionId);
            if(currency==null||!currency.matches("[A-Z]{3}")||amountMinor<=0||amountMinor>MAX_MINOR)throw invalid();
        }
    }

    public record Result(Status status,long confirmedMinor,long remainingMinor,List<UUID> receiptIds) {
        public Result {required(status);receiptIds=List.copyOf(receiptIds);}
    }

    /** Non-prepay finance duties remain independent: READY does not complete those duties.
     * conditionsVerified must be derived from persisted human verification of this exact basis. */
    public static Result evaluate(Basis basis,ContractVersionInput.PaymentGate gate,String currency,
                                  boolean conditionsVerified,List<Receipt> receipts) {
        required(basis);required(gate);required(receipts);
        if(!"CNY".equals(currency))throw invalid();
        Set<UUID> ids=new HashSet<>();long total=0;
        for(var receipt:receipts){
            if(receipt==null||!basis.tenantId().equals(receipt.tenantId())
                    ||!basis.contractId().equals(receipt.contractId())
                    ||!basis.revisionId().equals(receipt.revisionId())
                    ||!currency.equals(receipt.currency())||!ids.add(receipt.id()))throw invalid();
            if(receipt.amountMinor()>MAX_MINOR-total)throw invalid();
            total+=receipt.amountMinor();
        }
        long remaining=gate.receiptRequiredBeforeTransfer()?Math.max(0,gate.requiredMinor()-total):0;
        Status status=remaining>0?Status.AWAIT_REQUIRED_RECEIPT
                :!conditionsVerified?Status.AWAIT_CONDITION_VERIFICATION:Status.READY_FOR_TRANSFER_PREPARATION;
        // Canonical UUID byte order, independent of database/collection iteration order.
        var ordered=ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        return new Result(status,total,remaining,ordered);
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Exact verified contract receipt facts required");}
}
