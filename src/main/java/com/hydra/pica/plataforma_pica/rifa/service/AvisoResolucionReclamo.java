package com.hydra.pica.plataforma_pica.rifa.service;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.DecisionReclamo;
import com.hydra.pica.plataforma_pica.rifa.event.ReclamoRifaResuelto;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Notifica por email al reclamante cuando soporte o administración resuelve su reclamo (HU-706, PICA-339).
 */
@Component
@Slf4j
public class AvisoResolucionReclamo {

    private final ReclamoRifaRepository reclamoRifaRepository;
    private final EmailService emailService;
    private final String webUrl;

    public AvisoResolucionReclamo(
            ReclamoRifaRepository reclamoRifaRepository,
            EmailService emailService,
            @Value("${app.web-url:http://localhost:5173}") String webUrl) {
        this.reclamoRifaRepository = reclamoRifaRepository;
        this.emailService = emailService;
        this.webUrl = webUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alResolverReclamo(ReclamoRifaResuelto evento) {
        avisar(evento);
    }

    void avisar(ReclamoRifaResuelto evento) {
        ReclamoRifa reclamo = reclamoRifaRepository.findDetalleById(evento.reclamoId()).orElse(null);
        if (reclamo == null || reclamo.getUsuario() == null || reclamo.getRifa() == null) {
            return;
        }

        String email = reclamo.getUsuario().getEmail();
        String tituloRifa = reclamo.getRifa().getTitulo();
        String descripcionDecision = evento.decision() == DecisionReclamo.FRAUDE_CONFIRMADO
                ? "Fraude confirmado (reclamo aceptado)"
                : "Sin fraude (reclamo rechazado)";
        String asunto = "Resolución de tu reclamo en la rifa " + tituloRifa;
        String texto = """
                Se registró una resolución definitiva para tu reclamo sobre la rifa "%s".

                Decisión: %s
                Resolución:
                %s

                Esta resolución cierra el proceso de disputa y no admite apelaciones.
                Podés consultar la rifa en: %s/rifas/%d
                """.formatted(tituloRifa, descripcionDecision, evento.resolucion(), webUrl, reclamo.getRifa().getId());

        try {
            emailService.enviarAviso(email, asunto, texto);
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar a {} de la resolución del reclamo {} en la rifa {}",
                    email, reclamo.getId(), reclamo.getRifa().getId(), e);
        }
    }
}

