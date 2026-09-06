package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.responsibility.R1BusinessTime;
import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
    @ParameterizedTest @CsvSource({
        "1,2026-09-04T08:00:00Z,Asia/Shanghai,2026-09-07T02:00:00Z",
        "2,2026-09-05T08:00:00Z,Asia/Shanghai,2026-09-07T07:00:00Z",
        "1,2026-03-06T17:00:00Z,America/New_York,2026-03-09T14:00:00Z",
        "2,2026-10-30T17:00:00Z,America/New_York,2026-11-02T20:00:00Z"})
    void exact_next_weekday_windows_cross_weekend_and_dst(long n,String at,String zone,String expected){
        var resume=RetryPolicy.resumeAt(n,Instant.parse(at),ZoneId.of(zone));assertEquals(Instant.parse(expected),resume);
        assertEquals(resume.plusSeconds(1800),R1BusinessTime.due(resume,1800,ZoneId.of(zone)));
    }
    @Test void channel_priority_uses_only_controlled_available_contacts(){
        assertEquals("EMAIL",RetryPolicy.channel("PHONE",true,true));assertEquals("PHONE",RetryPolicy.channel("EMAIL",true,true));
        assertEquals("PHONE",RetryPolicy.channel("PHONE",true,false));assertEquals("EMAIL",RetryPolicy.channel("EMAIL",false,true));
        assertThrows(IllegalArgumentException.class,()->RetryPolicy.channel("PHONE",false,false));
        assertThrows(IllegalArgumentException.class,()->RetryPolicy.resumeAt(3,Instant.EPOCH,ZoneId.of("UTC")));
    }
}
