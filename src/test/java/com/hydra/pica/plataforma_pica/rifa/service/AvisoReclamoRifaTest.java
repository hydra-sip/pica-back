package com.hydra.pica.plataforma_pica.rifa.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.event.ReclamoRifaRegistrado;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AvisoReclamoRifaTest {

    @Mock
    private RifaRepository rifaRepository;

    @Mock
    private EmailService emailService;

    private AvisoReclamoRifa avisoReclamoRifa;

    @BeforeEach
    void setUp() {
        avisoReclamoRifa = new AvisoReclamoRifa(rifaRepository, emailService, "http://localhost:5173");
    }

    @Test
    @DisplayName("alRegistrarReclamo envía email al organizador con los detalles del reclamo")
    void enviaEmailAlOrganizador() {
        Usuario organizador = mock(Usuario.class);
        when(organizador.getEmail()).thenReturn("organizador@test.com");

        Rifa rifa = mock(Rifa.class);
        when(rifa.getId()).thenReturn(10L);
        when(rifa.getTitulo()).thenReturn("Rifa Solidaria");
        when(rifa.getOrganizador()).thenReturn(organizador);

        when(rifaRepository.findById(10L)).thenReturn(Optional.of(rifa));

        ReclamoRifaRegistrado evento = new ReclamoRifaRegistrado(
                10L, 1L, 42L, "Error en el número ganador"
        );

        avisoReclamoRifa.alRegistrarReclamo(evento);

        verify(emailService).enviarAviso(
                eq("organizador@test.com"),
                eq("Reclamo presentado en la rifa Rifa Solidaria"),
                contains("Error en el número ganador")
        );
    }

    @Test
    @DisplayName("alRegistrarReclamo no falla si el servicio de email lanza excepción")
    void toleraFalloEnEnvioEmail() {
        Usuario organizador = mock(Usuario.class);
        when(organizador.getEmail()).thenReturn("organizador@test.com");

        Rifa rifa = mock(Rifa.class);
        when(rifa.getId()).thenReturn(10L);
        when(rifa.getTitulo()).thenReturn("Rifa Solidaria");
        when(rifa.getOrganizador()).thenReturn(organizador);

        when(rifaRepository.findById(10L)).thenReturn(Optional.of(rifa));
        doThrow(new RuntimeException("SMTP error")).when(emailService).enviarAviso(any(), any(), any());

        ReclamoRifaRegistrado evento = new ReclamoRifaRegistrado(
                10L, 1L, 42L, "Error en el sorteo"
        );

        avisoReclamoRifa.alRegistrarReclamo(evento);

        verify(emailService).enviarAviso(any(), any(), any());
    }

    @Test
    @DisplayName("alRegistrarReclamo si no encuentra la rifa no intenta enviar email")
    void rifaNoEncontradaNoEnviaEmail() {
        when(rifaRepository.findById(10L)).thenReturn(Optional.empty());

        ReclamoRifaRegistrado evento = new ReclamoRifaRegistrado(
                10L, 1L, 42L, "Error en el sorteo"
        );

        avisoReclamoRifa.alRegistrarReclamo(evento);

        verifyNoInteractions(emailService);
    }
}
