package io.github.windyzhu3.ontologylaw.party;

import java.sql.*;import java.util.*;
/** Party-owned exact identity maintenance; never rebinds Lead or historical references. */
public interface CustomerPartyProfiles {
 record Selector(UUID id,long revision){}
 record Profile(Selector selector,String kind,String name){}
 record Version(Selector selector,Profile party){}
 Profile current(Connection c,UUID tenant,UUID id,boolean lock)throws SQLException;
 Profile create(Connection c,UUID tenant,String kind,String name)throws SQLException;
 Profile rename(Connection c,UUID tenant,Selector exact,String name)throws SQLException;
 Version snapshot(Connection c,UUID tenant,Profile profile,UUID actor)throws SQLException;
 Version version(Connection c,UUID tenant,Selector exact)throws SQLException;
 static CustomerPartyProfiles databaseBacked(){return new io.github.windyzhu3.ontologylaw.party.internal.persistence.JdbcCustomerPartyProfiles();}
}
