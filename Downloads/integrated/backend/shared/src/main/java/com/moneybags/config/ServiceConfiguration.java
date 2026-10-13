package com.moneybags.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServiceConfiguration {
    @Bean tools.jackson.databind.json.JsonMapper legacyFoundationJson() {
        return tools.jackson.databind.json.JsonMapper.builder().build();
    }
    @Bean OpenAPI moneybagsOpenApi(@Value("${spring.application.name}") String name) {
        var components = new Components();
        for (String scheme : java.util.List.of("iamBearer", "module1Bearer", "bearerAuth"))
            components.addSecuritySchemes(scheme, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer"));
        return new OpenAPI().info(new Info().title(name + " API").version("1.0.0")).components(components);
    }
}
