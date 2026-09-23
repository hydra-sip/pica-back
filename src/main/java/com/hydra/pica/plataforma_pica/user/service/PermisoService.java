package com.hydra.pica.plataforma_pica.user.service;

import java.util.Set;

import com.hydra.pica.plataforma_pica.user.repository.PermisoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permisos efectivos de un usuario (PICA-124). Los usa el login para armar el claim del JWT:
 * lo que devuelve acá es lo que después chequea {@code @PreAuthorize("hasAuthority('...')")}.
 * Se calculan al emitir el token, no en cada request; un cambio de roles o de permisos se ve
 * en el próximo login o refresh.
 */
@Service
@RequiredArgsConstructor
public class PermisoService {

    private final PermisoRepository permisoRepository;

    @Transactional(readOnly = true)
    public Set<String> permisosDe(Long usuarioId) {
        return permisoRepository.findCodigosByUsuarioId(usuarioId);
    }
}
