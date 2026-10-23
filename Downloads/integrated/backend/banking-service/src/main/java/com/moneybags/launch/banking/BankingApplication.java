package com.moneybags.launch.banking;

import com.moneybags.serviceclient.RemoteSessionAuthenticator;
import com.moneybags.serviceclient.HttpStatementClient;
import com.moneybags.serviceclient.HttpPaymentClient;
import com.moneybags.serviceclient.HttpAssistantLoanClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages={"com.moneybags.common", "com.moneybags.config", "com.moneybags.iam", "com.moneybags.integration", "com.moneybags.cif", "com.moneybags.product", "com.moneybags.account", "com.moneybags.txn", "com.moneybags.treasury", "com.moneybags.creditcard", "com.moneybags.assistant"}, nameGenerator=FullyQualifiedAnnotationBeanNameGenerator.class)
@ConfigurationPropertiesScan(basePackages="com.moneybags")
@Import({RemoteSessionAuthenticator.class, HttpStatementClient.class, HttpPaymentClient.class, HttpAssistantLoanClient.class})
@EnableScheduling
public class BankingApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(BankingApplication.class);
        app.setAdditionalProfiles("banking"); app.run(args);
    }
}
