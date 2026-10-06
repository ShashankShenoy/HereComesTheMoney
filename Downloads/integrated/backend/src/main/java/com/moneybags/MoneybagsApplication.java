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

/** One process, one datasource, one IAM security boundary. */
@SpringBootApplication(nameGenerator=FullyQualifiedAnnotationBeanNameGenerator.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class MoneybagsApplication {
    public static void main(String[] args) { SpringApplication.run(MoneybagsApplication.class,args); }
    @Bean tools.jackson.databind.json.JsonMapper legacyFoundationJson() {
        return tools.jackson.databind.json.JsonMapper.builder().build();
    }
    @Bean OpenAPI moneybagsOpenApi() {
        var components=new Components();
        for(String name:java.util.List.of("iamBearer","module1Bearer","bearerAuth"))
            components.addSecuritySchemes(name,new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer"));
        return new OpenAPI().info(new Info().title("Money Bags integrated banking API").version("1.0.0")).components(components);
    }
}
