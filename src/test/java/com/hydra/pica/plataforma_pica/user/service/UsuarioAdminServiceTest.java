package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.common.config.AdminConfig.AdminProperties;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioCreateRequest;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioCreateRequest.EstadoAlta;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioDetalle;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UsuarioAdminServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PersonaRepository personaRepository;

    @Mock
    private UsuarioService usuarioService;

    private UsuarioAdminService servicio;

    @BeforeEach
    void crearServicio() {
        servicio = new UsuarioAdminService(usuarioRepository, personaRepository, usuarioService,
                new AdminProperties("admin", "admin@pica.local", "x"), new ObjectMapper());
    }

    @Test
    @DisplayName("El alta arma NuevoUsuario.porAdmin con lo que vino en el request")
    void crearMapeaElRequestAlAltaPorAdmin() {
        Persona persona = persona("Ana", "Gomez");
        when(usuarioService.crear(any())).thenReturn(usuario("nuevo", EstadoUsuario.BLOQUEADO, persona));

        UsuarioDetalle detalle = servicio.crear(new UsuarioCreateRequest(
                "nuevo", "nuevo@example.com", "Password1", "una nota", EstadoAlta.BLOQUEADO, 10L, List.of(3L, 4L)));

        ArgumentCaptor<NuevoUsuario> captor = ArgumentCaptor.forClass(NuevoUsuario.class);
        org.mockito.Mockito.verify(usuarioService).crear(captor.capture());
        NuevoUsuario nuevo = captor.getValue();
        assertThat(nuevo.origen()).isEqualTo(NuevoUsuario.Origen.ADMIN);
        assertThat(nuevo.username()).isEqualTo("nuevo");
        assertThat(nuevo.email()).isEqualTo("nuevo@example.com");
        assertThat(nuevo.password()).isEqualTo("Password1");
        assertThat(nuevo.descripcion()).isEqualTo("una nota");
        assertThat(nuevo.estadoInicial()).isEqualTo(EstadoUsuario.BLOQUEADO);
        assertThat(nuevo.emailVerificado()).isTrue();
        assertThat(nuevo.personaId()).isEqualTo(10L);
        assertThat(nuevo.rolIds()).isEqualTo(Set.of(3L, 4L));
        assertThat(detalle.username()).isEqualTo("nuevo");
        assertThat(detalle.persona().nombres()).isEqualTo("Ana");
    }

    @Test
    @DisplayName("Sin estado en el request, el alta nace ACTIVO")
    void crearSinEstadoNaceActivo() {
        when(usuarioService.crear(any())).thenReturn(usuario("nuevo", EstadoUsuario.ACTIVO, persona("Ana", "Gomez")));

        servicio.crear(new UsuarioCreateRequest(
                "nuevo", "nuevo@example.com", "Password1", null, null, 10L, List.of()));

        ArgumentCaptor<NuevoUsuario> captor = ArgumentCaptor.forClass(NuevoUsuario.class);
        org.mockito.Mockito.verify(usuarioService).crear(captor.capture());
        assertThat(captor.getValue().estadoInicial()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(captor.getValue().rolIds()).isEmpty();
    }

    @Test
    @DisplayName("El detalle usa la persona traída aparte, sin pasar por usuario.getPersona()")
    void detalleTraeLaPersonaAunqueEsteDadaDeBaja() {
        Usuario eliminado = new Usuario();
        eliminado.setUsername("baja");
        eliminado.setEmail("baja@example.com");
        eliminado.setEstado(EstadoUsuario.ACTIVO);
        eliminado.setEliminadoEn(Instant.now());
        // sin persona a propósito: si el service la pidiera por el usuario, no la encontraría
        Persona personaEliminada = persona("Luis", "Perez");
        personaEliminada.setEliminadoEn(Instant.now());

        when(usuarioRepository.findByIdIncluyendoEliminados(5L)).thenReturn(Optional.of(eliminado));
        when(personaRepository.findByUsuarioIdIncluyendoEliminadas(5L)).thenReturn(Optional.of(personaEliminada));

        UsuarioDetalle detalle = servicio.obtenerDetalle(5L);

        assertThat(detalle.eliminado()).isTrue();
        assertThat(detalle.persona().nombres()).isEqualTo("Luis");
        assertThat(detalle.persona().apellidos()).isEqualTo("Perez");
    }

    @Test
    @DisplayName("Un id inexistente da 404 USUARIO_NO_ENCONTRADO y no busca la persona")
    void detalleInexistenteDa404() {
        when(usuarioRepository.findByIdIncluyendoEliminados(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.obtenerDetalle(99L))
                .isInstanceOfSatisfying(NoEncontradoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.USUARIO_NO_ENCONTRADO));
        verifyNoInteractions(personaRepository);
    }

    private static Persona persona(String nombres, String apellidos) {
        Persona persona = new Persona();
        persona.setNombres(nombres);
        persona.setApellidos(apellidos);
        persona.setTipoDoc("DNI");
        persona.setNroDoc("30123456");
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }

    private static Usuario usuario(String username, EstadoUsuario estado, Persona persona) {
        Usuario usuario = new Usuario();
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
        usuario.setEstado(estado);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        return usuario;
    }
}
