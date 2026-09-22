package com.hydra.pica.plataforma_pica.common.security;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public Optional<Long> getCurrentUserId() {
        // TODO T-JWT: leer el id del principal autenticado
        return Optional.empty();
    }
}
