package com.hydra.pica.plataforma_pica.user.service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.config.AdminConfig.AdminProperties;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Permiso;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRolId;
import com.hydra.pica.plataforma_pica.user.event.RolesDeUsuarioCambiados;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles de un usuario (PICA-127). El alta asigna los iniciales en {@link UsuarioService#crear}; acá
 * se reemplazan después, desde la pestaña de roles del backoffice.
 *
 * Orden de los errores: 404 del usuario o de algún rol, 409 si el usuario no está activo, después
 * las protecciones (403) y por último 409 si se agrega un rol inactivo.
 */
@Service
@RequiredArgsConstructor
public class UsuarioRolService {

    static final String PERMISO_ASIGNAR = "ROL_ASIGNAR";

    private final UsuarioRepository usuarioRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final RolRepository rolRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AdminProperties adminProperties;
    private final AuditingHandler auditingHandler;
    private final ApplicationEventPublisher eventos;
    private final EntityManager entityManager;

    /**
     * Deja al usuario con exactamente estos roles. Quitar uno es darlo de baja en usuario_rol, no
     * borrarlo; volver a darlo revive esa fila. Las asignaciones a roles dados de baja no se ven ni
     * se tocan: si el rol se reactiva, el usuario lo recupera.
     *
     * Un rol inactivo que el usuario ya tenía y sigue en la lista se conserva: ROL_INACTIVO es solo
     * para los que se agregan, así la pantalla puede guardar otros cambios sin sacárselo.
     */
    @Transactional
    public Usuario reemplazarRoles(Long usuarioId, Collection<Long> rolIds) {
        // findById respeta el @SQLRestriction: un usuario dado de baja da 404
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO,
                        "No existe el usuario " + usuarioId));
        Map<Long, Rol> pedidos = buscarRoles(rolIds);
        if (usuario.getEstado() != EstadoUsuario.ACTIVO) {
            throw new ConflictoException(CodigoError.USUARIO_NO_ACTIVO,
                    "El usuario " + usuario.getUsername() + " no está activo");
        }

        Map<Long, UsuarioRol> actuales = usuario.getRoles().stream()
                .collect(Collectors.toMap(asignacion -> asignacion.getRol().getId(), Function.identity()));
        List<Rol> agregados = pedidos.values().stream()
                .filter(rol -> !actuales.containsKey(rol.getId()))
                .toList();
        List<UsuarioRol> quitados = actuales.values().stream()
                .filter(asignacion -> !pedidos.containsKey(asignacion.getRol().getId()))
                .toList();
        if (agregados.isEmpty() && quitados.isEmpty()) {
            return usuario;
        }

        validarPermisosDeQuienAsigna(agregados, quitados);
        validarAdminDelSistema(usuario, quitados);
        validarUltimoAsignador(usuario, actuales.values(), pedidos.values());
        for (Rol rol : agregados) {
            if (rol.getEstado() != EstadoGeneral.ACTIVO) {
                throw new ConflictoException(CodigoError.ROL_INACTIVO,
                        "El rol " + rol.getNombre() + " está inactivo y no se puede asignar");
            }
        }

        String actor = currentUserProvider.getCurrentUserId().map(String::valueOf).orElse(AuditConstants.SISTEMA);
        Instant ahora = Instant.now();
        for (UsuarioRol asignacion : quitados) {
            asignacion.setEliminadoEn(ahora);
            asignacion.setEliminadoPor(actor);
            usuario.getRoles().remove(asignacion);
        }
        for (Rol rol : agregados) {
            usuario.getRoles().add(asignar(usuario, rol, actor, ahora));
        }

        // cambiar solo la colección no marca al usuario como modificado
        auditingHandler.markModified(usuario);
        usuarioRepository.saveAndFlush(usuario);
        eventos.publishEvent(new RolesDeUsuarioCambiados(usuario.getId()));
        return usuario;
    }

    /** Todos o ninguno: un id que no existe (o es de un rol dado de baja) da 404. */
    private Map<Long, Rol> buscarRoles(Collection<Long> rolIds) {
        Map<Long, Rol> roles = new LinkedHashMap<>();
        for (Long rolId : rolIds) {
            if (!roles.containsKey(rolId)) {
                roles.put(rolId, rolRepository.findById(rolId)
                        .orElseThrow(() -> new NoEncontradoException(CodigoError.ROL_NO_ENCONTRADO,
                                "No existe el rol " + rolId)));
            }
        }
        return roles;
    }

    private UsuarioRol asignar(Usuario usuario, Rol rol, String actor, Instant ahora) {
        if (usuarioRolRepository.reasignar(usuario.getId(), rol.getId(), ahora, actor) > 0) {
            UsuarioRol reasignada = entityManager.find(UsuarioRol.class, new UsuarioRolId(usuario.getId(), rol.getId()));
            // si la baja de esa fila pasó en esta misma sesión, la entidad quedó con eliminado_en viejo
            entityManager.refresh(reasignada);
            return reasignada;
        }
        UsuarioRol nueva = new UsuarioRol(usuario, rol);
        nueva.setAsignadoEn(ahora);
        nueva.setAsignadoPor(actor);
        return usuarioRolRepository.save(nueva);
    }

    /**
     * Nadie puede dar ni quitar un rol con permisos que no tiene. Sin esto, un Administrador (que
     * tiene ROL_ASIGNAR pero no ROL_CREAR/EDITAR/ELIMINAR) se daría SUPER_USUARIO a sí mismo.
     */
    private void validarPermisosDeQuienAsigna(List<Rol> agregados, List<UsuarioRol> quitados) {
        Set<String> propios = currentUserProvider.getPermisos();
        List<String> ajenos = Stream.concat(agregados.stream(), quitados.stream().map(UsuarioRol::getRol))
                .filter(rol -> !propios.containsAll(codigos(rol)))
                .map(Rol::getNombre)
                .sorted()
                .toList();
        if (!ajenos.isEmpty()) {
            throw new ProhibidoException(CodigoError.SIN_PERMISO,
                    "No podés asignar ni quitar roles con permisos que no tenés: " + String.join(", ", ajenos))
                    .con("roles", ajenos);
        }
    }

    private void validarAdminDelSistema(Usuario usuario, List<UsuarioRol> quitados) {
        boolean esAdmin = usuario.getUsername().equalsIgnoreCase(adminProperties.username());
        if (esAdmin && quitados.stream().anyMatch(asignacion -> asignacion.getRol().isEsSistema())) {
            throw new ProhibidoException(CodigoError.USUARIO_PROTEGIDO,
                    "Al usuario " + usuario.getUsername() + " no se le puede quitar el rol de sistema");
        }
    }

    /** Si uno se saca el último rol activo con ROL_ASIGNAR, nadie más que otro asignador lo arregla. */
    private void validarUltimoAsignador(Usuario usuario, Collection<UsuarioRol> actuales, Collection<Rol> pedidos) {
        boolean esUnoMismo = currentUserProvider.getCurrentUserId().filter(usuario.getId()::equals).isPresent();
        if (!esUnoMismo) {
            return;
        }
        boolean asignabaAntes = actuales.stream().map(UsuarioRol::getRol).anyMatch(this::daPermisoDeAsignar);
        boolean asignaDespues = pedidos.stream().anyMatch(this::daPermisoDeAsignar);
        if (asignabaAntes && !asignaDespues) {
            throw new ProhibidoException(CodigoError.ULTIMO_ASIGNADOR,
                    "No podés quitarte el último rol que te permite asignar roles");
        }
    }

    private boolean daPermisoDeAsignar(Rol rol) {
        return rol.getEstado() == EstadoGeneral.ACTIVO && codigos(rol).contains(PERMISO_ASIGNAR);
    }

    private static Set<String> codigos(Rol rol) {
        return rol.getPermisos().stream().map(Permiso::getCodigo).collect(Collectors.toSet());
    }
}
