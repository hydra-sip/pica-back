package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

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
import com.hydra.pica.plataforma_pica.user.dto.UsuarioUpdateRequest.EstadoEditable;
import com.hydra.pica.plataforma_pica.user.event.EmailDeUsuarioCambiado;
import com.hydra.pica.plataforma_pica.user.event.SesionesDeUsuarioInvalidadas;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Modificar, baja, reactivar y reset de contraseña (PICA-116) con los repositorios mockeados. */
@ExtendWith(MockitoExtension.class)
class UsuarioEdicionServiceTest {

    private static final Long ID = 3L;
    private static final Long PERSONA_ID = 10L;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PersonaRepository personaRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventos;

    private UsuarioEdicionService servicio;

    @BeforeEach
    void crearServicio() {
        servicio = new UsuarioEdicionService(usuarioRepository, personaRepository, passwordEncoder,
                new AdminProperties("admin", "admin@pica.local", "x"), eventos);
    }

    // --- modificar ---------------------------------------------------------------

    @Test
    @DisplayName("modificar: reemplaza los datos y, si el email no cambió, no pide verificarlo de nuevo")
    void modificaSinCambiarElEmail() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));

        UsuarioDetalle detalle = servicio.modificar(ID, pedido("juan.perez", "JUAN@example.com", "  una nota ",
                EstadoEditable.BLOQUEADO, PERSONA_ID));

        assertThat(usuario.getUsername()).isEqualTo("juan.perez");
        assertThat(usuario.getEmail()).isEqualTo("JUAN@example.com");
        assertThat(usuario.getDescripcion()).isEqualTo("una nota");
        assertThat(usuario.getEstado()).isEqualTo(EstadoUsuario.BLOQUEADO);
        assertThat(usuario.isEmailVerificado()).isTrue();
        assertThat(detalle.username()).isEqualTo("juan.perez");
        verify(usuarioRepository, never()).existsByEmailIncluyendoEliminados(anyString());
        // bloquearlo cierra sus sesiones; el email no cambió, así que no hay mail de verificación
        verify(eventos).publishEvent(new SesionesDeUsuarioInvalidadas(ID));
        verifyNoMoreInteractions(eventos);
    }

    @Test
    @DisplayName("modificar: si cambia el email el usuario queda sin verificar, PENDIENTE_VERIFICACION, y se publica el evento")
    void cambiarElEmailLoDejaPendiente() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));
        when(usuarioRepository.existsByEmailIncluyendoEliminados("nuevo@example.com")).thenReturn(false);

        UsuarioDetalle detalle = servicio.modificar(ID, pedido("juan", "nuevo@example.com", null,
                EstadoEditable.ACTIVO, PERSONA_ID));

        assertThat(usuario.getEmail()).isEqualTo("nuevo@example.com");
        assertThat(usuario.isEmailVerificado()).isFalse();
        assertThat(usuario.getEstado()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
        assertThat(detalle.emailVerificado()).isFalse();
        assertThat(detalle.estado()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
        verify(eventos).publishEvent(new EmailDeUsuarioCambiado(ID, "nuevo@example.com"));
        verifyNoMoreInteractions(eventos);
    }

    @Test
    @DisplayName("modificar: cambiar el email y bloquear a la vez deja BLOQUEADO, sin verificar, y cierra sus sesiones")
    void cambiarElEmailYBloquear() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));
        when(usuarioRepository.existsByEmailIncluyendoEliminados("nuevo@example.com")).thenReturn(false);

        servicio.modificar(ID, pedido("juan", "nuevo@example.com", null, EstadoEditable.BLOQUEADO, PERSONA_ID));

        assertThat(usuario.getEstado()).isEqualTo(EstadoUsuario.BLOQUEADO);
        assertThat(usuario.isEmailVerificado()).isFalse();
        verify(eventos).publishEvent(new EmailDeUsuarioCambiado(ID, "nuevo@example.com"));
        verify(eventos).publishEvent(new SesionesDeUsuarioInvalidadas(ID));
    }

    @Test
    @DisplayName("modificar: mandar ACTIVO a un usuario con el mail sin verificar da 409 EMAIL_NO_VERIFICADO y no cambia nada")
    void activoNoSaltaLaVerificacion() {
        Usuario pendiente = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        pendiente.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        pendiente.setEmailVerificado(false);
        Usuario bloqueadoSinVerificar = usuario(4L, "ana", "ana@example.com", persona(PERSONA_ID));
        bloqueadoSinVerificar.setEstado(EstadoUsuario.BLOQUEADO);
        bloqueadoSinVerificar.setEmailVerificado(false);
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(pendiente));
        when(usuarioRepository.findById(4L)).thenReturn(Optional.of(bloqueadoSinVerificar));

        assertThatThrownBy(() -> servicio.modificar(ID, pedido("juan", "juan@example.com", "nueva nota",
                EstadoEditable.ACTIVO, PERSONA_ID)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.EMAIL_NO_VERIFICADO);
        assertThatThrownBy(() -> servicio.modificar(4L, pedido("ana", "ana@example.com", null,
                EstadoEditable.ACTIVO, PERSONA_ID)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.EMAIL_NO_VERIFICADO);

        assertThat(pendiente.getEstado()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
        assertThat(pendiente.getDescripcion()).isNull();
        assertThat(bloqueadoSinVerificar.getEstado()).isEqualTo(EstadoUsuario.BLOQUEADO);
        verify(usuarioRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventos);
    }

    @Test
    @DisplayName("modificar: a un usuario con el mail sin verificar sí se lo puede bloquear")
    void pendienteSePuedeBloquear() {
        Usuario pendiente = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        pendiente.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        pendiente.setEmailVerificado(false);
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(pendiente));

        servicio.modificar(ID, pedido("juan", "juan@example.com", null, EstadoEditable.BLOQUEADO, PERSONA_ID));

        assertThat(pendiente.getEstado()).isEqualTo(EstadoUsuario.BLOQUEADO);
        verify(eventos).publishEvent(new SesionesDeUsuarioInvalidadas(ID));
    }

    @Test
    @DisplayName("modificar: desbloquear a uno con el mail verificado lo deja ACTIVO; bloquear a uno ya bloqueado no cierra sesiones de nuevo")
    void desbloquearYBloquearDeNuevo() {
        Usuario bloqueado = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        bloqueado.setEstado(EstadoUsuario.BLOQUEADO);
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(bloqueado));

        servicio.modificar(ID, pedido("juan", "juan@example.com", null, EstadoEditable.BLOQUEADO, PERSONA_ID));
        verifyNoInteractions(eventos);

        servicio.modificar(ID, pedido("juan", "juan@example.com", null, EstadoEditable.ACTIVO, PERSONA_ID));
        assertThat(bloqueado.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        verifyNoInteractions(eventos);
    }

    @Test
    @DisplayName("modificar: una descripción null o en blanco la borra")
    void descripcionVaciaSeBorra() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        usuario.setDescripcion("vieja");
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));

        servicio.modificar(ID, pedido("juan", "juan@example.com", "   ", EstadoEditable.ACTIVO, PERSONA_ID));

        assertThat(usuario.getDescripcion()).isNull();
    }

    @Test
    @DisplayName("modificar: un username o un email de otro usuario (aunque esté eliminado) da 409")
    void usernameOEmailDuplicado() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));
        when(usuarioRepository.existsByUsernameIncluyendoEliminados("otro")).thenReturn(true);
        when(usuarioRepository.existsByEmailIncluyendoEliminados("otro@example.com")).thenReturn(true);

        assertThatThrownBy(() -> servicio.modificar(ID, pedido("otro", "juan@example.com", null,
                EstadoEditable.ACTIVO, PERSONA_ID)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.USERNAME_DUPLICADO);
        assertThatThrownBy(() -> servicio.modificar(ID, pedido("juan", "otro@example.com", null,
                EstadoEditable.ACTIVO, PERSONA_ID)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.EMAIL_DUPLICADO);
        assertThat(usuario.getUsername()).isEqualTo("juan");
        assertThat(usuario.getEmail()).isEqualTo("juan@example.com");
    }

    @Test
    @DisplayName("modificar: cambiar solo las mayúsculas del propio username no cuenta como choque")
    void mayusculasDelPropioUsernameNoChocan() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));

        servicio.modificar(ID, pedido("Juan", "juan@example.com", null, EstadoEditable.ACTIVO, PERSONA_ID));

        assertThat(usuario.getUsername()).isEqualTo("Juan");
        verify(usuarioRepository, never()).existsByUsernameIncluyendoEliminados(anyString());
    }

    @Test
    @DisplayName("modificar: pasarlo a otra persona libre y activa lo vincula")
    void cambiaDePersona() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        Persona otra = persona(11L);
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));
        when(personaRepository.findById(11L)).thenReturn(Optional.of(otra));
        when(usuarioRepository.existsByPersonaIdIncluyendoEliminados(11L)).thenReturn(false);

        UsuarioDetalle detalle = servicio.modificar(ID, pedido("juan", "juan@example.com", null,
                EstadoEditable.ACTIVO, 11L));

        assertThat(usuario.getPersona()).isSameAs(otra);
        assertThat(detalle.persona().id()).isEqualTo(11L);
    }

    @Test
    @DisplayName("modificar: la persona nueva inexistente es 404, inactiva 409 PERSONA_INACTIVA, con usuario 409 PERSONA_CON_USUARIO")
    void personaNuevaInvalida() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        Persona inactiva = persona(12L);
        inactiva.setEstado(EstadoGeneral.INACTIVO);
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));
        when(personaRepository.findById(99L)).thenReturn(Optional.empty());
        when(personaRepository.findById(12L)).thenReturn(Optional.of(inactiva));
        when(personaRepository.findById(13L)).thenReturn(Optional.of(persona(13L)));
        when(usuarioRepository.existsByPersonaIdIncluyendoEliminados(13L)).thenReturn(true);

        assertThatThrownBy(() -> servicio.modificar(ID, pedido("juan", "juan@example.com", null,
                EstadoEditable.ACTIVO, 99L)))
                .isInstanceOf(NoEncontradoException.class)
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_NO_ENCONTRADA);
        assertThatThrownBy(() -> servicio.modificar(ID, pedido("juan", "juan@example.com", null,
                EstadoEditable.ACTIVO, 12L)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_INACTIVA);
        assertThatThrownBy(() -> servicio.modificar(ID, pedido("juan", "juan@example.com", null,
                EstadoEditable.ACTIVO, 13L)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_CON_USUARIO);
        assertThat(usuario.getPersona().getId()).isEqualTo(PERSONA_ID);
    }

    @Test
    @DisplayName("modificar: dos modificaciones simultáneas con el mismo email: el choque con el índice único es 409")
    void choqueConElIndiceUnico() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));
        when(usuarioRepository.saveAndFlush(any(Usuario.class)))
                .thenThrow(new DataIntegrityViolationException("dup",
                        new ConstraintViolationException("dup", new SQLException(), "uq_usuario_email_lower")));

        assertThatThrownBy(() -> servicio.modificar(ID, pedido("juan", "nuevo@example.com", null,
                EstadoEditable.ACTIVO, PERSONA_ID)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.EMAIL_DUPLICADO);
        verifyNoInteractions(eventos);
    }

    @Test
    @DisplayName("modificar: el Admin del sistema es 403 USUARIO_PROTEGIDO y no se toca; uno inexistente o dado de baja, 404")
    void modificarProtegidoOInexistente() {
        Usuario admin = usuario(1L, "admin", "admin@pica.local", persona(PERSONA_ID));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.modificar(1L, pedido("otro", "otro@example.com", null,
                EstadoEditable.BLOQUEADO, PERSONA_ID)))
                .isInstanceOf(ProhibidoException.class)
                .extracting("codigo").isEqualTo(CodigoError.USUARIO_PROTEGIDO);
        assertThatThrownBy(() -> servicio.modificar(99L, pedido("otro", "otro@example.com", null,
                EstadoEditable.ACTIVO, PERSONA_ID)))
                .isInstanceOf(NoEncontradoException.class)
                .extracting("codigo").isEqualTo(CodigoError.USUARIO_NO_ENCONTRADO);
        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
    }

    // --- eliminar ----------------------------------------------------------------

    @Test
    @DisplayName("eliminar: marca la baja y avisa que hay que cerrar sus sesiones")
    void eliminaYAvisa() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        when(usuarioRepository.findByIdIncluyendoEliminados(ID)).thenReturn(Optional.of(usuario));

        servicio.eliminar(ID);

        assertThat(usuario.getEliminadoEn()).isNotNull();
        verify(usuarioRepository).saveAndFlush(usuario);
        verify(eventos).publishEvent(new SesionesDeUsuarioInvalidadas(ID));
    }

    @Test
    @DisplayName("eliminar: un id que no existe es 404 y el Admin del sistema 403, sin tocar nada")
    void eliminarInexistenteOProtegido() {
        Usuario admin = usuario(1L, "Admin", "admin@pica.local", persona(PERSONA_ID));
        when(usuarioRepository.findByIdIncluyendoEliminados(1L)).thenReturn(Optional.of(admin));
        when(usuarioRepository.findByIdIncluyendoEliminados(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.eliminar(99L))
                .isInstanceOf(NoEncontradoException.class)
                .extracting("codigo").isEqualTo(CodigoError.USUARIO_NO_ENCONTRADO);
        assertThatThrownBy(() -> servicio.eliminar(1L))
                .isInstanceOf(ProhibidoException.class)
                .extracting("codigo").isEqualTo(CodigoError.USUARIO_PROTEGIDO);
        assertThat(admin.getEliminadoEn()).isNull();
        verifyNoInteractions(eventos);
    }

    @Test
    @DisplayName("eliminar: es idempotente, uno ya dado de baja no se toca ni cierra sesiones de nuevo")
    void eliminarUnoYaDadoDeBaja() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        Instant baja = Instant.parse("2026-01-01T00:00:00Z");
        usuario.setEliminadoEn(baja);
        when(usuarioRepository.findByIdIncluyendoEliminados(ID)).thenReturn(Optional.of(usuario));

        servicio.eliminar(ID);

        assertThat(usuario.getEliminadoEn()).isEqualTo(baja);
        verify(usuarioRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventos);
    }

    // --- reactivar ---------------------------------------------------------------

    @Test
    @DisplayName("reactivar: limpia la baja del usuario y también la de su persona, y conserva el estado")
    void reactivaUsuarioYPersona() {
        Persona persona = persona(PERSONA_ID);
        persona.setEliminadoEn(Instant.parse("2026-01-01T00:00:00Z"));
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona);
        usuario.setEliminadoEn(Instant.parse("2026-01-01T00:00:00Z"));
        usuario.setEstado(EstadoUsuario.BLOQUEADO);
        when(usuarioRepository.findByIdIncluyendoEliminados(ID)).thenReturn(Optional.of(usuario));
        when(personaRepository.findByUsuarioIdIncluyendoEliminadas(ID)).thenReturn(Optional.of(persona));

        UsuarioDetalle detalle = servicio.reactivar(ID);

        assertThat(usuario.getEliminadoEn()).isNull();
        assertThat(persona.getEliminadoEn()).isNull();
        assertThat(detalle.eliminado()).isFalse();
        assertThat(detalle.estado()).isEqualTo(EstadoUsuario.BLOQUEADO);
        verify(usuarioRepository).saveAndFlush(usuario);
        verify(personaRepository).saveAndFlush(persona);
    }

    @Test
    @DisplayName("reactivar: uno que no existe es 404")
    void reactivarInexistente() {
        when(usuarioRepository.findByIdIncluyendoEliminados(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.reactivar(99L))
                .isInstanceOf(NoEncontradoException.class)
                .extracting("codigo").isEqualTo(CodigoError.USUARIO_NO_ENCONTRADO);
    }

    // --- resetear contraseña -----------------------------------------------------

    @Test
    @DisplayName("resetearPassword: guarda el hash, no la contraseña, y avisa que hay que cerrar sus sesiones")
    void reseteaLaContrasena() {
        Usuario usuario = usuario(ID, "juan", "juan@example.com", persona(PERSONA_ID));
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));
        when(passwordEncoder.encode("Temporal1")).thenReturn("$2a$hash");

        servicio.resetearPassword(ID, "Temporal1");

        assertThat(usuario.getPasswordHash()).isEqualTo("$2a$hash");
        verify(usuarioRepository).saveAndFlush(usuario);
        verify(eventos).publishEvent(new SesionesDeUsuarioInvalidadas(ID));
    }

    @Test
    @DisplayName("resetearPassword: el Admin del sistema es 403 y uno inexistente 404")
    void resetearProtegidoOInexistente() {
        Usuario admin = usuario(1L, "admin", "admin@pica.local", persona(PERSONA_ID));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.resetearPassword(1L, "Temporal1"))
                .isInstanceOf(ProhibidoException.class)
                .extracting("codigo").isEqualTo(CodigoError.USUARIO_PROTEGIDO);
        assertThatThrownBy(() -> servicio.resetearPassword(99L, "Temporal1"))
                .isInstanceOf(NoEncontradoException.class);
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$12$hash");
        verifyNoInteractions(passwordEncoder, eventos);
    }

    // --- helpers -----------------------------------------------------------------

    private static UsuarioUpdateRequest pedido(String username, String email, String descripcion,
                                               EstadoEditable estado, Long personaId) {
        return new UsuarioUpdateRequest(username, email, descripcion, estado, personaId);
    }

    private static Usuario usuario(Long id, String username, String email, Persona persona) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", id);
        usuario.setUsername(username);
        usuario.setEmail(email);
        usuario.setPasswordHash("$2a$12$hash");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        return usuario;
    }

    private static Persona persona(Long id) {
        Persona persona = new Persona();
        ReflectionTestUtils.setField(persona, "id", id);
        persona.setNombres("Juan");
        persona.setApellidos("Pérez");
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }
}
