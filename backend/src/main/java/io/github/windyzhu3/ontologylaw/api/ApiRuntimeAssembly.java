package io.github.windyzhu3.ontologylaw.api;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = ApiRuntimeProbe.class)
public class ApiRuntimeAssembly {}
