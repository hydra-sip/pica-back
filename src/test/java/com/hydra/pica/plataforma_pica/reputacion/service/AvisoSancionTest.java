package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import com.hydra.pica.plataforma_pica.reputacion.domain.Sancion;
import com.hydra.pica.plataforma_pica.reputacion.event.SancionAplicada;
import com.hydra.pica.plataforma_pica.reputacion.repository.SancionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AvisoSancionTest {

    private final SancionRepository repositorio = mock(SancionRepository.class);
    private final EmailService email = mock(EmailService.class);
    private final AvisoSancion aviso = new AvisoSancion(repositorio, email, "https://pica.test");

    @Test
    void leAvisaAlJugadorConElTorneoLaGravedadYElMotivo() {
        when(repositorio.conTodo(7L)).thenReturn(Optional.of(sancion()));

        aviso.alAplicar(new SancionAplicada(7L, 9L, 3L, Gravedad.GRAVE));

        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(email).enviarAviso(eq("juan@pica.test"), eq("Sanción en el torneo Copa"), texto.capture());
        assertThat(texto.getValue()).contains("Juan", "grave", "«Copa»", "Agredió a un rival",
                "https://pica.test/mi-perfil");
    }

    @Test
    void siElMailFallaLaSancionQuedaIgual() {
        when(repositorio.conTodo(7L)).thenReturn(Optional.of(sancion()));
        doThrow(new IllegalStateException("smtp caído")).when(email).enviarAviso(anyString(), anyString(), any());

        // el aviso corre después del commit: un SMTP caído no puede propagarse ni deshacer la sanción
        assertThatCode(() -> aviso.alAplicar(new SancionAplicada(7L, 9L, 3L, Gravedad.GRAVE)))
                .doesNotThrowAnyException();
        verify(email).enviarAviso(eq("juan@pica.test"), anyString(), any());
    }

    @Test
    void sinLaSancionNoAvisa() {
        when(repositorio.conTodo(7L)).thenReturn(Optional.empty());

        aviso.alAplicar(new SancionAplicada(7L, 9L, 3L, Gravedad.LEVE));

        verifyNoInteractions(email);
    }

    private static Sancion sancion() {
        Persona persona = new Persona();
        persona.setNombres("Juan");
        Usuario jugador = new Usuario();
        jugador.setEmail("juan@pica.test");
        jugador.setPersona(persona);
        Torneo torneo = new Torneo();
        torneo.setNombre("Copa");
        Sancion sancion = new Sancion();
        sancion.setId(7L);
        sancion.setTorneo(torneo);
        sancion.setUsuario(jugador);
        sancion.setGravedad(Gravedad.GRAVE);
        sancion.setMotivo("Agredió a un rival");
        return sancion;
    }
}
