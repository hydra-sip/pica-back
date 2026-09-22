package com.hydra.pica.plataforma_pica.user.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.event.UsuarioCreado;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta de usuarios (PICA-110). Es el único lugar por donde se crea un {@link Usuario}: el registro
 * público, el ABM de admin y el login con Google arman un {@link NuevoUsuario} con la fábrica que
 * les corresponde y llaman a {@link #crear}.
 *
 * Orden de las validaciones, pensado para que el error que ve el usuario sea el que puede arreglar:
 * primero username y email (409 USERNAME_DUPLICADO / EMAIL_DUPLICADO), después la persona
 * (404 PERSONA_NO_ENCONTRADA, 409 PERSONA_INACTIVA / PERSONA_CON_USUARIO) y por último los roles
 * (404 ROL_NO_ENCONTRADO, 409 ROL_INACTIVO). Todo dentro de una transacción: si algo falla no queda
 * ni la persona nueva ni el usuario a medias.
 */
@Service
@RequiredArgsConstructor
public class UsuarioService {

    static final String ROL_PARTICIPANTE = "PARTICIPANTE";

    private static final int USERNAME_MAX = 30;

    private final UsuarioRepository usuarioRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final PersonaRepository personaRepository;
    private final RolRepository rolRepository;
    private final PersonaService personaService;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventos;

    @Transactional
    public Usuario crear(NuevoUsuario nuevo) {
        String username = nuevo.username() != null ? nuevo.username() : generarUsername(nuevo.email());

        if (usuarioRepository.existsByUsernameIncluyendoEliminados(username)) {
            throw new ConflictoException(CodigoError.USERNAME_DUPLICADO, "El username " + username + " ya está en uso");
        }
        if (usuarioRepository.existsByEmailIncluyendoEliminados(nuevo.email())) {
            throw new ConflictoException(CodigoError.EMAIL_DUPLICADO, "El email " + nuevo.email() + " ya está en uso");
        }

        Persona persona = resolverPersona(nuevo);
        List<Rol> roles = resolverRoles(nuevo);

        Usuario usuario = new Usuario();
        usuario.setUsername(username);
        usuario.setEmail(nuevo.email());
        usuario.setPasswordHash(nuevo.password() != null ? passwordEncoder.encode(nuevo.password()) : null);
        usuario.setGoogleSub(nuevo.googleSub());
        usuario.setDescripcion(nuevo.descripcion());
        usuario.setEstado(nuevo.estadoInicial());
        usuario.setEmailVerificado(nuevo.emailVerificado());
        usuario.setPersona(persona);

        try {
            // flush acá para que un choque con los índices únicos salte ahora y no al cerrar la transacción
            usuario = usuarioRepository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException e) {
            throw traducirViolacion(e, username, nuevo.email());
        }

        // UsuarioRol exige los dos ids, por eso va después del save
        String asignadoPor = currentUserProvider.getCurrentUserId().map(String::valueOf).orElse(AuditConstants.SISTEMA);
        for (Rol rol : roles) {
            UsuarioRol asignacion = new UsuarioRol(usuario, rol);
            asignacion.setAsignadoEn(Instant.now());
            asignacion.setAsignadoPor(asignadoPor);
            usuario.getRoles().add(usuarioRolRepository.save(asignacion));
        }

        eventos.publishEvent(new UsuarioCreado(usuario.getId(), username, usuario.getEmail(),
                !usuario.isEmailVerificado()));
        return usuario;
    }

    private Persona resolverPersona(NuevoUsuario nuevo) {
        Persona persona;
        if (nuevo.datosPersona() != null) {
            persona = personaService.buscarOCrear(nuevo.datosPersona());
        } else {
            // findById respeta el @SQLRestriction: una persona eliminada da 404, no 409
            persona = personaRepository.findById(nuevo.personaId())
                    .orElseThrow(() -> new NoEncontradoException(CodigoError.PERSONA_NO_ENCONTRADA,
                            "No existe la persona " + nuevo.personaId()));
            if (persona.getEstado() != EstadoGeneral.ACTIVO) {
                throw new PersonaInactivaException(persona);
            }
        }
        if (usuarioRepository.existsByPersonaIdIncluyendoEliminados(persona.getId())) {
            throw new ConflictoException(CodigoError.PERSONA_CON_USUARIO,
                    "La persona " + persona.getId() + " ya tiene un usuario");
        }
        return persona;
    }

    private List<Rol> resolverRoles(NuevoUsuario nuevo) {
        List<Rol> roles = new ArrayList<>();
        if (nuevo.llevaRolParticipante()) {
            roles.add(rolRepository.findByNombre(ROL_PARTICIPANTE)
                    .orElseThrow(() -> new IllegalStateException("Falta el rol " + ROL_PARTICIPANTE + " del seed V2")));
        }
        for (Long rolId : nuevo.rolIds()) {
            roles.add(rolRepository.findById(rolId)
                    .orElseThrow(() -> new NoEncontradoException(CodigoError.ROL_NO_ENCONTRADO,
                            "No existe el rol " + rolId)));
        }
        for (Rol rol : roles) {
            if (rol.getEstado() != EstadoGeneral.ACTIVO) {
                throw new ConflictoException(CodigoError.ROL_INACTIVO,
                        "El rol " + rol.getNombre() + " está inactivo y no se puede asignar");
            }
        }
        return roles;
    }

    /**
     * Para Google, que no manda username: la parte local del mail, limpia según el patrón del
     * contrato, y un número al final si ya está tomada (jperez, jperez2, jperez3...).
     */
    private String generarUsername(String email) {
        String base = email.substring(0, email.indexOf('@'))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "");
        if (base.length() < 3) {
            base = "usuario" + base;
        }
        if (base.length() > USERNAME_MAX) {
            base = base.substring(0, USERNAME_MAX);
        }
        String candidato = base;
        for (int n = 2; usuarioRepository.existsByUsernameIncluyendoEliminados(candidato); n++) {
            String sufijo = String.valueOf(n);
            candidato = base.substring(0, Math.min(base.length(), USERNAME_MAX - sufijo.length())) + sufijo;
        }
        return candidato;
    }

    /**
     * Red de seguridad para dos altas simultáneas con el mismo dato: las dos pasan los chequeos de
     * arriba y una pierde contra el índice único. Se traduce al mismo 409 que hubiera dado el chequeo.
     */
    private RuntimeException traducirViolacion(DataIntegrityViolationException e, String username, String email) {
        Throwable causa = e;
        while (causa != null && !(causa instanceof ConstraintViolationException)) {
            causa = causa.getCause();
        }
        if (causa == null || ((ConstraintViolationException) causa).getConstraintName() == null) {
            return e;
        }
        return switch (((ConstraintViolationException) causa).getConstraintName()) {
            case "uq_usuario_username_lower" ->
                    new ConflictoException(CodigoError.USERNAME_DUPLICADO, "El username " + username + " ya está en uso");
            case "uq_usuario_email_lower" ->
                    new ConflictoException(CodigoError.EMAIL_DUPLICADO, "El email " + email + " ya está en uso");
            case "uq_usuario_persona_id" ->
                    new ConflictoException(CodigoError.PERSONA_CON_USUARIO, "La persona ya tiene un usuario");
            default -> e;
        };
    }
}
