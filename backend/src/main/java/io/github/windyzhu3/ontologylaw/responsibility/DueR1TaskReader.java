package io.github.windyzhu3.ontologylaw.responsibility;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Bounded keyset scan, SQL-prefiltered by identity-owned authorized Appointment selection. */
public interface DueR1TaskReader {
    record Position(Instant dueAt,UUID taskId) {}
    List<Position> scan(Connection c,UUID tenant,TaskFactory.Type type,Set<UUID> ownerAppointments,Instant observedAt,Position after,int limit)throws SQLException;
    static DueR1TaskReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqDueR1TaskReader();}
}
