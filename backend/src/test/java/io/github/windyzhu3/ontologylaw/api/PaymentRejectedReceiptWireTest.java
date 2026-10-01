package io.github.windyzhu3.ontologylaw.api;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.RejectedCommandReceipt;

class PaymentRejectedReceiptWireTest {
    @Test void original_duplicate_payment_rejection_remains_readable() {
        var command=UUID.randomUUID();
        var receipt=UUID.randomUUID();
        var body=Map.<String,Object>of("commandId",command.toString(),"receiptId",receipt.toString(),
            "outcome","REJECTED","completedAt","2026-10-01T10:00:00Z","rejectionCode","PAYMENT_ALREADY_RECORDED");
        var result=assertInstanceOf(RejectedCommandReceipt.class,
            assertDoesNotThrow(()->R1WireModels.receipt(body)));
        assertEquals(command,result.getCommandId());
        assertEquals(receipt,result.getReceiptId());
        assertEquals("PAYMENT_ALREADY_RECORDED",result.getRejectionCode().getValue());
    }
}
