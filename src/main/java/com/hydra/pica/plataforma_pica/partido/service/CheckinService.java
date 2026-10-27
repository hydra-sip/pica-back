package com.hydra.pica.plataforma_pica.partido.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.PartidoConfig.PartidoProperties;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.EstadoVentana;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.LadoCheckin;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.VentanaCheckin;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La ventana de check-in de un partido y el check-in de cada capitán (HU-308, PICA-250). La ventana abre
 * {@code minAntes} minutos antes del partido y dura {@code duracion}, los dos del torneo; se calcula al consultar.
 */
@Service
@RequiredArgsConstructor
public class CheckinService {

    private final Partidos partidos;
    private final JuegaRepository juegaRepository;
    private final PartidoProperties partidoProperties;
    private final ResultadoService resultadoService;

    @Transactional(readOnly = true)
    public CheckinPartido ver(Long usuarioId, Long partidoId) {
        Partido partido = partidos.buscar(partidoId);
        return checkin(partido, juegaRepository.delPartido(partidoId), usuarioId);
    }

    /** El capitán de un lado confirma su presencia: el primero pasa el partido a CHECK_IN y el segundo a EN_JUEGO. */
    @Transactional
    public CheckinPartido hacer(Long usuarioId, Long partidoId) {
        Partido partido = partidos.buscarBloqueado(partidoId);
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        Juega mio = Partidos.exigirCapitan(lados, usuarioId, partidoId);
        if (partido.getTorneo().getEstado() != EstadoTorneo.EN_CURSO || lados.size() < 2
                || !ResultadoService.EN_JUEGO.contains(partido.getEstado())) {
            throw new ConflictoException(CodigoError.PARTIDO_NO_EN_JUEGO,
                    "El partido " + partidoId + " está " + partido.getEstado()
                            + (lados.size() < 2 ? " y sin rival definido" : "") + ": no se hace check-in");
        }
        if (mio.getCheckinEn() != null) {
            // por si toca el botón de nuevo o se cortó la conexión: aunque la ventana ya haya cerrado
            return checkin(partido, lados, usuarioId);
        }
        VentanaCheckin ventana = ventana(partido);
        if (ventana == null) {
            throw new ConflictoException(CodigoError.CHECKIN_SIN_VENTANA, "El partido " + partidoId
                    + " no tiene ventana de check-in: el torneo no la configuró o el partido no tiene fecha");
        }
        if (estado(ventana, Instant.now()) != EstadoVentana.ABIERTA) {
            throw new ConflictoException(CodigoError.CHECKIN_FUERA_DE_VENTANA,
                    "La ventana de check-in del partido " + partidoId + " va de " + ventana.abreEn() + " a "
                            + ventana.cierraEn())
                    .con("abreEn", ventana.abreEn())
                    .con("cierraEn", ventana.cierraEn());
        }
        mio.setCheckinEn(Instant.now());
        boolean losDos = lados.stream().allMatch(j -> j.getCheckinEn() != null);
        partido.setEstado(losDos ? EstadoPartido.EN_JUEGO : EstadoPartido.CHECK_IN);
        resultadoService.auditar(partido, usuarioId, ResultadoService.detalle("CHECKIN", mio));
        return checkin(partido, lados, usuarioId);
    }

    private CheckinPartido checkin(Partido partido, List<Juega> lados, Long usuarioId) {
        VentanaCheckin ventana = ventana(partido);
        return new CheckinPartido(ventana, estado(ventana, Instant.now()), lado(lados, Lado.LOCAL),
                lado(lados, Lado.VISITANTE),
                Partidos.deCapitan(lados, usuarioId).map(Juega::getLado).orElse(null));
    }

    /** Null si el torneo no la configuró o el partido no tiene fecha. */
    private static VentanaCheckin ventana(Partido partido) {
        Torneo torneo = partido.getTorneo();
        if (partido.getFechaProgramada() == null || torneo.getCheckinMinAntes() == null) {
            return null;
        }
        Instant abreEn = partido.getFechaProgramada().minus(Duration.ofMinutes(torneo.getCheckinMinAntes()));
        return new VentanaCheckin(abreEn, abreEn.plus(Duration.ofMinutes(torneo.getCheckinDuracionMin())));
    }

    /** Con la tolerancia en cada extremo, por diferencias de reloj. */
    private EstadoVentana estado(VentanaCheckin ventana, Instant ahora) {
        if (ventana == null) {
            return EstadoVentana.SIN_VENTANA;
        }
        Duration tolerancia = partidoProperties.toleranciaCheckin();
        if (ahora.isBefore(ventana.abreEn().minus(tolerancia))) {
            return EstadoVentana.ANTES;
        }
        return ahora.isAfter(ventana.cierraEn().plus(tolerancia)) ? EstadoVentana.CERRADA : EstadoVentana.ABIERTA;
    }

    private static LadoCheckin lado(List<Juega> lados, Lado lado) {
        return Partidos.lado(lados, lado)
                .map(j -> new LadoCheckin(Partidos.nombre(j.getInscripcion()), j.getCheckinEn()))
                .orElse(null);
    }
}
