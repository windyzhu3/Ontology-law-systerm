package io.github.windyzhu3.ontologylaw.api;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class R25AiConfigurationTest {
    @TempDir Path directory;
    @Test void defaultsOffAndNeverReadsSecretUnlessExplicitlyEnabled() {
        assertNotNull(R25AiConfiguration.model(new MockEnvironment().withProperty("ols.api.ai.api-key-path","does-not-exist")));
        for(var env:new MockEnvironment[]{new MockEnvironment().withProperty("ols.api.ai.enabled","true"),new MockEnvironment().withProperty("ols.api.ai.enabled","true").withProperty("ols.api.ai.provider","unknown")})
            assertEquals("Invalid AI deployment configuration",assertThrows(IllegalArgumentException.class,()->R25AiConfiguration.model(env)).getMessage());
    }
    @Test void explicitProviderModelAndPrivateFileAreRequiredWithoutLeakingSecret() throws Exception {
        Path key=directory.resolve("model.secret");Files.writeString(key,"test-private-key\n");
        var env=new MockEnvironment().withProperty("ols.api.ai.enabled","true").withProperty("ols.api.ai.provider","openai-responses").withProperty("ols.api.ai.model","configured-test-model").withProperty("ols.api.ai.api-key-path",key.toAbsolutePath().toString());
        assertFalse(R25AiConfiguration.model(env).toString().contains("test-private-key"));
        env.setProperty("ols.api.ai.model","");assertEquals("Invalid AI deployment configuration",assertThrows(IllegalArgumentException.class,()->R25AiConfiguration.model(env)).getMessage());
    }
}
