package com.moneybags.launch.loan;

import com.moneybags.serviceclient.RemoteSessionAuthenticator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages={"com.moneybags.common","com.moneybags.config","com.moneybags.iam","com.moneybags.integration","com.moneybags.loan","com.moneybags.cif.service","com.moneybags.product.service","com.moneybags.txn"}, nameGenerator=FullyQualifiedAnnotationBeanNameGenerator.class)
@ConfigurationPropertiesScan(basePackages="com.moneybags")
@Import(RemoteSessionAuthenticator.class)
public class LoanApplication {
    public static void main(String[] args) {
        var app=new SpringApplication(LoanApplication.class);
        app.setAdditionalProfiles("loan"); app.run(args);
    }
}
