package io.github.windyzhu3.ontologylaw.lead;

import java.time.*;

/** Initial global ordinals only. Supervisor reopening does not reset this budget. */
public final class RetryPolicy {
    private RetryPolicy(){}
    public static Instant resumeAt(long number,Instant now,ZoneId zone){
        if(number!=1&&number!=2)throw new IllegalArgumentException("No automatic retry budget");
        LocalDate day=now.atZone(zone).toLocalDate();
        do{day=day.plusDays(1);}while(day.getDayOfWeek().getValue()>5);
        return day.atTime(number==1?10:15,0).atZone(zone).withEarlierOffsetAtOverlap().toInstant();
    }
    public static String channel(String previous,boolean phone,boolean email){
        if("PHONE".equals(previous)&&email)return "EMAIL";
        if("EMAIL".equals(previous)&&phone)return "PHONE";
        if("PHONE".equals(previous)&&phone||"EMAIL".equals(previous)&&email)return previous;
        if(phone)return "PHONE";if(email)return "EMAIL";
        throw new IllegalArgumentException("No captured contact channel");
    }
}
