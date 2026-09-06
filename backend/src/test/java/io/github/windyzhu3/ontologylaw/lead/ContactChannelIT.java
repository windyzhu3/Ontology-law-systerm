package io.github.windyzhu3.ontologylaw.lead;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import org.junit.jupiter.api.Test;
class ContactChannelIT extends ContactFlowFixture {
    @Test void retry_prioritizes_alternate_controlled_channel_and_rejects_repeating_when_alternate_exists()throws Exception{
        bothChannels=true;setupContact();execute(prepare(contact("NOT_CONNECTED")));recoverContact();
        var before=counts();var outcome=execute(prepare(contact("NOT_CONNECTED")));assertEquals(CommandOutcome.Status.REJECTED,outcome.status());assertEquals("VALIDATION_FAILED",outcome.rejectionCode());delta(before,0,0,0,0,0,1,1,1,1,0,0);
    }
    @Test void alternate_channel_can_complete_the_recovered_task()throws Exception{
        bothChannels=true;setupContact();execute(prepare(contact("NOT_CONNECTED")));recoverContact();var values=contact("CONNECTED_VALID");values.put("contactChannelCode","EMAIL");assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(values)).status());
    }
}
