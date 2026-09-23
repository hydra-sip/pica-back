package com.hydra.pica.plataforma_pica.user.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hydra.pica.plataforma_pica.user.domain.Modulo;
import com.hydra.pica.plataforma_pica.user.domain.Permiso;
import com.hydra.pica.plataforma_pica.user.dto.ModuloPermisos;
import com.hydra.pica.plataforma_pica.user.repository.PermisoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permisos efectivos de un usuario (PICA-124). Los usa el login para armar el claim del JWT:
 * lo que devuelve acá es lo que después chequea {@code @PreAuthorize("hasAuthority('...')")}.
 * Se calculan al emitir el token, no en cada request; un cambio de roles o de permisos se ve
 * en el próximo login o refresh.
 *
 * También expone el catálogo fijo de V4 para la pantalla de roles. Qué permisos tiene cada rol se
 * cambia en {@link RolService#reemplazarPermisos}.
 */
@Service
@RequiredArgsConstructor
public class PermisoService {

    private final PermisoRepository permisoRepository;

    @Transactional(readOnly = true)
    public Set<String> permisosDe(Long usuarioId) {
        return permisoRepository.findCodigosByUsuarioId(usuarioId);
    }

    /**
     * Catálogo agrupado por módulo para los checkboxes de la pantalla de roles (PICA-126). Módulos y
     * permisos salen en el orden del seed, así la pantalla no tiene que ordenar nada.
     */
    @Transactional(readOnly = true)
    public List<ModuloPermisos> catalogo() {
        Map<Modulo, List<ModuloPermisos.Item>> porModulo = new LinkedHashMap<>();
        for (Permiso permiso : permisoRepository.findAllByOrderByIdAsc()) {
            porModulo.computeIfAbsent(permiso.getModulo(), modulo -> new ArrayList<>())
                    .add(new ModuloPermisos.Item(permiso.getCodigo(), permiso.getDescripcion()));
        }
        return porModulo.entrySet().stream()
                .map(entrada -> new ModuloPermisos(entrada.getKey(), entrada.getValue()))
                .toList();
    }
}
