package io.github.windyzhu3.ontologylaw.api;

import java.sql.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class R1AuthInfrastructureHttpIT extends R1HttpFixture {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void real_identity_query_infrastructure_failure_is_503_not_an_invalid_credential(boolean certificate)throws Exception {
        setupContact();var service=certificate?service("R1_PROJECTION_CONSUME"):null;var tls=certificate?new io.github.windyzhu3.ontologylaw.testing.TlsFixture(directory):null;
        credentialConnection=c->(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("prepareStatement")&&a[0] instanceof String sql&&sql.contains("\"identity\"."))throw new SQLException("Synthetic broken credential database transport","08006");
            try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
        });
        try(var http=new HttpHarness(service,tls)){var before=counts();var response=http.request("GET",certificate?"/internal/v1/projections/r1/readiness":"/api/v1/workcards/current",null,Map.of());assertEquals(503,response.statusCode(),response.body());assertEquals("SERVICE_UNAVAILABLE",http.body(response).get("code"));assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());assertTrue(response.headers().firstValue("ETag").isEmpty());assertFalse(response.body().contains("Synthetic"));assertFalse(response.body().contains("identity"));assertEquals(before,counts());}
    }
}
