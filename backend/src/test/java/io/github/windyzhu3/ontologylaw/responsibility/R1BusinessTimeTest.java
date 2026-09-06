package io.github.windyzhu3.ontologylaw.responsibility;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class R1BusinessTimeTest {
    @Test void strict_next_opening_handles_boundary_microseconds_and_weekend() {
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        assertEquals(Instant.parse("2026-09-04T01:00:00Z"),R1BusinessTime.nextWindow(Instant.parse("2026-09-04T00:59:59.999999Z"),zone));
        assertEquals(Instant.parse("2026-09-07T01:00:00Z"),R1BusinessTime.nextWindow(Instant.parse("2026-09-04T01:00:00Z"),zone));
        assertEquals(Instant.parse("2026-09-07T01:00:00Z"),R1BusinessTime.nextWindow(Instant.parse("2026-09-06T02:00:00Z"),zone));
    }
    @Test void frozen_business_seconds_cross_weekend_and_preserve_microseconds() {
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        assertEquals(Instant.parse("2026-09-07T04:00:00.123456Z"),R1BusinessTime.due(Instant.parse("2026-09-04T09:00:00.123456Z"),14400,zone));
        assertEquals(Instant.parse("2026-09-04T10:00:00Z"),R1BusinessTime.due(Instant.parse("2026-09-04T09:00:00Z"),3600,zone));
        assertEquals(Instant.parse("2026-09-07T02:00:00Z"),R1BusinessTime.due(Instant.parse("2026-09-04T10:00:00Z"),3600,zone));
    }
    @Test void dst_weekends_use_local_window_offset_not_twenty_four_hour_steps() {
        ZoneId zone=ZoneId.of("America/New_York");
        assertEquals(Instant.parse("2026-03-09T13:00:00Z"),R1BusinessTime.nextWindow(Instant.parse("2026-03-06T14:00:00Z"),zone));
        assertEquals(Instant.parse("2026-11-02T14:00:00Z"),R1BusinessTime.nextWindow(Instant.parse("2026-10-30T13:00:00Z"),zone));
        assertEquals(Instant.parse("2026-03-09T16:00:00.123456Z"),R1BusinessTime.due(Instant.parse("2026-03-06T22:00:00.123456Z"),14400,zone));
    }
}
