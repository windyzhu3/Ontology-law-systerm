package io.github.windyzhu3.ontologylaw.lead.internal.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URL;
import org.springframework.context.ApplicationContext;

public final class FrameworkLeakFixture {
    public static final class UrlLeak {
        private URL endpoint;
    }

    public static final class JacksonLeak {
        private ObjectMapper objectMapper;
    }

    public static final class SpringLeak {
        private ApplicationContext applicationContext;
    }
}
