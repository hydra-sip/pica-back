package com.hydra.pica.plataforma_pica.partido.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.FasePartido;
import com.hydra.pica.plataforma_pica.partido.domain.Grupo;
import com.hydra.pica.plataforma_pica.partido.domain.GrupoInscripcion;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.TablaGrupo;
import com.hydra.pica.plataforma_pica.partido.dto.GruposTorneo;
import com.hydra.pica.plataforma_pica.partido.repository.GrupoInscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.repository.GrupoRepository;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.ReglasGrupos;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las tablas de la fase de grupos (CR-02, PICA-399). No se guardan: se calculan con los partidos terminados cada vez
 * que se piden ({@link TablaGrupo}), así una objeción que cambia un resultado cambia la tabla sola.
 */
@Service
@RequiredArgsConstructor
public class TablaGruposService {

    /** Los estados en que el partido ya no se juega y su resultado cuenta. */
    static final Set<EstadoPartido> TERMINADOS =
            EnumSet.of(EstadoPartido.CONFIRMADO, EstadoPartido.CORREGIDO, EstadoPartido.WALKOVER);

    private final TorneoRepository torneoRepository;
    private final GrupoRepository grupoRepository;
    private final GrupoInscripcionRepository grupoInscripcionRepository;
    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;

    /** Público: la de un torneo con grupos publicado. */
    @Transactional(readOnly = true)
    public GruposTorneo ver(Long torneoId) {
        Torneo torneo = torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                .filter(t -> t.getEstado() != EstadoTorneo.BORRADOR)
                .filter(t -> t.getFormato() == Formato.GRUPOS_Y_ELIMINACION)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo con grupos " + torneoId));
        Datos datos = datos(torneo);
        List<GruposTorneo.Grupo> grupos = new ArrayList<>();
        for (Grupo grupo : datos.grupos()) {
            TablaGrupo.Tabla tabla = tabla(torneo, grupo, datos);
            Map<Long, String> nombres = datos.integrantes(grupo).stream()
                    .collect(Collectors.toMap(gi -> gi.getId().getInscripcionId(),
                            gi -> Partidos.nombre(gi.getInscripcion())));
            List<GruposTorneo.Fila> filas = tabla.filas().stream()
                    .map(f -> new GruposTorneo.Fila(f.inscripcionId(), nombres.get(f.inscripcionId()), f.posicion(),
                            f.jugados(), f.ganados(), f.empatados(), f.perdidos(), f.aFavor(), f.enContra(),
                            f.diferencia(), f.puntos(), f.criterio(), f.clasifica()))
                    .toList();
            grupos.add(new GruposTorneo.Grupo(grupo.getLetra(), terminado(grupo, datos), filas,
                    datos.partidos(grupo, FasePartido.GRUPOS).stream()
                            .map(p -> BracketService.partidoBracket(p, datos.lados(p))).toList(),
                    datos.partidos(grupo, FasePartido.DESEMPATE).stream()
                            .map(p -> BracketService.partidoBracket(p, datos.lados(p))).toList(),
                    tabla.desempatesPendientes()));
        }
        return new GruposTorneo(torneo.getId(), ReglasGrupos.de(torneo), SelloTorneo.DESEMPATE, grupos);
    }

    /** La tabla del grupo con lo que hay hasta ahora. */
    TablaGrupo.Tabla tabla(Torneo torneo, Grupo grupo, Datos datos) {
        List<Long> integrantes = datos.integrantes(grupo).stream().map(gi -> gi.getId().getInscripcionId()).toList();
        List<TablaGrupo.Resultado> resultados = datos.partidos(grupo, FasePartido.GRUPOS).stream()
                .filter(p -> TERMINADOS.contains(p.getEstado()))
                .map(p -> resultado(datos.lados(p)))
                .filter(Objects::nonNull)
                .toList();
        return TablaGrupo.calcular(integrantes, resultados, torneo.getPuntosVictoria(), torneo.getPuntosEmpate(),
                torneo.getClasificanPorGrupo(), terminado(grupo, datos), Map.of());
    }

    /** Si se jugaron todos sus partidos de todos contra todos. */
    static boolean terminado(Grupo grupo, Datos datos) {
        return datos.partidos(grupo, FasePartido.GRUPOS).stream().allMatch(p -> TERMINADOS.contains(p.getEstado()));
    }

    private static TablaGrupo.Resultado resultado(List<Juega> lados) {
        Juega local = Partidos.lado(lados, Lado.LOCAL).orElse(null);
        Juega visitante = Partidos.lado(lados, Lado.VISITANTE).orElse(null);
        if (local == null || visitante == null) {
            return null;
        }
        return new TablaGrupo.Resultado(local.getId().getInscripcionId(), visitante.getId().getInscripcionId(),
                local.getPuntaje(), visitante.getPuntaje(), Boolean.TRUE.equals(local.getEsGanador()),
                Boolean.TRUE.equals(visitante.getEsGanador()));
    }

    /** Todo lo de los grupos del torneo, cargado una vez. */
    Datos datos(Torneo torneo) {
        List<Grupo> grupos = grupoRepository.findByTorneoIdOrderByLetraAsc(torneo.getId());
        Map<Long, List<GrupoInscripcion>> integrantes = grupoInscripcionRepository.delTorneo(torneo.getId()).stream()
                .collect(Collectors.groupingBy(gi -> gi.getGrupo().getId()));
        Map<Long, List<Partido>> partidos = partidoRepository.deLosGrupos(torneo.getId()).stream()
                .collect(Collectors.groupingBy(p -> p.getGrupo().getId(), LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<Juega>> lados = juegaRepository.delTorneo(torneo.getId()).stream()
                .collect(Collectors.groupingBy(j -> j.getId().getPartidoId()));
        return new Datos(grupos, integrantes, partidos, lados);
    }

    record Datos(List<Grupo> grupos, Map<Long, List<GrupoInscripcion>> porGrupo, Map<Long, List<Partido>> porPartido,
                 Map<Long, List<Juega>> porLados) {

        List<GrupoInscripcion> integrantes(Grupo grupo) {
            return porGrupo.getOrDefault(grupo.getId(), List.of());
        }

        List<Partido> partidos(Grupo grupo, FasePartido fase) {
            return porPartido.getOrDefault(grupo.getId(), List.of()).stream()
                    .filter(p -> p.getFase() == fase)
                    .toList();
        }

        List<Juega> lados(Partido partido) {
            return porLados.getOrDefault(partido.getId(), List.of());
        }
    }
}
