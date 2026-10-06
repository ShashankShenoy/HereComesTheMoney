package com.moneybags.integration;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
/** Windows hosts can disable Unix-domain loopback sockets used by the NIO selector. NIO2 uses IOCP. */
@Configuration @Profile("local")
public class LocalWebServerConfiguration {
 @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> localConnector(){return factory->factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");}
}
