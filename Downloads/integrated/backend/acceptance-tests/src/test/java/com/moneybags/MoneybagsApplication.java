package com.moneybags;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** In-process regression harness only; deployed services have separate entry points. */
@org.springframework.boot.SpringBootConfiguration
@org.springframework.boot.autoconfigure.EnableAutoConfiguration
@org.springframework.context.annotation.ComponentScan(basePackages="com.moneybags", nameGenerator=FullyQualifiedAnnotationBeanNameGenerator.class, excludeFilters={
    @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.REGEX, pattern="com[.]moneybags[.]launch[.].*"),
    @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.CUSTOM, classes=org.springframework.boot.context.TypeExcludeFilter.class),
    @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.CUSTOM, classes=org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter.class)
})
@ConfigurationPropertiesScan
@EnableScheduling
public class MoneybagsApplication {
    public static void main(String[] args) { SpringApplication.run(MoneybagsApplication.class,args); }
}
