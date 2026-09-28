package io.github.windyzhu3.ontologylaw.worker;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.security.*;

/** Closed, versioned technical encoding. Never deserialize executable Java objects. */
final class R2OpportunityCheckpointCodec {
    private static final int LEGACY_MAGIC=0x52324331;
    private static final int CONTRACT_MAGIC=0x52324333;
    private static final int MAGIC=0x52324332;
    private R2OpportunityCheckpointCodec(){}
    private static byte[] digest(byte[] body){try{return MessageDigest.getInstance("SHA-256").digest(body);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void nullable(DataOutputStream out,String value)throws IOException {out.writeBoolean(value!=null);if(value!=null)out.writeUTF(value);}
    private static String cursor(DataInputStream in)throws IOException {if(!in.readBoolean())return null;var value=in.readUTF();if(value.isEmpty()||value.length()>2048)throw new IOException("Invalid checkpoint cursor");return value;}
    private static void uuid(DataOutputStream out,UUID id)throws IOException {out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException {return new UUID(in.readLong(),in.readLong());}
    static byte[] encode(R2OpportunityTaskScheduler.State s,InternalApiClient.OpportunityKind kind)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            out.writeInt(kind==InternalApiClient.OpportunityKind.CONTRACT_PREPARATION?CONTRACT_MAGIC:MAGIC);out.writeUTF(kind.name());nullable(out,s.cursor);
            out.writeInt(s.index);out.writeInt(s.failures);out.writeInt(s.lastStatus);out.writeLong(s.acknowledged);out.writeLong(s.rejected);
            out.writeBoolean(s.blocked);out.writeBoolean(s.active);out.writeBoolean(s.uncertain);out.writeBoolean(s.probe);
            out.writeUTF(s.retryAt.toString());out.writeUTF(s.snapshot.status().name());out.writeBoolean(s.page!=null);
            if(s.page!=null){nullable(out,s.page.nextCursor());if(kind==InternalApiClient.OpportunityKind.OWNER_EXCEPTION)out.writeInt(s.page.diagnostics());out.writeInt(s.page.candidates().size());for(var c:s.page.candidates()){
                if(c.kind()!=kind)throw new IOException("Mixed checkpoint kind");
                uuid(out,c.idempotencyKey());uuid(out,c.opportunityId());out.writeLong(c.expectedOpportunityRevision());
                if(c instanceof InternalApiClient.ContractPreparationCandidate contract){out.writeUTF(contract.sourceKind());uuid(out,contract.basisId());out.writeLong(contract.basisRevision());uuid(out,contract.sourceId());out.writeBoolean(contract.workflowId()!=null);if(contract.workflowId()!=null)uuid(out,contract.workflowId());}
                if(c instanceof InternalApiClient.DueOpportunityCandidate due){
                    uuid(out,due.taskId());out.writeLong(due.expectedTaskRevision());uuid(out,due.waitReceiptId());out.writeUTF(due.waitReceiptHash());uuid(out,due.progressId());out.writeUTF(due.progressHash());out.writeUTF(due.dueCutoff());
                }
            }}
        }
        byte[] payload=bytes.toByteArray();bytes.write(digest(payload));
        if(bytes.size()>65536)throw new IOException("Checkpoint too large");return bytes.toByteArray();
    }
    static void restore(R2OpportunityTaskScheduler.State target,byte[] body,InternalApiClient.OpportunityKind kind)throws IOException {
        var s=new R2OpportunityTaskScheduler.State();
        if(body!=null){
            if(body.length<=32||body.length>65536)throw new IOException("Invalid checkpoint size");
            byte[] payload=Arrays.copyOf(body,body.length-32);
            if(!MessageDigest.isEqual(digest(payload),Arrays.copyOfRange(body,body.length-32,body.length)))throw new IOException("Checkpoint checksum mismatch");
            try(var in=new DataInputStream(new ByteArrayInputStream(payload))){
                int version=in.readInt();
                if((version!=MAGIC&&version!=LEGACY_MAGIC&&version!=CONTRACT_MAGIC)||(version==LEGACY_MAGIC&&(kind==InternalApiClient.OpportunityKind.OWNER_EXCEPTION||kind==InternalApiClient.OpportunityKind.CONTRACT_PREPARATION))||(kind==InternalApiClient.OpportunityKind.CONTRACT_PREPARATION)!=(version==CONTRACT_MAGIC)||!in.readUTF().equals(kind.name()))throw new IOException("Unknown checkpoint format");
                s.cursor=cursor(in);s.index=in.readInt();s.failures=in.readInt();s.lastStatus=in.readInt();s.acknowledged=in.readLong();s.rejected=in.readLong();
                s.blocked=in.readBoolean();s.active=in.readBoolean();s.uncertain=in.readBoolean();s.probe=in.readBoolean();
                s.retryAt=Instant.parse(in.readUTF());var status=R2OpportunityTaskScheduler.Status.valueOf(in.readUTF());
                if(in.readBoolean()){
                    var next=cursor(in);int diagnostics=kind==InternalApiClient.OpportunityKind.OWNER_EXCEPTION?in.readInt():0;int count=in.readInt();if(count<0||count>50)throw new IOException("Invalid checkpoint page");
                    var candidates=new ArrayList<InternalApiClient.OpportunityCandidate>();var keys=new HashSet<UUID>();
                    for(int i=0;i<count;i++){
                        var key=uuid(in);var opportunity=uuid(in);long revision=in.readLong();if(!keys.add(key))throw new IOException("Duplicate checkpoint command");
                        if(kind==InternalApiClient.OpportunityKind.CONTRACT_PREPARATION){var sourceKind=in.readUTF();var basis=uuid(in);long basisRevision=in.readLong();var source=uuid(in);var workflow=in.readBoolean()?uuid(in):null;candidates.add(new InternalApiClient.ContractPreparationCandidate(key,opportunity,revision,basis,basisRevision,source,workflow,sourceKind));continue;}
                        candidates.add(kind==InternalApiClient.OpportunityKind.OWNER_EXCEPTION?new InternalApiClient.OwnerExceptionCandidate(key,opportunity,revision):kind==InternalApiClient.OpportunityKind.INITIAL?new InternalApiClient.InitialOpportunityCandidate(key,opportunity,revision):new InternalApiClient.DueOpportunityCandidate(key,opportunity,revision,uuid(in),in.readLong(),uuid(in),in.readUTF(),uuid(in),in.readUTF(),in.readUTF()));
                    }
                    s.page=new InternalApiClient.OpportunityPage(200,candidates,next,diagnostics);
                }
                if(in.read()!=-1||s.index<0||s.index>(s.page==null?0:s.page.candidates().size())||s.failures<0||s.failures>7||s.lastStatus<0||s.lastStatus>599||s.acknowledged<0||s.rejected<0||(s.page!=null&&!s.active)||((s.uncertain||s.probe)&&(s.page==null||s.index==s.page.candidates().size())))throw new IOException("Invalid checkpoint state");
                s.publish(status);
            }catch(IllegalArgumentException|java.time.DateTimeException failure){throw new IOException("Invalid checkpoint value",failure);}
        }
        target.cursor=s.cursor;target.page=s.page;target.index=s.index;target.failures=s.failures;target.lastStatus=s.lastStatus;
        target.acknowledged=s.acknowledged;target.rejected=s.rejected;target.blocked=s.blocked;target.active=s.active;target.uncertain=s.uncertain;target.probe=s.probe;target.retryAt=s.retryAt;target.snapshot=s.snapshot;
    }
}
