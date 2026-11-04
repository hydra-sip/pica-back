package com.hydra.pica.plataforma_pica.partido.service;

import java.util.LinkedHashSet;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.partido.domain.Objecion;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.LadoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoObjetado;
import com.hydra.pica.plataforma_pica.partido.repository.ObjecionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.repository.ArbitroDesignadoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Avisa por mail de una objeción (HU-302) al organizador y a los árbitros que aceptaron la designación con permiso
 * para resolver objeciones. Va después del commit: si el aviso falla, la objeción queda igual.
 */
@Component
public class AvisoObjecion {

    private static final Logger log = LoggerFactory.getLogger(AvisoObjecion.class);

    private final ObjecionRepository objecionRepository;
    private final ArbitroDesignadoRepository arbitroDesignadoRepository;
    private final PartidoService partidoService;
    private final EmailService emailService;
    private final String webUrl;

    public AvisoObjecion(ObjecionRepository objecionRepository,
                         ArbitroDesignadoRepository arbitroDesignadoRepository,
                         PartidoService partidoService,
                         EmailService emailService,
                         @Value("${app.web-url:http://localhost:5173}") String webUrl) {
        this.objecionRepository = objecionRepository;
        this.arbitroDesignadoRepository = arbitroDesignadoRepository;
        this.partidoService = partidoService;
        this.emailService = emailService;
        this.webUrl = webUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alObjetar(ResultadoObjetado evento) {
        avisar(evento);
    }

    /** Sin transacción propia: los tests lo llaman dentro de la suya, que no se confirma. */
    void avisar(ResultadoObjetado evento) {
        Objecion objecion = objecionRepository.findById(evento.partidoId()).orElse(null);
        if (objecion == null) {
            return;
        }
        Partido partido = objecion.getPartido();
        Set<String> destinatarios = new LinkedHashSet<>();
        destinatarios.add(partido.getTorneo().getOrganizador().getEmail());
        arbitroDesignadoRepository.delTorneo(evento.torneoId()).stream()
                .filter(a -> a.getEstado() == EstadoDesignacion.ACEPTADO && a.isResuelveObjeciones())
                .map(ArbitroDesignado::getUsuario)
                .forEach(u -> destinatarios.add(u.getEmail()));

        PartidoDetalle detalle = partidoService.detalle(partido, null);
        String asunto = "Resultado objetado en " + detalle.torneo();
        String texto = """
                Objetaron el resultado de %s (%s vs %s) en %s.

                Motivo: %s

                Queda en suspenso hasta que lo resuelva un árbitro: %s/partidos/%d
                """.formatted(detalle.nombreRonda(), nombre(detalle.local()), nombre(detalle.visitante()),
                detalle.torneo(), objecion.getMotivo(), webUrl, partido.getId());
        for (String email : destinatarios) {
            try {
                emailService.enviarAviso(email, asunto, texto);
            } catch (RuntimeException e) {
                log.warn("No se pudo avisar a {} de la objeción del partido {}", email, partido.getId(), e);
            }
        }
    }

    private static String nombre(LadoBracket lado) {
        return lado == null ? "a definir" : lado.nombre();
    }
}
