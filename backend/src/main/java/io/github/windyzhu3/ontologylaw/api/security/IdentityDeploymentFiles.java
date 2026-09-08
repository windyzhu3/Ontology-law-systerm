package io.github.windyzhu3.ontologylaw.api.security;

import java.nio.file.*;
import java.util.*;
import javax.net.ssl.*;

/** Explicit deployment-owned secret files and application-only PKIX trust. Never changes system trust. */
public final class IdentityDeploymentFiles {
    private IdentityDeploymentFiles(){}
    public static byte[] read(String file,long maximum)throws Exception {
        var path=Path.of(file);if(!path.isAbsolute()||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>maximum)throw new IllegalArgumentException("Identity material unavailable");return Files.readAllBytes(path);
    }
    public static String secret(String file)throws Exception {
        String value=new String(read(file,4096),java.nio.charset.StandardCharsets.UTF_8).stripTrailing();if(value.isBlank()||value.indexOf('\0')>=0)throw new IllegalArgumentException("Identity material unavailable");return value;
    }
    public static byte[] key(String file)throws Exception {
        String encoded=secret(file);byte[] key=Base64.getDecoder().decode(encoded);if(key.length!=32||Arrays.equals(key,new byte[32])||!Base64.getEncoder().encodeToString(key).equals(encoded))throw new IllegalArgumentException("Identity material unavailable");return key;
    }
    public static SSLContext tls(String store,String passwordFile)throws Exception {
        if(store==null&&passwordFile==null)return SSLContext.getDefault();if(store==null||passwordFile==null)throw new IllegalArgumentException("Identity trust unavailable");
        char[] password=secret(passwordFile).toCharArray();try {
            var trust=java.security.KeyStore.getInstance("PKCS12");try(var input=new java.io.ByteArrayInputStream(read(store,1048576))){trust.load(input,password);}
            if(trust.size()==0)throw new IllegalArgumentException("Identity trust unavailable");var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);var tls=SSLContext.getInstance("TLS");tls.init(null,tm.getTrustManagers(),null);return tls;
        }finally{Arrays.fill(password,'\0');}
    }
}
