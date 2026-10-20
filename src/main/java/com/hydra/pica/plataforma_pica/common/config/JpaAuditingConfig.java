package com.hydra.pica.plataforma_pica.common.config;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    private final CurrentUserProvider currentUserProvider;

    public JpaAuditingConfig(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of(currentUserProvider.getCurrentUserId()
                .map(String::valueOf)
                .orElse(AuditConstants.SISTEMA));
    }
}
