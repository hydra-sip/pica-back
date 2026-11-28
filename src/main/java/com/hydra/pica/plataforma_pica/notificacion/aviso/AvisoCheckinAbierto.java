package com.hydra.pica.plataforma_pica.notificacion.aviso;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.notificacion.domain.TipoNotificacion;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.VentanaCheckin;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.service.Partidos;
import com.hydra.pica.plataforma_pica.partido.service.VentanasCheckin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Apertura del check-in (HU-808, T808-2): a los dos lados del partido, con la hora límite para confirmar. Nadie la
 * provoca con un clic, así que la busca una tarea programada, como el aviso a Discord ({@code AvisoCheckinDiscord}),
 * pero en todos los torneos con check-in y con su propia marca ({@code checkin_notificado_para}).
 */
@Slf4j
@Component
public class AvisoCheckinAbierto {

    static final Duration RECIENTE = Duration.ofMinutes(10);

    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final AvisosDePartido avisos;
    private final TransactionTemplate transaccion;
    private final Duration reciente;
    private final boolean activo;
    private final Clock clock;

    AvisoCheckinAbierto(PartidoRepository partidoRepository, JuegaRepository juegaRepository, AvisosDePartido avisos,
                        TransactionTemplate transaccion,
                        @Value("${app.partidos.revision-plazos:30s}") Duration revision,
                        @Value("${app.notificaciones.aviso-checkin:true}") boolean activo, Clock clock) {
        this.partidoRepository = partidoRepository;
        this.juegaRepository = juegaRepository;
        this.avisos = avisos;
        this.transaccion = transaccion;
        this.reciente = revision.compareTo(RECIENTE) > 0 ? revision : RECIENTE;
        this.activo = activo;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.partidos.revision-plazos:30s}")
    void programada() {
        if (activo) {
            notificarAbiertas(Instant.now(clock));
        }
    }

    /** @return de cuántos partidos notificó la apertura */
    public int notificarAbiertas(Instant ahora) {
        int notificados = 0;
        for (Long id : partidoRepository.conCheckinPorNotificar(ahora, ahora.minus(reciente))) {
            try {
                List<Aviso> deEste = transaccion.execute(estado -> marcar(id));
                if (deEste != null && !deEste.isEmpty()) {
                    avisos.enviar(deEste);
                    notificados++;
                }
            } catch (RuntimeException e) {
                log.error("No se pudo notificar la apertura del check-in del partido {}", id, e);
            }
        }
        return notificados;
    }

    /** Con la fila bloqueada: si dos instancias lo ven a la vez, una sola lo notifica. */
    private List<Aviso> marcar(Long id) {
        Partido partido = partidoRepository.buscarBloqueado(id).orElseThrow();
        VentanaCheckin ventana = VentanasCheckin.de(partido);
        if (ventana == null || ventana.abreEn().equals(partido.getCheckinNotificadoPara())) {
            return List.of();
        }
        partido.setCheckinNotificadoPara(ventana.abreEn());

        List<Juega> lados = juegaRepository.delPartido(id);
        String ronda = Partidos.nombreRonda(partido, partidoRepository.rondasDelTorneo(partido.getTorneo().getId()));
        String torneo = partido.getTorneo().getNombre();
        String limite = AvisosDePartido.diaYHora(ventana.cierraEn());
        return avisos.aLosDosLados(partido, lados, TipoNotificacion.CHECKIN_ABIERTO,
                rival -> "Se abrió el check-in de tu partido contra " + rival,
                rival -> "Ya podés confirmar tu presencia en " + ronda + " de " + torneo + " contra " + rival
                        + ". El check-in cierra el " + limite + ".",
                Map.of("ronda", ronda, "cierraEn", ventana.cierraEn().toString()));
    }
}
