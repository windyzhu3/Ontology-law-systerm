package io.github.windyzhu3.ontologylaw.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.HibernateValidatorConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** JSON Schema string lengths are Unicode code points; collection/array validators remain unchanged. */
@Configuration(proxyBeanMethods=false)
public class R1ValidationConfiguration {
    @Bean ValidationConfigurationCustomizer r1JsonStringLengths(){return configuration->{
        if(!(configuration instanceof HibernateValidatorConfiguration validator))throw new IllegalStateException("R1_VALIDATION_CONFIGURATION_UNAVAILABLE");
        var mapping=validator.createConstraintMapping();mapping.constraintDefinition(Size.class).includeExistingValidators(true).validatedBy(CodePointSize.class);validator.addMapping(mapping);
    };}
    public static final class CodePointSize implements ConstraintValidator<Size,String> {
        private int min,max;
        public void initialize(Size size){min=size.min();max=size.max();}
        public boolean isValid(String value,ConstraintValidatorContext context){return value==null||value.codePointCount(0,value.length())>=min&&value.codePointCount(0,value.length())<=max;}
    }
}
