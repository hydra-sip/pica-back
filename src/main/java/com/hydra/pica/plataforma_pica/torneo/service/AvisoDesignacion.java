package com.hydra.pica.plataforma_pica.torneo.service;

import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignadoId;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.event.ArbitroDesignadoEnTorneo;
import com.hydra.pica.plataforma_pica.torneo.event.DesignacionRespondida;
import com.hydra.pica.plataforma_pica.torneo.repository.ArbitroDesignadoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Los mails de la designación de árbitros (HU-807): al árbitro cuando lo designan, con el torneo y los permisos
 * propuestos, y al organizador cuando el árbitro acepta o rechaza. Van después del commit: si el mail falla, la
 * designación queda igual.
 */
@Component
public class AvisoDesignacion {

    private static final Logger log = LoggerFactory.getLogger(AvisoDesignacion.class);

    private final ArbitroDesignadoRepository arbitroRepository;
    private final EmailService emailService;
    private final String webUrl;

    public AvisoDesignacion(ArbitroDesignadoRepository arbitroRepository, EmailService emailService,
                            @Value("${app.web-url:http://localhost:5173}") String webUrl) {
        this.arbitroRepository = arbitroRepository;
        this.emailService = emailService;
        this.webUrl = webUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alDesignar(ArbitroDesignadoEnTorneo evento) {
        avisarDesignacion(evento);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alResponder(DesignacionRespondida evento) {
        avisarRespuesta(evento);
    }

    /** Sin transacción propia: los tests lo llaman dentro de la suya, que no se confirma. */
    void avisarDesignacion(ArbitroDesignadoEnTorneo evento) {
        ArbitroDesignado designacion = buscar(evento.torneoId(), evento.usuarioId());
        if (designacion == null || designacion.getEstado() != EstadoDesignacion.PENDIENTE) {
            return;
        }
        Torneo torneo = designacion.getTorneo();
        String texto = """
                %s te designó árbitro de %s.

                Permisos propuestos: %s.

                Hasta que aceptes no podés cargar resultados ni resolver objeciones. Aceptá o rechazá la designación
                en %s/mis-designaciones
                """.formatted(nombre(torneo.getOrganizador()), torneo.getNombre(), permisos(designacion), webUrl);
        enviar(designacion.getUsuario().getEmail(), "Te designaron árbitro de " + torneo.getNombre(), texto,
                evento.torneoId());
    }

    void avisarRespuesta(DesignacionRespondida evento) {
        ArbitroDesignado designacion = buscar(evento.torneoId(), evento.usuarioId());
        if (designacion == null) {
            return;
        }
        Torneo torneo = designacion.getTorneo();
        boolean acepto = evento.estado() == EstadoDesignacion.ACEPTADO;
        String arbitro = nombre(designacion.getUsuario());
        String texto = acepto
                ? """
                  %s aceptó ser árbitro de %s, con estos permisos: %s.

                  Ya puede arbitrar los partidos que le toquen: %s/torneos/%d/editar?paso=2
                  """.formatted(arbitro, torneo.getNombre(), permisos(designacion), webUrl, torneo.getId())
                : """
                  %s rechazó ser árbitro de %s: no va a arbitrar ningún partido.

                  Podés designar a otro en el paso 2 del torneo: %s/torneos/%d/editar?paso=2
                  """.formatted(arbitro, torneo.getNombre(), webUrl, torneo.getId());
        enviar(torneo.getOrganizador().getEmail(),
                arbitro + (acepto ? " aceptó" : " rechazó") + " ser árbitro de " + torneo.getNombre(), texto,
                evento.torneoId());
    }

    private ArbitroDesignado buscar(Long torneoId, Long usuarioId) {
        return arbitroRepository.findById(new ArbitroDesignadoId(torneoId, usuarioId)).orElse(null);
    }

    private void enviar(String email, String asunto, String texto, Long torneoId) {
        try {
            emailService.enviarAviso(email, asunto, texto);
        } catch (RuntimeException e) {
            log.warn("No se pudo mandar el aviso de designación del torneo {} a {}", torneoId, email, e);
        }
    }

    private static String nombre(Usuario usuario) {
        Persona persona = usuario.getPersona();
        return persona == null ? usuario.getUsername() : persona.getNombres() + " " + persona.getApellidos();
    }

    private static String permisos(ArbitroDesignado designacion) {
        return designacion.getPermisos().stream()
                .sorted()
                .map(p -> p == PermisoArbitro.CARGAR_RESULTADOS ? "cargar resultados" : "resolver objeciones")
                .collect(Collectors.joining(" y "));
    }
}
