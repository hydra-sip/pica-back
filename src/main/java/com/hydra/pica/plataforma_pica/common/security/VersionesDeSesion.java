package com.hydra.pica.plataforma_pica.common.security;

import java.util.Optional;

/**
 * De dónde saca {@link JwtAuthenticationFilter} la versión de sesión vigente de cada usuario (CE2-2).
 * La implementa {@code UsuarioRepository}, así common/security no depende de user.
 */
public interface VersionesDeSesion {

    /** Vacío si el usuario no existe o está dado de baja. */
    Optional<Integer> versionDeSesion(Long usuarioId);
}
