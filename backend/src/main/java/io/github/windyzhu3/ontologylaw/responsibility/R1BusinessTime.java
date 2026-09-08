package io.github.windyzhu3.ontologylaw.responsibility;

import java.time.*;

/** Frozen CN_WEEKDAY_V1, local 09:00 inclusive to 18:00 exclusive; preserves microseconds. */
public final class R1BusinessTime {
    private R1BusinessTime() {}
    private static boolean workday(LocalDate day){return day.getDayOfWeek().getValue()<=5;}
    private static ZonedDateTime start(LocalDate day,ZoneId zone){return day.atTime(9,0).atZone(zone).withEarlierOffsetAtOverlap();}
    private static ZonedDateTime next(LocalDate day,ZoneId zone){do{day=day.plusDays(1);}while(!workday(day));return start(day,zone);}
    public static Instant due(Instant origin,long seconds,ZoneId zone) {
        if(seconds<0||origin.getNano()%1000!=0)throw new IllegalArgumentException("Microsecond business time required");
        var cursor=origin.atZone(zone);Duration remaining=Duration.ofSeconds(seconds);
        while(true) {
            LocalDate day=cursor.toLocalDate();var open=start(day,zone);var close=day.atTime(18,0).atZone(zone).withEarlierOffsetAtOverlap();
            if(!workday(day)||!cursor.isBefore(close)){cursor=next(day,zone);continue;}
            if(cursor.isBefore(open))cursor=open;
            Duration available=Duration.between(cursor,close);
            if(remaining.compareTo(available)<=0)return cursor.plus(remaining).toInstant();
            remaining=remaining.minus(available);cursor=next(day,zone);
        }
    }
    public static Instant nextWindow(Instant now,ZoneId zone) {
        var current=now.atZone(zone);var opening=start(current.toLocalDate(),zone);
        return workday(current.toLocalDate())&&current.isBefore(opening)?opening.toInstant():next(current.toLocalDate(),zone).toInstant();
    }
}
