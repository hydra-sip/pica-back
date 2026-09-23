package com.hydra.pica.plataforma_pica.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdminConfig.AdminProperties.class)
public class AdminConfig {

    @ConfigurationProperties(prefix = "app.admin")
    public record AdminProperties(String username, String email, String initialPassword) {
    }
}