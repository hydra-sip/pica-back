package com.hydra.pica.plataforma_pica.discord;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.VentanaCheckin;
import com.hydra.pica.plataforma_pica.partido.dto.RondaBracket;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.service.Partidos;
import com.hydra.pica.plataforma_pica.partido.service.VentanasCheckin;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Avisa al canal de Discord del torneo cuando se abre la ventana de check-in de un partido (PICA-371). No hay un
 * evento de apertura: la ventana sale de la fecha del partido y de la configuración del torneo, así que se buscan cada
 * {@code app.partidos.revision-plazos} las que abrieron hace poco: {@link #RECIENTE}, o el intervalo de revisión si es
 * más largo (con la API que duerme, 15 minutos), para no perder una ventana que abrió entre dos revisiones. Así, al
 * hacer el deploy o al cargar el webhook con el torneo en curso, no sale una tanda de avisos viejos.
 *
 * Cada partido guarda la apertura que avisó: no se avisa dos veces, y si se reprograma, la ventana nueva sí. El mensaje
 * sale después de guardar la marca: si el commit falla, no se avisó.
 */
@Slf4j
@Component
public class AvisoCheckinDiscord {

    static final Duration RECIENTE = Duration.ofMinutes(10);

    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final CanalDiscord canal;
    private final AvisosDiscord avisos;
    private final TransactionTemplate transaccion;
    private final Duration reciente;
    private final boolean activo;

    AvisoCheckinDiscord(PartidoRepository partidoRepository, JuegaRepository juegaRepository, CanalDiscord canal,
                        AvisosDiscord avisos, TransactionTemplate transaccion,
                        @Value("${app.partidos.revision-plazos:30s}") Duration revision,
                        @Value("${app.discord.aviso-checkin:true}") boolean activo) {
        this.partidoRepository = partidoRepository;
        this.juegaRepository = juegaRepository;
        this.canal = canal;
        this.avisos = avisos;
        this.transaccion = transaccion;
        this.reciente = revision.compareTo(RECIENTE) > 0 ? revision : RECIENTE;
        this.activo = activo;
    }

    @Scheduled(fixedDelayString = "${app.partidos.revision-plazos:30s}")
    void programada() {
        if (activo) {
            avisarAbiertas(Instant.now());
        }
    }

    /** @return cuántas ventanas avisó */
    public int avisarAbiertas(Instant ahora) {
        int avisadas = 0;
        for (Long id : partidoRepository.conCheckinPorAvisar(ahora, ahora.minus(reciente))) {
            try {
                Aviso aviso = transaccion.execute(estado -> marcar(id));
                if (aviso != null) {
                    canal.publicar(aviso.webhook(), aviso.texto());
                    avisadas++;
                }
            } catch (RuntimeException e) {
                log.error("No se pudo avisar a Discord la ventana de check-in del partido {}", id, e);
            }
        }
        return avisadas;
    }

    private record Aviso(String webhook, String texto) {
    }

    /** Con la fila bloqueada: si dos instancias lo ven a la vez, una sola lo avisa. */
    private Aviso marcar(Long id) {
        Partido partido = partidoRepository.buscarBloqueado(id).orElseThrow();
        VentanaCheckin ventana = VentanasCheckin.de(partido);
        if (ventana == null || ventana.abreEn().equals(partido.getCheckinAvisadoPara())) {
            return null;
        }
        partido.setCheckinAvisadoPara(ventana.abreEn());
        Torneo torneo = partido.getTorneo();
        return new Aviso(torneo.getDiscordWebhook(), texto(partido, ventana));
    }

    /** El cierre como timestamp de Discord: cada uno lo ve en su hora local. */
    private String texto(Partido partido, VentanaCheckin ventana) {
        Torneo torneo = partido.getTorneo();
        List<Juega> lados = juegaRepository.delPartido(partido.getId());
        String ronda = RondaBracket.nombre(partido.getRonda(), partidoRepository.rondasDelTorneo(torneo.getId()));
        long cierre = ventana.cierraEn().getEpochSecond();
        return avisos.firmado(torneo, "⏰ Se abrió el check-in de **" + ronda + "**: " + nombre(lados, Lado.LOCAL)
                + " vs " + nombre(lados, Lado.VISITANTE) + ". Cierra a las <t:" + cierre + ":t> (<t:" + cierre
                + ":R>).");
    }

    private static String nombre(List<Juega> lados, Lado lado) {
        return Partidos.nombre(Partidos.lado(lados, lado).orElseThrow().getInscripcion());
    }
}
