package com.hydra.pica.plataforma_pica.partido.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.partido.domain.Decision;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Evidencia;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Objecion;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.LaudoRequest;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.repository.EvidenciaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.ObjecionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El laudo de un partido objetado (HU-304, PICA-248): el organizador o un árbitro con permiso para resolver
 * objeciones fija el resultado oficial, citando una evidencia del partido y con una justificación. El laudo queda
 * sellado con SHA-256 y en la auditoría, y la llave avanza como al aceptar ({@link AvanceLlave}): el partido queda
 * CONFIRMADO si se mantuvo el resultado cargado o CORREGIDO si cambió.
 */
@Service
@RequiredArgsConstructor
public class LaudoService {

    private final Partidos partidos;
    private final PartidoService partidoService;
    private final ResultadoService resultadoService;
    private final JuegaRepository juegaRepository;
    private final ObjecionRepository objecionRepository;
    private final EvidenciaRepository evidenciaRepository;
    private final UsuarioRepository usuarioRepository;
    private final AvanceLlave avanceLlave;

    @Transactional
    public PartidoDetalle laudar(Long usuarioId, Long partidoId, LaudoRequest request) {
        Partido partido = partidos.buscarBloqueado(partidoId);
        if (!partidoService.puedeResolver(partido, usuarioId)) {
            throw new ProhibidoException(CodigoError.SIN_PERMISO,
                    "El usuario " + usuarioId + " no puede resolver objeciones en el partido " + partidoId);
        }
        if (partido.getEstado() != EstadoPartido.OBJETADO) {
            throw new ConflictoException(CodigoError.PARTIDO_NO_OBJETADO,
                    "El partido " + partidoId + " está " + partido.getEstado() + ": no hay objeción para resolver");
        }
        List<ErrorCampo> errores = ResultadoService.erroresDelMarcador(request.puntajeLocal(),
                request.puntajeVisitante(), request.penalesLocal(), request.penalesVisitante());
        Evidencia evidencia = evidenciaRepository.findById(request.evidenciaId())
                .filter(e -> e.getPartido().getId().equals(partidoId))
                .orElse(null);
        if (evidencia == null) {
            errores.add(new ErrorCampo("evidenciaId", ErrorCampo.Codigo.VALOR_INVALIDO,
                    "La evidencia tiene que ser una de las del partido"));
        }
        ResultadoService.lanzarSiHay(errores);

        List<Juega> lados = juegaRepository.delPartido(partidoId);
        Decision decision = igualAlCargado(lados, request) ? Decision.CONFIRMADO : Decision.CORREGIDO;
        String justificacion = request.justificacion().strip();
        String sello = sello(partidoId, request.puntajeLocal(), request.puntajeVisitante(), request.penalesLocal(),
                request.penalesVisitante(), evidencia.getHash(), justificacion);
        ResultadoService.fijarMarcador(lados, request.puntajeLocal(), request.puntajeVisitante(),
                request.penalesLocal(), request.penalesVisitante());

        Objecion objecion = objecionRepository.findById(partidoId).orElseThrow();
        objecion.setResueltaPor(usuarioRepository.getReferenceById(usuarioId));
        objecion.setDecision(decision);
        objecion.setJustificacion(justificacion);
        objecion.setEvidencia(evidencia);
        objecion.setLaudoHash(sello);
        objecion.setResueltaEn(Instant.now());
        avanceLlave.confirmar(partido, lados,
                decision == Decision.CONFIRMADO ? EstadoPartido.CONFIRMADO : EstadoPartido.CORREGIDO);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("resultado", "LAUDO");
        detalle.put("decision", decision.name());
        detalle.put("puntajeLocal", request.puntajeLocal());
        detalle.put("puntajeVisitante", request.puntajeVisitante());
        detalle.put("penalesLocal", request.penalesLocal());
        detalle.put("penalesVisitante", request.penalesVisitante());
        detalle.put("evidenciaId", evidencia.getId());
        detalle.put("evidenciaHash", evidencia.getHash());
        detalle.put("laudoHash", sello);
        resultadoService.auditar(partido, usuarioId, AccionAuditoria.LAUDO, detalle);
        return partidoService.detalle(partido, usuarioId);
    }

    /**
     * SHA-256 de {@code laudo:<partido>:<puntajeLocal>:<puntajeVisitante>:<penalesLocal>:<penalesVisitante>:
     * <hash de la evidencia>:<justificación>}, con los penales vacíos si no hay. El front lo calcula igual para
     * mostrarlo antes de confirmar.
     */
    static String sello(Long partidoId, int puntajeLocal, int puntajeVisitante, Integer penalesLocal,
                        Integer penalesVisitante, String evidenciaHash, String justificacion) {
        return SelloTorneo.sha256(String.join(":", "laudo", String.valueOf(partidoId), String.valueOf(puntajeLocal),
                String.valueOf(puntajeVisitante), Objects.toString(penalesLocal, ""),
                Objects.toString(penalesVisitante, ""), evidenciaHash, justificacion));
    }

    private static boolean igualAlCargado(List<Juega> lados, LaudoRequest request) {
        Juega local = Partidos.lado(lados, Lado.LOCAL).orElseThrow();
        Juega visitante = Partidos.lado(lados, Lado.VISITANTE).orElseThrow();
        return request.puntajeLocal().equals(local.getPuntaje())
                && request.puntajeVisitante().equals(visitante.getPuntaje())
                && Objects.equals(request.penalesLocal(), local.getPenales())
                && Objects.equals(request.penalesVisitante(), visitante.getPenales());
    }
}
