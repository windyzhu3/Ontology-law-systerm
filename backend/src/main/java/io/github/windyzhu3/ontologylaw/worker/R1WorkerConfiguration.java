package io.github.windyzhu3.ontologylaw.worker;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
class R1WorkerConfiguration {
    @Bean(destroyMethod="close") R1WorkerDeployment r1WorkerDeployment(Environment environment){return R1WorkerDeployment.from(environment);}
    @Bean WorkerRuntimeHealth workerRuntimeHealth(R1WorkerDeployment deployment,org.springframework.context.ApplicationContext context){return new WorkerRuntimeHealth(deployment,context);}
    @Bean WorkerAvailability applicationAvailability(WorkerRuntimeHealth health){return new WorkerAvailability(health);}
}
