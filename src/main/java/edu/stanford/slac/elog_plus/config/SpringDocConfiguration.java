package edu.stanford.slac.elog_plus.config;

import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class SpringDocConfiguration {
    private final BuildProperties buildProperties;
    private final AppProperties appProperties;
    private final SpringDocProperties springDocProperties;
    @Bean
    public OpenAPI customOpenAPI() {
        String securitySchemeName = "CustomHeaderAuth";
        return new OpenAPI()
                .info(new Info()
                        .title(springDocProperties.getTitle())
                        .description(springDocProperties.getDescription())
                        .version(buildProperties.getVersion())
                        .contact(springDocProperties.getContact()))
                .components(new Components().addSecuritySchemes(securitySchemeName,
                        new SecurityScheme()
                                .name(appProperties.getUserHeaderName())
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName));
    }
}
