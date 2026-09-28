package io.github.windyzhu3.ontologylaw.evidence;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.time.*;import java.util.*;
public interface MaterialEvidence {
 String BASIS="evidence.material_upload_basis";
 record Basis(Subject selector,Subject opportunity,Subject responsibility,UUID owner,Subject confirmation,Subject previous,Instant createdAt,Instant expiresAt,String purpose,String state,String checkState,String resultCode,UUID receivedObject,UUID objectVersion,String sha256,Long sizeBytes,String mediaType) {}
 record ProtectedBody(byte[] ciphertext,byte[] digest) {}
 ProtectedBody protectedBody(Connection c,UUID tenant,UUID basis)throws SQLException;
 record Submission(UUID source,UUID submission,UUID binding,Instant at) {}
 interface Codec{String encode(Map<String,Object> body);Map<String,Object> decode(String body);}
 Basis open(Connection c,UUID tenant,Subject opportunity,Subject responsibility,UUID owner,Subject confirmation,Subject previous,String purpose,String fileName,String note)throws SQLException;
 Basis basis(Connection c,UUID tenant,UUID id)throws SQLException;
 List<Basis> pending(Connection c,UUID tenant,UUID opportunity,UUID owner)throws SQLException;
 Map<String,Object> body(Connection c,UUID tenant,Basis basis)throws SQLException;
 UUID beginCheck(Connection c,UUID tenant,Basis basis)throws SQLException;
 void completeCheck(Connection c,UUID tenant,Basis basis,UUID check,MaterialObjectStore.StoredObject stored,String status,String reason)throws SQLException;
 Submission accept(Connection c,UUID tenant,Basis basis)throws SQLException;
 static MaterialEvidence databaseBacked(MaterialProtection cipher,Codec codec){return new io.github.windyzhu3.ontologylaw.evidence.internal.persistence.JdbcMaterialEvidence(cipher,codec);}
}

