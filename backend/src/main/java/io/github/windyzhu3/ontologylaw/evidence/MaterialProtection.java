package io.github.windyzhu3.ontologylaw.evidence;
import java.util.UUID;
/** Protected metadata belongs to the evidence owner; the API supplies the deployment cipher. */
public interface MaterialProtection {
 byte[] encrypt(UUID tenant,UUID opportunity,UUID fact,String body);
 String decrypt(UUID tenant,UUID opportunity,UUID fact,byte[] body);
}
