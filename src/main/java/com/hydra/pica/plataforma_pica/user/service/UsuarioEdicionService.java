package com.hydra.pica.plataforma_pica.user.service;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.config.AdminConfig.AdminProperties;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioDetalle;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioUpdateRequest;
import com.hydra.pica.plataforma_pica.user.event.EmailDeUsuarioCambiado;
import com.hydra.pica.plataforma_pica.user.event.SesionesDeUsuarioInvalidadas;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Modificar, dar de baja, reactivar y resetear la contraseña de un usuario desde el ABM (PICA-116).
 * El alta está en {@link UsuarioService} y los roles en {@link UsuarioRolService}.
 *
 * Modificar, dar de baja y resetear no se pueden hacer sobre el Admin del sistema (403
 * USUARIO_PROTEGIDO). Un usuario dado de baja no se ve para esas tres operaciones: da 404, como en
 * los roles; para traerlo de vuelta está {@link #reactivar}.
 */
@Service
@RequiredArgsConstructor
public class UsuarioEdicionService {

    private final UsuarioRepository usuarioRepository;
    private final PersonaRepository personaRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties adminProperties;
    private final ApplicationEventPublisher eventos;

    /**
     * Reemplaza username, email, descripción, estado y persona. Si cambia el email el usuario queda
     * con el mail sin verificar y se publica {@link EmailDeUsuarioCambiado}.
     */
    @Transactional
    public UsuarioDetalle modificar(Long id, UsuarioUpdateRequest request) {
        Usuario usuario = buscarVivo(id);
        exigirNoProtegido(usuario, "modificar");
        // el formulario solo puede mandar ACTIVO o BLOQUEADO: a uno con el mail sin verificar no se lo
        // activa a mano, se activa solo cuando verifica
        if (request.estado() == UsuarioUpdateRequest.EstadoEditable.ACTIVO && !usuario.isEmailVerificado()) {
            throw new ConflictoException(CodigoError.EMAIL_NO_VERIFICADO,
                    "El email de " + usuario.getUsername() + " todavía no fue verificado: no se lo puede pasar a ACTIVO");
        }

        // cambiar solo mayúsculas/minúsculas del propio valor no es un choque: el índice único es sobre lower()
        if (!request.username().equalsIgnoreCase(usuario.getUsername())
                && usuarioRepository.existsByUsernameIncluyendoEliminados(request.username())) {
            throw new ConflictoException(CodigoError.USERNAME_DUPLICADO,
                    "El username " + request.username() + " ya está en uso");
        }
        boolean cambiaEmail = !request.email().equalsIgnoreCase(usuario.getEmail());
        if (cambiaEmail && usuarioRepository.existsByEmailIncluyendoEliminados(request.email())) {
            throw new ConflictoException(CodigoError.EMAIL_DUPLICADO,
                    "El email " + request.email() + " ya está en uso");
        }
        Persona persona = resolverPersona(usuario, request.personaId());

        EstadoUsuario estadoAnterior = usuario.getEstado();
        usuario.setUsername(request.username());
        usuario.setEmail(request.email());
        usuario.setDescripcion(textoONull(request.descripcion()));
        usuario.setPersona(persona);
        if (cambiaEmail) {
            usuario.setEmailVerificado(false);
        }
        usuario.setEstado(estadoResultante(request.estado(), usuario.isEmailVerificado()));

        try {
            // flush acá para que un choque con los índices únicos (dos pedidos a la vez) salte ahora
            usuarioRepository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException e) {
            throw traducirViolacion(e, request);
        }

        if (cambiaEmail) {
            eventos.publishEvent(new EmailDeUsuarioCambiado(usuario.getId(), usuario.getEmail()));
        }
        if (usuario.getEstado() == EstadoUsuario.BLOQUEADO && estadoAnterior != EstadoUsuario.BLOQUEADO) {
            eventos.publishEvent(new SesionesDeUsuarioInvalidadas(usuario.getId()));
        }
        return UsuarioDetalle.desde(usuario, persona, false);
    }

    /**
     * Bloquear siempre bloquea. ACTIVO solo llega acá con el mail verificado (si no, ya dio 409), pero
     * si el mismo pedido cambió el email, el usuario queda PENDIENTE_VERIFICACION hasta que verifique el
     * nuevo: lo activa el link del mail (o un reenvío si se perdió).
     */
    private static EstadoUsuario estadoResultante(UsuarioUpdateRequest.EstadoEditable pedido, boolean emailVerificado) {
        if (pedido == UsuarioUpdateRequest.EstadoEditable.BLOQUEADO) {
            return EstadoUsuario.BLOQUEADO;
        }
        return emailVerificado ? EstadoUsuario.ACTIVO : EstadoUsuario.PENDIENTE_VERIFICACION;
    }

    /**
     * Baja lógica. Idempotente: si ya estaba dado de baja no hace nada. Un id que no existe es 404.
     * El Admin del sistema no se da de baja, ni siquiera "otra vez".
     */
    @Transactional
    public void eliminar(Long id) {
        Usuario usuario = usuarioRepository.findByIdIncluyendoEliminados(id).orElseThrow(() -> noExiste(id));
        exigirNoProtegido(usuario, "dar de baja");
        if (usuario.getEliminadoEn() != null) {
            return;
        }

        usuario.setEliminadoEn(Instant.now());
        usuarioRepository.saveAndFlush(usuario);
        eventos.publishEvent(new SesionesDeUsuarioInvalidadas(usuario.getId()));
    }

    /**
     * Limpia la baja del usuario y también la de su persona (si la tenía). Conserva roles y estado.
     * Si no estaba dado de baja no cambia nada y lo devuelve igual.
     */
    @Transactional
    public UsuarioDetalle reactivar(Long id) {
        Usuario usuario = usuarioRepository.findByIdIncluyendoEliminados(id)
                .orElseThrow(() -> noExiste(id));
        // usuario.getPersona() pasa por el @SQLRestriction de Persona y falla si está dada de baja
        Persona persona = personaRepository.findByUsuarioIdIncluyendoEliminadas(id)
                .orElseThrow(() -> new IllegalStateException("El usuario " + id + " no tiene persona"));

        usuario.setEliminadoEn(null);
        persona.setEliminadoEn(null);
        usuarioRepository.saveAndFlush(usuario);
        personaRepository.saveAndFlush(persona);
        return UsuarioDetalle.desde(usuario, persona, esProtegido(usuario));
    }

    /** La contraseña temporal la define el admin y se la pasa al usuario por fuera. */
    @Transactional
    public void resetearPassword(Long id, String password) {
        Usuario usuario = buscarVivo(id);
        exigirNoProtegido(usuario, "resetear la contraseña de");

        usuario.setPasswordHash(passwordEncoder.encode(password));
        usuarioRepository.saveAndFlush(usuario);
        eventos.publishEvent(new SesionesDeUsuarioInvalidadas(usuario.getId()));
    }

    /** La persona nueva tiene que existir, estar activa y no tener ya otro usuario. */
    private Persona resolverPersona(Usuario usuario, Long personaId) {
        if (usuario.getPersona().getId().equals(personaId)) {
            return usuario.getPersona();
        }
        // findById respeta el @SQLRestriction: una persona eliminada da 404, no 409
        Persona persona = personaRepository.findById(personaId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.PERSONA_NO_ENCONTRADA,
                        "No existe la persona " + personaId));
        if (persona.getEstado() != EstadoGeneral.ACTIVO) {
            throw new PersonaInactivaException(persona);
        }
        if (usuarioRepository.existsByPersonaIdIncluyendoEliminados(personaId)) {
            throw new ConflictoException(CodigoError.PERSONA_CON_USUARIO,
                    "La persona " + personaId + " ya tiene un usuario");
        }
        return persona;
    }

    /** findById respeta el @SQLRestriction: un usuario dado de baja da 404. */
    private Usuario buscarVivo(Long id) {
        return usuarioRepository.findById(id).orElseThrow(() -> noExiste(id));
    }

    private static NoEncontradoException noExiste(Long id) {
        return new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario " + id);
    }

    private void exigirNoProtegido(Usuario usuario, String accion) {
        if (esProtegido(usuario)) {
            throw new ProhibidoException(CodigoError.USUARIO_PROTEGIDO,
                    "No se puede " + accion + " al usuario " + usuario.getUsername() + ": es el Admin del sistema");
        }
    }

    private boolean esProtegido(Usuario usuario) {
        return adminProperties.esAdmin(usuario.getUsername());
    }

    private static String textoONull(String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }

    /** Dos modificaciones simultáneas con el mismo dato: una pierde contra el índice único. */
    private RuntimeException traducirViolacion(DataIntegrityViolationException e, UsuarioUpdateRequest request) {
        Throwable causa = e;
        while (causa != null && !(causa instanceof ConstraintViolationException)) {
            causa = causa.getCause();
        }
        if (causa == null || ((ConstraintViolationException) causa).getConstraintName() == null) {
            return e;
        }
        return switch (((ConstraintViolationException) causa).getConstraintName()) {
            case "uq_usuario_username_lower" -> new ConflictoException(CodigoError.USERNAME_DUPLICADO,
                    "El username " + request.username() + " ya está en uso");
            case "uq_usuario_email_lower" -> new ConflictoException(CodigoError.EMAIL_DUPLICADO,
                    "El email " + request.email() + " ya está en uso");
            case "uq_usuario_persona_id" -> new ConflictoException(CodigoError.PERSONA_CON_USUARIO,
                    "La persona " + request.personaId() + " ya tiene un usuario");
            default -> e;
        };
    }
}
