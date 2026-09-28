package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.api.security.IdentityDeploymentFiles;
import org.springframework.core.env.Environment;

/** Deliberately opt-in; never discovers credentials or chooses a provider/model implicitly. */
final class R25AiConfiguration {
    private R25AiConfiguration() {}
    static R25AiModel model(Environment environment) {
        try {
            if(!environment.getProperty("ols.api.ai.enabled",Boolean.class,false))return R25ResponsesAiModel.disabled();
            if(!"openai-responses".equals(environment.getProperty("ols.api.ai.provider")))throw new IllegalArgumentException();
            return R25ResponsesAiModel.configured(environment.getProperty("ols.api.ai.model"),IdentityDeploymentFiles.secret(environment.getProperty("ols.api.ai.api-key-path")));
        } catch(Exception ignored){throw new IllegalArgumentException("Invalid AI deployment configuration");}
    }
}
