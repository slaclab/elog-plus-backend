package edu.stanford.slac.elog_plus.config;

import io.swagger.v3.oas.models.info.Contact;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "springdoc")
public class SpringDocProperties {
    private String title;
    private String description;
    private Contact contact;
    private String apiDocsUrl;

}