package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.event.UsuarioCreado;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Reglas de {@link UsuarioService#crear} con todo mockeado. Los ids los pone el test por reflexión
 * porque las entidades no tienen setter de id (los asigna la base). El recorrido real contra
 * Postgres está en {@code UsuarioServiceIntegracionTest}.
 */
@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {

    private static final DatosPersona DATOS_JUAN = new DatosPersona(
            TipoDoc.DNI, "30123456", "Juan", "Pérez", null, null, null);

    @Mock private UsuarioRepository usuarioRepository;
    @Mock private UsuarioRolRepository usuarioRolRepository;
    @Mock private PersonaRepository personaRepository;
    @Mock private RolRepository rolRepository;
    @Mock private PersonaService personaService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private ApplicationEventPublisher eventos;

    @InjectMocks
    private UsuarioService usuarioService;

    private final AtomicLong secuencia = new AtomicLong(100);
    private Persona personaJuan;
    private Rol participante;

    @BeforeEach
    void datos() {
        personaJuan = conId(new Persona(), 5L);
        personaJuan.setNombres("Juan");
        personaJuan.setApellidos("Pérez");
        personaJuan.setEstado(EstadoGeneral.ACTIVO);

        participante = rol(6L, "PARTICIPANTE", EstadoGeneral.ACTIVO);
    }

    @Test
    @DisplayName("Autoregistro: hash BCrypt, PENDIENTE_VERIFICACION, rol PARTICIPANTE y evento que pide verificación")
    void autoRegistroCompleto() {
        when(personaService.buscarOCrear(DATOS_JUAN)).thenReturn(personaJuan);
        when(rolRepository.findByNombre("PARTICIPANTE")).thenReturn(Optional.of(participante));
        when(passwordEncoder.encode("Pica2026")).thenReturn("$2a$12$hash");
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.empty());
        guardaAsignandoId();

        Usuario creado = usuarioService.crear(
                NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026", DATOS_JUAN));

        assertThat(creado.getId()).isNotNull();
        assertThat(creado.getUsername()).isEqualTo("jperez");
        assertThat(creado.getPasswordHash()).isEqualTo("$2a$12$hash");
        assertThat(creado.getGoogleSub()).isNull();
        assertThat(creado.getEstado()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
        assertThat(creado.isEmailVerificado()).isFalse();
        assertThat(creado.getPersona()).isSameAs(personaJuan);

        ArgumentCaptor<UsuarioRol> asignacion = ArgumentCaptor.forClass(UsuarioRol.class);
        verify(usuarioRolRepository).save(asignacion.capture());
        assertThat(asignacion.getValue().getRol()).isSameAs(participante);
        assertThat(asignacion.getValue().getAsignadoEn()).isNotNull();
        assertThat(asignacion.getValue().getAsignadoPor()).isEqualTo("SISTEMA");
        assertThat(creado.getRoles()).hasSize(1);

        ArgumentCaptor<Object> evento = ArgumentCaptor.forClass(Object.class);
        verify(eventos).publishEvent(evento.capture());
        assertThat(evento.getValue()).isEqualTo(
                new UsuarioCreado(creado.getId(), "jperez", "juan@example.com", true));
    }

    @Test
    @DisplayName("Username ya usado (aunque sea de un eliminado): 409 USERNAME_DUPLICADO y no toca nada más")
    void usernameDuplicado() {
        when(usuarioRepository.existsByUsernameIncluyendoEliminados("jperez")).thenReturn(true);

        assertThatThrownBy(() -> usuarioService.crear(
                NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026", DATOS_JUAN)))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.USERNAME_DUPLICADO);
        verify(personaService, never()).buscarOCrear(any());
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Email ya usado: 409 EMAIL_DUPLICADO")
    void emailDuplicado() {
        when(usuarioRepository.existsByEmailIncluyendoEliminados("juan@example.com")).thenReturn(true);

        assertThatThrownBy(() -> usuarioService.crear(
                NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026", DATOS_JUAN)))
                .extracting("codigo").isEqualTo(CodigoError.EMAIL_DUPLICADO);
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("La persona ya tiene usuario: 409 PERSONA_CON_USUARIO")
    void personaConUsuario() {
        when(personaService.buscarOCrear(DATOS_JUAN)).thenReturn(personaJuan);
        when(usuarioRepository.existsByPersonaIdIncluyendoEliminados(5L)).thenReturn(true);

        assertThatThrownBy(() -> usuarioService.crear(
                NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026", DATOS_JUAN)))
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_CON_USUARIO);
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Alta por admin: persona por id; si no existe 404, si está inactiva 409")
    void porAdminPersonaInexistenteOInactiva() {
        when(personaRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> usuarioService.crear(porAdmin(99L, Set.of())))
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_NO_ENCONTRADA);

        personaJuan.setEstado(EstadoGeneral.INACTIVO);
        when(personaRepository.findById(5L)).thenReturn(Optional.of(personaJuan));
        assertThatThrownBy(() -> usuarioService.crear(porAdmin(5L, Set.of())))
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_INACTIVA);

        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Alta por admin: rol inexistente 404, rol inactivo 409")
    void porAdminRolInexistenteOInactivo() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(personaJuan));

        when(rolRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> usuarioService.crear(porAdmin(5L, Set.of(99L))))
                .extracting("codigo").isEqualTo(CodigoError.ROL_NO_ENCONTRADO);

        when(rolRepository.findById(8L)).thenReturn(Optional.of(rol(8L, "ARBITRO", EstadoGeneral.INACTIVO)));
        assertThatThrownBy(() -> usuarioService.crear(porAdmin(5L, Set.of(8L))))
                .extracting("codigo").isEqualTo(CodigoError.ROL_INACTIVO);

        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Alta por admin OK: ACTIVO, mail verificado, solo los roles pedidos y asignadoPor = admin logueado")
    void porAdminCompleto() {
        Rol organizador = rol(3L, "ORGANIZADOR", EstadoGeneral.ACTIVO);
        when(personaRepository.findById(5L)).thenReturn(Optional.of(personaJuan));
        when(rolRepository.findById(3L)).thenReturn(Optional.of(organizador));
        when(passwordEncoder.encode("Temporal1")).thenReturn("hash");
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(1L));
        guardaAsignandoId();

        Usuario creado = usuarioService.crear(porAdmin(5L, Set.of(3L)));

        assertThat(creado.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(creado.isEmailVerificado()).isTrue();
        assertThat(creado.getDescripcion()).isEqualTo("Carga manual");
        verify(rolRepository, never()).findByNombre(any());
        ArgumentCaptor<UsuarioRol> asignacion = ArgumentCaptor.forClass(UsuarioRol.class);
        verify(usuarioRolRepository).save(asignacion.capture());
        assertThat(asignacion.getValue().getRol()).isSameAs(organizador);
        assertThat(asignacion.getValue().getAsignadoPor()).isEqualTo("1");
        verify(eventos).publishEvent(new UsuarioCreado(creado.getId(), "jperez", "juan@example.com", false));
    }

    @Test
    @DisplayName("Google: sin contraseña, username generado del mail y con sufijo si ya está tomado")
    void desdeGoogleGeneraUsername() {
        when(personaService.buscarOCrear(any())).thenReturn(personaJuan);
        when(rolRepository.findByNombre("PARTICIPANTE")).thenReturn(Optional.of(participante));
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.empty());
        when(usuarioRepository.existsByUsernameIncluyendoEliminados("juan.perez")).thenReturn(true);
        when(usuarioRepository.existsByUsernameIncluyendoEliminados("juan.perez2")).thenReturn(true);
        when(usuarioRepository.existsByUsernameIncluyendoEliminados("juan.perez3")).thenReturn(false);
        guardaAsignandoId();

        Usuario creado = usuarioService.crear(
                NuevoUsuario.desdeGoogle("Juan.Perez@gmail.com", "sub-123", "Juan", "Pérez"));

        assertThat(creado.getUsername()).isEqualTo("juan.perez3");
        assertThat(creado.getPasswordHash()).isNull();
        assertThat(creado.getGoogleSub()).isEqualTo("sub-123");
        assertThat(creado.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(creado.isEmailVerificado()).isTrue();
        verify(passwordEncoder, never()).encode(any());
        verify(personaService).buscarOCrear(DatosPersona.sinDocumento("Juan", "Pérez"));
        verify(eventos).publishEvent(new UsuarioCreado(creado.getId(), "juan.perez3", "Juan.Perez@gmail.com", false));
    }

    @Test
    @DisplayName("Dos altas a la vez: la que pierde contra el índice único recibe el mismo 409")
    void choqueConIndiceUnicoSeTraduce() {
        when(personaService.buscarOCrear(DATOS_JUAN)).thenReturn(personaJuan);
        when(rolRepository.findByNombre("PARTICIPANTE")).thenReturn(Optional.of(participante));
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(usuarioRepository.saveAndFlush(any(Usuario.class))).thenThrow(new DataIntegrityViolationException("dup",
                new ConstraintViolationException("dup", new SQLException(), "uq_usuario_email_lower")));

        assertThatThrownBy(() -> usuarioService.crear(
                NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026", DATOS_JUAN)))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.EMAIL_DUPLICADO);
        verify(eventos, never()).publishEvent(any());
    }

    private static NuevoUsuario porAdmin(Long personaId, Set<Long> rolIds) {
        return NuevoUsuario.porAdmin("jperez", "juan@example.com", "Temporal1", "Carga manual", null, personaId, rolIds);
    }

    private void guardaAsignandoId() {
        when(usuarioRepository.saveAndFlush(any(Usuario.class)))
                .thenAnswer(inv -> conId(inv.getArgument(0), secuencia.incrementAndGet()));
        when(usuarioRolRepository.save(any(UsuarioRol.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Rol rol(Long id, String nombre, EstadoGeneral estado) {
        Rol rol = conId(new Rol(), id);
        rol.setNombre(nombre);
        rol.setNombreAmigable(nombre);
        rol.setEstado(estado);
        return rol;
    }

    private static <T> T conId(T entidad, Long id) {
        ReflectionTestUtils.setField(entidad, "id", id);
        return entidad;
    }
}
