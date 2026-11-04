package com.hydra.pica.plataforma_pica.rifa.service;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.event.ReclamoRifaRegistrado;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Notifica por email al organizador de una rifa cuando un comprador presenta un reclamo (HU-506, T506-1).
 */
@Component
@Slf4j
public class AvisoReclamoRifa {

    private final RifaRepository rifaRepository;
    private final EmailService emailService;
    private final String webUrl;

    public AvisoReclamoRifa(
            RifaRepository rifaRepository,
            EmailService emailService,
            @Value("${app.web-url:http://localhost:5173}") String webUrl) {
        this.rifaRepository = rifaRepository;
        this.emailService = emailService;
        this.webUrl = webUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alRegistrarReclamo(ReclamoRifaRegistrado evento) {
        avisar(evento);
    }

    void avisar(ReclamoRifaRegistrado evento) {
        Rifa rifa = rifaRepository.findById(evento.rifaId()).orElse(null);
        if (rifa == null || rifa.getOrganizador() == null) {
            return;
        }

        String email = rifa.getOrganizador().getEmail();
        String asunto = "Reclamo presentado en la rifa " + rifa.getTitulo();
        String texto = """
                Se ha presentado un reclamo sobre el resultado del sorteo de la rifa "%s".

                Motivo del reclamo:
                %s

                La rifa ha pasado a estado EN REVISIÓN hasta que se resuelva la disputa.
                Puedes revisarla en: %s/rifas/%d
                """.formatted(rifa.getTitulo(), evento.motivo(), webUrl, rifa.getId());

        try {
            emailService.enviarAviso(email, asunto, texto);
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar a {} del reclamo en la rifa {}", email, rifa.getId(), e);
        }
    }
}
