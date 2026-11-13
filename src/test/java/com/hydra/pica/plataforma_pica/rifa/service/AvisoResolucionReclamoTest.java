package com.hydra.pica.plataforma_pica.rifa.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.DecisionReclamo;
import com.hydra.pica.plataforma_pica.rifa.event.ReclamoRifaResuelto;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AvisoResolucionReclamoTest {

    @Mock
    private ReclamoRifaRepository reclamoRifaRepository;

    @Mock
    private EmailService emailService;

    private AvisoResolucionReclamo avisoResolucionReclamo;

    @BeforeEach
    void setUp() {
        avisoResolucionReclamo = new AvisoResolucionReclamo(
                reclamoRifaRepository, emailService, "http://localhost:5173");
    }

    @Test
    @DisplayName("alResolverReclamo envía mail al reclamante con decisión Sin fraude y la resolución")
    void enviaEmailAlReclamanteSinFraude() {
        when(reclamoRifaRepository.findDetalleById(1L))
                .thenReturn(Optional.of(reclamo("reclamante@test.com", 10L, "Rifa Solidaria")));

        avisoResolucionReclamo.alResolverReclamo(new ReclamoRifaResuelto(
                1L, 10L, null, DecisionReclamo.SIN_FRAUDE, EstadoReclamo.RECHAZADO, "Todo verificado", true));

        verify(emailService).enviarAviso(
                eq("reclamante@test.com"),
                eq("Resolución de tu reclamo en la rifa Rifa Solidaria"),
                contains("Sin fraude (reclamo rechazado)"));
        verify(emailService).enviarAviso(
                eq("reclamante@test.com"),
                any(),
                contains("Todo verificado"));
    }

    @Test
    @DisplayName("alResolverReclamo envía mail al reclamante con decisión Fraude confirmado")
    void enviaEmailAlReclamanteFraudeConfirmado() {
        when(reclamoRifaRepository.findDetalleById(1L))
                .thenReturn(Optional.of(reclamo("reclamante@test.com", 10L, "Rifa Solidaria")));

        avisoResolucionReclamo.alResolverReclamo(new ReclamoRifaResuelto(
                1L, 10L, null, DecisionReclamo.FRAUDE_CONFIRMADO, EstadoReclamo.ACEPTADO, "Fraude probado", true));

        verify(emailService).enviarAviso(
                eq("reclamante@test.com"),
                eq("Resolución de tu reclamo en la rifa Rifa Solidaria"),
                contains("Fraude confirmado (reclamo aceptado)"));
    }

    @Test
    @DisplayName("alResolverReclamo tolera fallos del servicio de email")
    void toleraFalloDeEmail() {
        when(reclamoRifaRepository.findDetalleById(1L))
                .thenReturn(Optional.of(reclamo("reclamante@test.com", 10L, "Rifa Solidaria")));
        doThrow(new RuntimeException("SMTP caído")).when(emailService).enviarAviso(any(), any(), any());

        avisoResolucionReclamo.alResolverReclamo(new ReclamoRifaResuelto(
                1L, 10L, null, DecisionReclamo.SIN_FRAUDE, EstadoReclamo.RECHAZADO, "Ok", true));

        verify(emailService).enviarAviso(any(), any(), any());
    }

    @Test
    @DisplayName("si el reclamo no existe no intenta enviar email")
    void reclamoInexistenteNoEnviaEmail() {
        when(reclamoRifaRepository.findDetalleById(99L)).thenReturn(Optional.empty());

        avisoResolucionReclamo.alResolverReclamo(new ReclamoRifaResuelto(
                99L, 10L, null, DecisionReclamo.SIN_FRAUDE, EstadoReclamo.RECHAZADO, "Ok", true));

        verifyNoInteractions(emailService);
    }

    private static ReclamoRifa reclamo(String email, Long rifaId, String tituloRifa) {
        Usuario usuario = new Usuario();
        usuario.setEmail(email);

        Rifa rifa = new Rifa();
        ReflectionTestUtils.setField(rifa, "id", rifaId);
        rifa.setTitulo(tituloRifa);

        ReclamoRifa reclamo = new ReclamoRifa();
        ReflectionTestUtils.setField(reclamo, "id", 1L);
        reclamo.setUsuario(usuario);
        reclamo.setRifa(rifa);
        return reclamo;
    }
}
