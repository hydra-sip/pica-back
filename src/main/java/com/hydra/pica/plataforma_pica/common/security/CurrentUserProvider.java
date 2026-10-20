package com.hydra.pica.plataforma_pica.common.security;

import java.util.Optional;
import java.util.Set;

public interface CurrentUserProvider {

    Optional<Long> getCurrentUserId();

    /** Códigos de permiso de quien hace la request (los del token). Vacío si no hay nadie autenticado. */
    Set<String> getPermisos();
}
