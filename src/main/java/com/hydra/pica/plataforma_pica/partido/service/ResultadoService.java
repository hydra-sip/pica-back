package com.hydra.pica.plataforma_pica.partido.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.config.PartidoConfig.PartidoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cargar el resultado de un partido (HU-301, PICA-241).
 *
 * <ul>
 * <li>Lo carga el capitán de uno de los dos lados: el jugador inscripto o el capitán del equipo.</li>
 * <li>Solo con el partido en juego: el torneo en curso, los dos rivales definidos y el resultado sin cargar. Mientras
 * no haya check-in (HU-308), PROGRAMADO también cuenta como en juego.</li>
 * <li>Es eliminación directa: un empate se define por penales, y los penales no pueden empatar.</li>
 * <li>El partido queda RESULTADO_CARGADO y el rival tiene {@code app.partidos.plazo-objecion} para aceptarlo u
 * objetarlo.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ResultadoService {

    static final Set<EstadoPartido> EN_JUEGO =
            EnumSet.of(EstadoPartido.PROGRAMADO, EstadoPartido.CHECK_IN, EstadoPartido.EN_JUEGO);

    private final Partidos partidos;
    private final PartidoService partidoService;
    private final JuegaRepository juegaRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final PartidoProperties partidoProperties;

    @Transactional
    public PartidoDetalle cargar(Long usuarioId, Long partidoId, ResultadoRequest request) {
        Partido partido = partidos.buscarBloqueado(partidoId);
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        Juega mio = Partidos.exigirCapitan(lados, usuarioId, partidoId);
        if (partido.getTorneo().getEstado() != EstadoTorneo.EN_CURSO || lados.size() < 2
                || !EN_JUEGO.contains(partido.getEstado())) {
            throw new ConflictoException(CodigoError.PARTIDO_NO_EN_JUEGO,
                    "El partido " + partidoId + " está " + partido.getEstado()
                            + (lados.size() < 2 ? " y sin rival definido" : "") + ": no se le carga resultado");
        }
        validar(request);

        Juega local = Partidos.lado(lados, Lado.LOCAL).orElseThrow();
        Juega visitante = Partidos.lado(lados, Lado.VISITANTE).orElseThrow();
        local.setPuntaje(request.puntajeLocal());
        local.setPenales(request.penalesLocal());
        visitante.setPuntaje(request.puntajeVisitante());
        visitante.setPenales(request.penalesVisitante());
        boolean ganaLocal = request.puntajeLocal() > request.puntajeVisitante()
                || (request.puntajeLocal().equals(request.puntajeVisitante())
                        && request.penalesLocal() > request.penalesVisitante());
        local.setEsGanador(ganaLocal);
        visitante.setEsGanador(!ganaLocal);

        Instant ahora = Instant.now();
        partido.setEstado(EstadoPartido.RESULTADO_CARGADO);
        partido.setResultadoCargadoEn(ahora);
        partido.setObjecionVenceEn(ahora.plus(partidoProperties.plazoObjecion()));
        partido.setResultadoCargadoPor(mio.getInscripcion());
        auditar(partido, usuarioId, mio, request);
        return partidoService.detalle(partido, usuarioId);
    }

    /** Todos los errores juntos en un 400, como el resto de las validaciones. */
    private static void validar(ResultadoRequest request) {
        List<ErrorCampo> errores = new ArrayList<>();
        boolean empate = request.puntajeLocal().equals(request.puntajeVisitante());
        if (empate) {
            if (request.penalesLocal() == null) {
                errores.add(new ErrorCampo("penalesLocal", ErrorCampo.Codigo.REQUERIDO,
                        "Con empate, el partido se define por penales"));
            }
            if (request.penalesVisitante() == null) {
                errores.add(new ErrorCampo("penalesVisitante", ErrorCampo.Codigo.REQUERIDO,
                        "Con empate, el partido se define por penales"));
            }
            if (errores.isEmpty() && request.penalesLocal().equals(request.penalesVisitante())) {
                errores.add(new ErrorCampo("penalesVisitante", ErrorCampo.Codigo.VALOR_INVALIDO,
                        "Los penales tienen que definir un ganador"));
            }
        } else {
            if (request.penalesLocal() != null) {
                errores.add(new ErrorCampo("penalesLocal", ErrorCampo.Codigo.VALOR_INVALIDO,
                        "Solo hay penales si el marcador termina empatado"));
            }
            if (request.penalesVisitante() != null) {
                errores.add(new ErrorCampo("penalesVisitante", ErrorCampo.Codigo.VALOR_INVALIDO,
                        "Solo hay penales si el marcador termina empatado"));
            }
        }
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
    }

    private void auditar(Partido partido, Long usuarioId, Juega mio, ResultadoRequest request) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("resultado", "CARGADO");
        detalle.put("inscripcionId", mio.getId().getInscripcionId());
        detalle.put("lado", mio.getLado().name());
        detalle.put("puntajeLocal", request.puntajeLocal());
        detalle.put("puntajeVisitante", request.puntajeVisitante());
        detalle.put("penalesLocal", request.penalesLocal());
        detalle.put("penalesVisitante", request.penalesVisitante());
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        auditoria.setAccion(AccionAuditoria.MODIFICACION);
        auditoria.setEntidad("PARTIDO");
        auditoria.setEntidadId(String.valueOf(partido.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }
}
