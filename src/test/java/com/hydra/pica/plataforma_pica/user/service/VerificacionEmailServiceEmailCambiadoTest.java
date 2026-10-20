package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.event.EmailDeUsuarioCambiado;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** El listener de {@code EmailDeUsuarioCambiado} (PICA-116), con el repositorio y el mail mockeados. */
@ExtendWith(MockitoExtension.class)
class VerificacionEmailServiceEmailCambiadoTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EmailService emailService;

    private VerificacionEmailService servicio;

    @BeforeEach
    void crearServicio() {
        servicio = new VerificacionEmailService(usuarioRepository, emailService, "http://localhost/verificar");
    }

    @Test
    @DisplayName("Un usuario PENDIENTE_VERIFICACION recibe un token nuevo y el mail al email nuevo")
    void mandaElLinkAlEmailNuevo() {
        Usuario usuario = usuario(EstadoUsuario.PENDIENTE_VERIFICACION);
        usuario.setTokenVerificacionHash("hash-viejo");
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario));

        servicio.alCambiarEmail(new EmailDeUsuarioCambiado(1L, "nuevo@example.com"));

        assertThat(usuario.getTokenVerificacionHash()).isNotEqualTo("hash-viejo").isNotBlank();
        assertThat(usuario.getTokenVerificacionExpiraEn()).isNotNull();
        verify(usuarioRepository).saveAndFlush(usuario);
        verify(emailService).enviarVerificacion(org.mockito.ArgumentMatchers.eq("nuevo@example.com"),
                startsWith("http://localhost/verificar?token="));
    }

    @Test
    @DisplayName("Un usuario bloqueado, activo o que ya no existe no recibe nada")
    void noMandaNadaSiNoEstaPendiente() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario(EstadoUsuario.BLOQUEADO)));
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(usuario(EstadoUsuario.ACTIVO)));
        when(usuarioRepository.findById(3L)).thenReturn(Optional.empty());

        servicio.alCambiarEmail(new EmailDeUsuarioCambiado(1L, "nuevo@example.com"));
        servicio.alCambiarEmail(new EmailDeUsuarioCambiado(2L, "nuevo@example.com"));
        servicio.alCambiarEmail(new EmailDeUsuarioCambiado(3L, "nuevo@example.com"));

        verify(emailService, never()).enviarVerificacion(anyString(), anyString());
        verify(usuarioRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    private static Usuario usuario(EstadoUsuario estado) {
        Usuario usuario = new Usuario();
        usuario.setUsername("juan");
        usuario.setEmail("nuevo@example.com");
        usuario.setEstado(estado);
        usuario.setEmailVerificado(false);
        return usuario;
    }
}
