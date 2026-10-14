package com.moneybags;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
/** Existing money-flow regression fixtures provide an explicit test PIN; security tests do not import this. */
@TestConfiguration
public class PaymentPinTestSupport {
 public static final String PIN="134679";
 @Bean MockMvcBuilderCustomizer fixturePin(){return builder->builder.defaultRequest(get("/").header("X-Payment-Pin",PIN));}
}
