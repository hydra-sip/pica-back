package com.hydra.pica.plataforma_pica.partido.service;

import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.LadoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.RondaBracket;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.ObjecionRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.service.Arbitraje;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ver un partido y el próximo cruce de quien consulta (HU-301, PICA-241), con su objeción y su laudo. */
@Service
@RequiredArgsConstructor
public class PartidoService {

    private final Partidos partidos;
    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final TorneoRepository torneoRepository;
    private final ObjecionRepository objecionRepository;
    private final Arbitraje arbitraje;

    @Transactional(readOnly = true)
    public PartidoDetalle ver(Long usuarioId, Long partidoId) {
        return detalle(partidos.buscar(partidoId), usuarioId);
    }

    /**
     * El primer partido sin cerrar del torneo en el que juega el usuario, aunque el rival todavía no esté definido.
     * Vacío si no juega, si quedó afuera o si el torneo terminó.
     */
    @Transactional(readOnly = true)
    public Optional<PartidoDetalle> miProximo(Long usuarioId, Long torneoId) {
        torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                .filter(t -> t.getEstado() != EstadoTorneo.BORRADOR)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo " + torneoId));
        return partidoRepository.abiertosDe(torneoId, usuarioId, Partidos.CERRADOS).stream()
                .findFirst()
                .map(p -> detalle(p, usuarioId));
    }

    /** También lo devuelven la carga y la aceptación del resultado. */
    PartidoDetalle detalle(Partido partido, Long usuarioId) {
        List<Juega> lados = juegaRepository.delPartido(partido.getId());
        LadoBracket local = Partidos.ladoBracket(lados, Lado.LOCAL);
        LadoBracket visitante = Partidos.ladoBracket(lados, Lado.VISITANTE);
        int rondas = partidoRepository.rondasDelTorneo(partido.getTorneo().getId());
        Lado cargadoPor = partido.getResultadoCargadoPor() == null ? null : lados.stream()
                .filter(j -> j.getId().getInscripcionId().equals(partido.getResultadoCargadoPor().getId()))
                .map(Juega::getLado)
                .findFirst()
                .orElse(null);
        boolean bye = partido.getRonda() == 1 && local != null && visitante == null;
        return new PartidoDetalle(partido.getId(), partido.getTorneo().getId(), partido.getTorneo().getNombre(),
                partido.getTorneo().getSlug(), partido.getRonda(), RondaBracket.nombre(partido.getRonda(), rondas),
                partido.getPosicion(), partido.getEstado(), bye, local, visitante,
                partido.getCancha() == null ? null : partido.getCancha().getNombre(), partido.getFechaProgramada(),
                Partidos.nombre(partido.getArbitro()), cargadoPor, partido.getResultadoCargadoEn(),
                partido.getObjecionVenceEn(), Partidos.deCapitan(lados, usuarioId).map(Juega::getLado).orElse(null),
                objecion(partido, lados),
                partido.getEstado() == EstadoPartido.OBJETADO && puedeResolver(partido, usuarioId));
    }

    /** El organizador o un árbitro aceptado del torneo con permiso para resolver objeciones. */
    boolean puedeResolver(Partido partido, Long usuarioId) {
        return puedeResolver(partido.getTorneo(), usuarioId);
    }

    /** Lo mismo, para todas las objeciones del torneo (HU-303). */
    boolean puedeResolver(Torneo torneo, Long usuarioId) {
        return usuarioId != null
                && (torneo.getOrganizador().getId().equals(usuarioId)
                        || arbitraje.puedeArbitrar(torneo.getId(), usuarioId, PermisoArbitro.RESOLVER_OBJECIONES));
    }

    private ObjecionDetalle objecion(Partido partido, List<Juega> lados) {
        // Hasta que la cargan no hay nada que objetar; después puede quedar CONFIRMADO por laudo.
        if (partido.getResultadoCargadoEn() == null) {
            return null;
        }
        return objecionRepository.findById(partido.getId())
                .map(o -> new ObjecionDetalle(lados.stream()
                        .filter(j -> j.getId().getInscripcionId().equals(o.getInscripcion().getId()))
                        .map(Juega::getLado)
                        .findFirst()
                        .orElse(null), o.getMotivo(), o.getPuntajeLocal(), o.getPuntajeVisitante(),
                        o.getPenalesLocal(), o.getPenalesVisitante(), o.getCreadoEn(), o.getDecision(),
                        o.getJustificacion(), o.getResueltaPor() == null ? null : Partidos.nombre(o.getResueltaPor()),
                        o.getResueltaEn(), o.getEvidencia() == null ? null : o.getEvidencia().getId(),
                        o.getLaudoHash()))
                .orElse(null);
    }
}
