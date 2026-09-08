package io.github.windyzhu3.ontologylaw.party;
import java.sql.*;import java.util.UUID;
public interface R1PartyReader {
    record ExactParty(UUID id,long revision) {}
    record NamedParty(UUID id,long revision,String canonicalName,String status) {}
    NamedParty named(Connection c,UUID tenant,UUID id)throws SQLException;
    ExactParty active(Connection c,UUID tenant,UUID id)throws SQLException;
    static R1PartyReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.party.internal.persistence.JooqR1PartyReader();}
}
