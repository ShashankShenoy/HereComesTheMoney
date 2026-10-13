package com.moneybags.launch.payments;

import com.moneybags.serviceclient.RemoteSessionAuthenticator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages={"com.moneybags.common","com.moneybags.config","com.moneybags.iam","com.moneybags.integration","com.moneybags.payments","com.moneybags.cif.service","com.moneybags.txn","com.moneybags.treasury.service","com.moneybags.treasury.repository","com.moneybags.treasury.config"}, nameGenerator=FullyQualifiedAnnotationBeanNameGenerator.class)
@ConfigurationPropertiesScan(basePackages="com.moneybags")
@Import(RemoteSessionAuthenticator.class)
@EnableScheduling
public class PaymentsApplication {
    public static void main(String[] args) {
        var app=new SpringApplication(PaymentsApplication.class);
        app.setAdditionalProfiles("payments"); app.run(args);
    }
}
