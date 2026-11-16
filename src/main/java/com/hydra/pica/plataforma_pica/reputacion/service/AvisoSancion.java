package com.hydra.pica.plataforma_pica.reputacion.service;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.reputacion.domain.Sancion;
import com.hydra.pica.plataforma_pica.reputacion.event.SancionAplicada;
import com.hydra.pica.plataforma_pica.reputacion.repository.SancionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Le avisa por mail al jugador que lo sancionaron (HU-803). Va después del commit: si el mail falla, la sanción queda. */
@Component
public class AvisoSancion {

    private static final Logger log = LoggerFactory.getLogger(AvisoSancion.class);

    private final SancionRepository sancionRepository;
    private final EmailService emailService;
    private final String webUrl;

    public AvisoSancion(SancionRepository sancionRepository, EmailService emailService,
                        @Value("${app.web-url:http://localhost:5173}") String webUrl) {
        this.sancionRepository = sancionRepository;
        this.emailService = emailService;
        this.webUrl = webUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alAplicar(SancionAplicada evento) {
        Sancion sancion = sancionRepository.conTodo(evento.sancionId()).orElse(null);
        if (sancion == null) {
            return;
        }
        String torneo = sancion.getTorneo().getNombre();
        String texto = "Hola " + sancion.getUsuario().getPersona().getNombres() + ",\n\n"
                + "Te aplicaron una sanción " + sancion.getGravedad().name().toLowerCase() + " en el torneo «" + torneo
                + "».\n\nMotivo: " + sancion.getMotivo() + "\n\n"
                + "La sanción baja tu índice de fair play. Podés verla en tu perfil: " + webUrl + "/mi-perfil\n";
        try {
            emailService.enviarAviso(sancion.getUsuario().getEmail(), "Sanción en el torneo " + torneo, texto);
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar la sanción {} al usuario {}", sancion.getId(), evento.usuarioId(), e);
        }
    }
}
