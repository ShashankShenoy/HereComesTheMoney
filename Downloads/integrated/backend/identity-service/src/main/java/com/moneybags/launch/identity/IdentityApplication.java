package com.moneybags.launch.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

@SpringBootApplication(scanBasePackages={"com.moneybags.common", "com.moneybags.config", "com.moneybags.iam", "com.moneybags.integration"}, nameGenerator=FullyQualifiedAnnotationBeanNameGenerator.class)
@ConfigurationPropertiesScan(basePackages="com.moneybags")
public class IdentityApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(IdentityApplication.class);
        app.setAdditionalProfiles("identity"); app.run(args);
    }
}
