package com.hydra.pica.plataforma_pica.common.security;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    /**
     * El filtro JWT pone el {@code sub} del token (el id del usuario) como nombre del principal.
     * Cualquier otro principal (anónimo, {@code @WithMockUser} en los tests) no es un usuario del
     * sistema y da vacío.
     */
    @Override
    public Optional<Long> getCurrentUserId() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()
                || autenticacion instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(autenticacion.getName()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public Set<String> getPermisos() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()) {
            return Set.of();
        }
        return autenticacion.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }
}
