package com.moneybags.config;
import com.moneybags.iam.service.BootstrapService;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.slf4j.*;
@Configuration
public class BootstrapConfiguration {
    private static final Logger LOG=LoggerFactory.getLogger(BootstrapConfiguration.class);
    @Bean public ApplicationRunner bootstrap(BootstrapService service,@Value("${moneybags.bootstrap.enabled:false}")boolean enabled,@Value("${moneybags.bootstrap.username:admin}")String username,@Value("${moneybags.bootstrap.password:}")String password) {
        return args->{if(enabled){boolean created=service.createFirstAdmin(username,password);LOG.info(created?"Initial administrator created. Disable BOOTSTRAP_ADMIN before the next run.":"Administrator already exists; its password was left unchanged.");}};
    }
}
