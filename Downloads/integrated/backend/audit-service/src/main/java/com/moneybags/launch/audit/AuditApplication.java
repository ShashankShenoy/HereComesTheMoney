package com.moneybags.launch.audit;

import com.moneybags.serviceclient.RemoteSessionAuthenticator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages={"com.moneybags.common","com.moneybags.config","com.moneybags.iam","com.moneybags.integration","com.moneybags.privacy"}, nameGenerator=FullyQualifiedAnnotationBeanNameGenerator.class)
@ConfigurationPropertiesScan(basePackages="com.moneybags")
@Import(RemoteSessionAuthenticator.class)
@EnableScheduling
public class AuditApplication {
    public static void main(String[] args) {
        var app=new SpringApplication(AuditApplication.class);
        app.setAdditionalProfiles("audit"); app.run(args);
    }
}
