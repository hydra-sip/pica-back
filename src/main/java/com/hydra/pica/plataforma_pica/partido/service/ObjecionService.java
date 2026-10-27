package com.hydra.pica.plataforma_pica.partido.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.partido.domain.Decision;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Objecion;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.EventoPartido;
import com.hydra.pica.plataforma_pica.partido.dto.EvidenciaDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.LaudoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionComparada;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionResumen;
import com.hydra.pica.plataforma_pica.partido.dto.RondaBracket;
import com.hydra.pica.plataforma_pica.partido.dto.VersionResultado;
import com.hydra.pica.plataforma_pica.partido.repository.EvidenciaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.ObjecionRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La vista consolidada de los partidos objetados (HU-303, PICA-246): el organizador o un árbitro con permiso para
 * resolver objeciones ({@link PartidoService#puedeResolver}) ve las del torneo y compara las dos versiones de cada una.
 */
@Service
@RequiredArgsConstructor
public class ObjecionService {

    /** Como la registra {@link ResultadoService#auditar}. */
    private static final String ENTIDAD = "PARTIDO";
    private static final String CARGADO = "CARGADO";

    /** Las pendientes de la más vieja a la más nueva; después las laudadas, de la más reciente a la más vieja. */
    private static final Comparator<Objecion> PENDIENTES_PRIMERO = (a, b) -> {
        boolean pendienteA = a.getDecision() == null;
        boolean pendienteB = b.getDecision() == null;
        if (pendienteA != pendienteB) {
            return pendienteA ? -1 : 1;
        }
        return pendienteA ? a.getCreadoEn().compareTo(b.getCreadoEn())
                : b.getResueltaEn().compareTo(a.getResueltaEn());
    };

    private final Partidos partidos;
    private final TorneoRepository torneoRepository;
    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final ObjecionRepository objecionRepository;
    private final EvidenciaRepository evidenciaRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final PartidoService partidoService;

    @Transactional(readOnly = true)
    public List<ObjecionResumen> delTorneo(Long usuarioId, Long torneoId) {
        Torneo torneo = torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                .filter(t -> t.getEstado() != EstadoTorneo.BORRADOR)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo " + torneoId));
        if (!partidoService.puedeResolver(torneo, usuarioId)) {
            throw new ProhibidoException(CodigoError.SIN_PERMISO,
                    "El usuario " + usuarioId + " no puede resolver objeciones en el torneo " + torneoId);
        }
        Map<Long, List<Juega>> lados = juegaRepository.delTorneo(torneoId).stream()
                .collect(Collectors.groupingBy(j -> j.getId().getPartidoId()));
        int rondas = partidoRepository.rondasDelTorneo(torneoId);
        List<Objecion> objeciones = objecionRepository.delTorneo(torneoId);
        Map<String, List<Auditoria>> historiales = auditoriaRepository
                .findByEntidadAndEntidadIdInOrderByCreadoEnAscIdAsc(ENTIDAD,
                        objeciones.stream().map(o -> String.valueOf(o.getPartidoId())).toList())
                .stream()
                .collect(Collectors.groupingBy(Auditoria::getEntidadId));
        return objeciones.stream()
                .sorted(PENDIENTES_PRIMERO)
                .map(o -> resumen(o, lados.getOrDefault(o.getPartidoId(), List.of()),
                        historiales.getOrDefault(String.valueOf(o.getPartidoId()), List.of()), rondas))
                .toList();
    }

    @Transactional(readOnly = true)
    public ObjecionComparada comparar(Long usuarioId, Long partidoId) {
        Partido partido = partidos.buscar(partidoId);
        if (!partidoService.puedeResolver(partido, usuarioId)) {
            throw new ProhibidoException(CodigoError.SIN_PERMISO,
                    "El usuario " + usuarioId + " no puede resolver objeciones en el partido " + partidoId);
        }
        Objecion objecion = objecionRepository.findById(partidoId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.OBJECION_NO_ENCONTRADA,
                        "Nadie objetó el partido " + partidoId));
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        List<Auditoria> historial = auditoriaRepository.findByEntidadAndEntidadIdInOrderByCreadoEnAscIdAsc(ENTIDAD,
                List.of(String.valueOf(partidoId)));
        Map<Long, List<EvidenciaDetalle>> evidencias = evidenciaRepository
                .findByPartidoIdOrderByCreadoEnAscIdAsc(partidoId).stream()
                .collect(Collectors.groupingBy(e -> e.getInscripcion().getId(),
                        Collectors.mapping(e -> EvidenciaService.detalle(e,
                                deInscripcion(lados, e.getInscripcion().getId())), Collectors.toList())));
        VersionResultado cargada = cargada(partido, lados, historial, evidencias);
        VersionResultado objetada = objetada(objecion, lados, evidencias);
        return new ObjecionComparada(partidoId, partido.getTorneo().getId(), partido.getEstado(), cargada, objetada,
                objecion.getMotivo(), laudo(objecion), historial.stream().map(a -> evento(a, lados)).toList());
    }

    private static ObjecionResumen resumen(Objecion objecion, List<Juega> lados, List<Auditoria> historial,
            int rondas) {
        Partido partido = objecion.getPartido();
        return new ObjecionResumen(partido.getId(), partido.getRonda(), RondaBracket.nombre(partido.getRonda(), rondas),
                nombre(lados, Lado.LOCAL), nombre(lados, Lado.VISITANTE), cargada(partido, lados, historial, null),
                objetada(objecion, lados, null), objecion.getMotivo(), objecion.getCreadoEn(), objecion.getDecision(),
                objecion.getResueltaEn());
    }

    /**
     * La del último CARGADO de la auditoría y no la del marcador del partido, que un laudo CORREGIDO pisa con el
     * resultado oficial.
     */
    private static VersionResultado cargada(Partido partido, List<Juega> lados, List<Auditoria> historial,
            Map<Long, List<EvidenciaDetalle>> evidencias) {
        Map<String, Object> carga = historial.reversed().stream()
                .map(Auditoria::getDetalle)
                .filter(d -> CARGADO.equals(d.get("resultado")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El partido " + partido.getId() + " tiene objeción y no tiene la carga en la auditoría"));
        Juega quien = deInscripcion(lados, ((Number) carga.get("inscripcionId")).longValue());
        return new VersionResultado(quien.getLado(), Partidos.nombre(quien.getInscripcion()),
                entero(carga.get("puntajeLocal")), entero(carga.get("puntajeVisitante")),
                enteroONull(carga.get("penalesLocal")), enteroONull(carga.get("penalesVisitante")),
                partido.getResultadoCargadoEn(), deLado(evidencias, quien));
    }

    private static VersionResultado objetada(Objecion objecion, List<Juega> lados,
            Map<Long, List<EvidenciaDetalle>> evidencias) {
        Juega quien = deInscripcion(lados, objecion.getInscripcion().getId());
        return new VersionResultado(quien.getLado(), Partidos.nombre(quien.getInscripcion()),
                objecion.getPuntajeLocal(), objecion.getPuntajeVisitante(), objecion.getPenalesLocal(),
                objecion.getPenalesVisitante(), objecion.getCreadoEn(), deLado(evidencias, quien));
    }

    /** Null en el listado, que no trae evidencias. */
    private static List<EvidenciaDetalle> deLado(Map<Long, List<EvidenciaDetalle>> evidencias, Juega lado) {
        return evidencias == null ? null : evidencias.getOrDefault(lado.getId().getInscripcionId(), List.of());
    }

    /** El lado y el equipo, cuando el registro los tiene: el laudo y la confirmación por plazo no son de un lado. */
    private static EventoPartido evento(Auditoria registro, List<Juega> lados) {
        Map<String, Object> detalle = registro.getDetalle();
        Object lado = detalle.get("lado");
        Object inscripcionId = detalle.get("inscripcionId");
        Object decision = detalle.get("decision");
        return new EventoPartido((String) detalle.get("resultado"),
                registro.getUsuario() == null ? null : Partidos.nombre(registro.getUsuario()),
                lado == null ? null : Lado.valueOf((String) lado),
                inscripcionId == null ? null
                        : Partidos.nombre(deInscripcion(lados, ((Number) inscripcionId).longValue()).getInscripcion()),
                registro.getCreadoEn(), decision == null ? null : Decision.valueOf((String) decision));
    }

    private static LaudoDetalle laudo(Objecion objecion) {
        if (objecion.getDecision() == null) {
            return null;
        }
        return new LaudoDetalle(objecion.getDecision(), objecion.getJustificacion(),
                Partidos.nombre(objecion.getResueltaPor()), objecion.getResueltaEn(),
                objecion.getEvidencia().getId(), objecion.getLaudoHash());
    }

    private static Juega deInscripcion(List<Juega> lados, Long inscripcionId) {
        return lados.stream().filter(j -> j.getId().getInscripcionId().equals(inscripcionId)).findFirst().orElseThrow();
    }

    private static int entero(Object valor) {
        return ((Number) valor).intValue();
    }

    private static Integer enteroONull(Object valor) {
        return valor == null ? null : entero(valor);
    }

    private static String nombre(List<Juega> lados, Lado lado) {
        return Partidos.lado(lados, lado).map(j -> Partidos.nombre(j.getInscripcion())).orElse(null);
    }
}
