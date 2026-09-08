package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.responsibility.CurrentTaskReader;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Shared review qualification for the production command and CurrentCard. */
public final class ContactCausality {
    private ContactCausality(){}
    @FunctionalInterface public interface ResultReader {CurrentLeadReader.ContactResult read(Connection c,UUID tenant,UUID id)throws SQLException;}
    public static CurrentLeadReader.ContactResult trigger(Connection c,UUID tenant,UUID lead,Instant cutoff,CurrentLeadReader reader)throws SQLException {
        return trigger(c,tenant,lead,cutoff,reader::contactResult);
    }
    public static CurrentLeadReader.ContactResult trigger(Connection c,UUID tenant,UUID lead,Instant cutoff,ResultReader reader)throws SQLException {
        CurrentLeadReader.ContactResult result=null;
        for(var task:CurrentTaskReader.databaseBacked().completedContactTasks(c,tenant,lead)){
            var candidate=reader.read(c,tenant,task.completion().id());
            if(candidate==null||!candidate.selector().equals(task.completion())||!candidate.taskId().equals(task.selector().id())||!candidate.leadId().equals(lead)||candidate.resultedAt().isAfter(cutoff)
                    ||!("SUSPECT_INVALID".equals(candidate.resultCode())||"NOT_CONNECTED".equals(candidate.resultCode())&&candidate.contactNo()>=3))continue;
            if(result==null||candidate.resultedAt().isAfter(result.resultedAt())||candidate.resultedAt().equals(result.resultedAt())&&compare(candidate.selector().id(),result.selector().id())>0)result=candidate;
        }
        return result;
    }
    private static int compare(UUID a,UUID b){int high=Long.compareUnsigned(a.getMostSignificantBits(),b.getMostSignificantBits());return high==0?Long.compareUnsigned(a.getLeastSignificantBits(),b.getLeastSignificantBits()):high;}
}
