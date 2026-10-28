package com.hydra.pica.plataforma_pica.partido.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Avisa por mail lo que resolvió la ausencia (HU-309): el walkover a los dos capitanes y al organizador. Va después
 * del commit: si el aviso falla, lo resuelto queda igual.
 */
@Component
public class AvisoAusencia {

    private static final Logger log = LoggerFactory.getLogger(AvisoAusencia.class);

    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final EmailService emailService;
    private final String webUrl;

    public AvisoAusencia(PartidoRepository partidoRepository,
                         JuegaRepository juegaRepository,
                         EmailService emailService,
                         @Value("${app.web-url:http://localhost:5173}") String webUrl) {
        this.partidoRepository = partidoRepository;
        this.juegaRepository = juegaRepository;
        this.emailService = emailService;
        this.webUrl = webUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alConfirmar(ResultadoConfirmado evento) {
        avisar(evento);
    }

    /** Sin transacción propia: los tests lo llaman dentro de la suya, que no se confirma. Solo los WALKOVER. */
    void avisar(ResultadoConfirmado evento) {
        Partido partido = partidoRepository.findById(evento.partidoId()).orElse(null);
        if (partido == null || partido.getEstado() != EstadoPartido.WALKOVER) {
            return;
        }
        List<Juega> lados = juegaRepository.delPartido(partido.getId());
        Juega ganador = lados.stream().filter(j -> Boolean.TRUE.equals(j.getEsGanador())).findFirst().orElseThrow();
        Juega ausente = lados.stream().filter(j -> j != ganador).findFirst().orElseThrow();
        String torneo = partido.getTorneo().getNombre();
        String texto = """
                %s ganó por ausencia de %s en %s: no hizo check-in a tiempo.

                %s/partidos/%d
                """.formatted(Partidos.nombre(ganador.getInscripcion()), Partidos.nombre(ausente.getInscripcion()),
                torneo, webUrl, partido.getId());
        enviar(partido, capitanesYOrganizador(partido, lados), "Victoria por ausencia en " + torneo, texto);
    }

    private static Set<String> capitanesYOrganizador(Partido partido, List<Juega> lados) {
        Set<String> destinatarios = new LinkedHashSet<>();
        lados.forEach(j -> destinatarios.add(j.getInscripcion().titular().getEmail()));
        destinatarios.add(partido.getTorneo().getOrganizador().getEmail());
        return destinatarios;
    }

    private void enviar(Partido partido, Set<String> destinatarios, String asunto, String texto) {
        for (String email : destinatarios) {
            try {
                emailService.enviarAviso(email, asunto, texto);
            } catch (RuntimeException e) {
                log.warn("No se pudo avisar a {} de la ausencia en el partido {}", email, partido.getId(), e);
            }
        }
    }
}
