package com.hydra.pica.plataforma_pica.partido.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.hydra.pica.plataforma_pica.partido.domain.Objecion;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionRequest;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoObjetado;
import com.hydra.pica.plataforma_pica.partido.repository.EvidenciaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.ObjecionRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cargar el resultado de un partido (HU-301, PICA-241), aceptarlo y confirmarlo al vencer el plazo (PICA-242), u
 * objetarlo (HU-302, PICA-244).
 *
 * <ul>
 * <li>Lo carga el capitán de uno de los dos lados: el jugador inscripto o el capitán del equipo.</li>
 * <li>Solo con el partido en juego: el torneo en curso, los dos rivales definidos y el resultado sin cargar. El
 * check-in (HU-308) no es requisito: PROGRAMADO también cuenta como en juego.</li>
 * <li>Es eliminación directa: un empate se define por penales, y los penales no pueden empatar.</li>
 * <li>El partido queda RESULTADO_CARGADO y el rival tiene {@code app.partidos.plazo-objecion} para aceptarlo u
 * objetarlo.</li>
 * <li>Lo acepta el capitán del otro lado, el que no lo cargó. Si nadie lo objeta (HU-302) en el plazo, se confirma
 * solo ({@link ConfirmacionAutomatica}). En los dos casos el partido queda CONFIRMADO y el ganador avanza
 * ({@link AvanceLlave}).</li>
 * <li>Si lo objeta, queda OBJETADO hasta que el árbitro lo resuelva (HU-304).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ResultadoService {

    private final Partidos partidos;
    private final PartidoService partidoService;
    private final JuegaRepository juegaRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final PartidoProperties partidoProperties;
    private final AvanceLlave avanceLlave;
    private final ObjecionRepository objecionRepository;
    private final EvidenciaRepository evidenciaRepository;
    private final ApplicationEventPublisher eventos;

    @Transactional
    public PartidoDetalle cargar(Long usuarioId, Long partidoId, ResultadoRequest request) {
        Partido partido = partidos.buscarBloqueado(partidoId);
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        Juega mio = Partidos.exigirCapitan(lados, usuarioId, partidoId);
        Partidos.exigirEnJuego(partido, lados, "le carga resultado");
        validar(request);

        fijarMarcador(lados, request.puntajeLocal(), request.puntajeVisitante(), request.penalesLocal(),
                request.penalesVisitante());

        Instant ahora = Instant.now();
        partido.setEstado(EstadoPartido.RESULTADO_CARGADO);
        partido.setResultadoCargadoEn(ahora);
        partido.setObjecionVenceEn(ahora.plus(partidoProperties.plazoObjecion()));
        partido.setResultadoCargadoPor(mio.getInscripcion());
        Map<String, Object> detalle = detalle("CARGADO", mio);
        detalle.put("puntajeLocal", request.puntajeLocal());
        detalle.put("puntajeVisitante", request.puntajeVisitante());
        detalle.put("penalesLocal", request.penalesLocal());
        detalle.put("penalesVisitante", request.penalesVisitante());
        auditar(partido, usuarioId, detalle);
        return partidoService.detalle(partido, usuarioId);
    }

    /** El rival da por bueno el resultado antes de que venza el plazo: no hace falta esperarlo. */
    @Transactional
    public PartidoDetalle aceptar(Long usuarioId, Long partidoId) {
        Partido partido = partidos.buscarBloqueado(partidoId);
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        Juega mio = exigirRival(partido, lados, usuarioId, "aceptar");
        avanceLlave.confirmar(partido, lados, EstadoPartido.CONFIRMADO);
        auditar(partido, usuarioId, detalle("ACEPTADO", mio));
        return partidoService.detalle(partido, usuarioId);
    }

    /**
     * El rival no está de acuerdo (HU-302): deja el resultado que dice que fue, el motivo y al menos una evidencia
     * suya. El partido queda OBJETADO: no se confirma solo ni avanza la llave hasta el laudo (HU-304), y el que lo
     * cargó ya no lo puede cambiar. Se avisa al organizador y a los árbitros ({@link AvisoObjecion}).
     */
    @Transactional
    public PartidoDetalle objetar(Long usuarioId, Long partidoId, ObjecionRequest request) {
        Partido partido = partidos.buscarBloqueado(partidoId);
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        Juega mio = exigirRival(partido, lados, usuarioId, "objetar");
        if (!partido.getObjecionVenceEn().isAfter(Instant.now())) {
            throw new ConflictoException(CodigoError.PLAZO_OBJECION_VENCIDO,
                    "Venció el plazo para objetar el resultado del partido " + partidoId);
        }
        List<ErrorCampo> errores = erroresDelMarcador(request.puntajeLocal(), request.puntajeVisitante(),
                request.penalesLocal(), request.penalesVisitante());
        if (!evidenciaRepository.existsByPartidoIdAndInscripcionId(partidoId, mio.getId().getInscripcionId())) {
            errores.add(new ErrorCampo("evidencia", ErrorCampo.Codigo.REQUERIDO,
                    "Para objetar hay que adjuntar al menos una evidencia"));
        }
        lanzarSiHay(errores);

        Objecion objecion = new Objecion(partido);
        objecion.setInscripcion(mio.getInscripcion());
        objecion.setMotivo(request.motivo().strip());
        objecion.setPuntajeLocal(request.puntajeLocal());
        objecion.setPuntajeVisitante(request.puntajeVisitante());
        objecion.setPenalesLocal(request.penalesLocal());
        objecion.setPenalesVisitante(request.penalesVisitante());
        objecionRepository.save(objecion);
        partido.setEstado(EstadoPartido.OBJETADO);

        Map<String, Object> detalle = detalle("OBJETADO", mio);
        detalle.put("puntajeLocal", request.puntajeLocal());
        detalle.put("puntajeVisitante", request.puntajeVisitante());
        detalle.put("penalesLocal", request.penalesLocal());
        detalle.put("penalesVisitante", request.penalesVisitante());
        auditar(partido, usuarioId, detalle);
        eventos.publishEvent(new ResultadoObjetado(partido.getTorneo().getId(), partidoId));
        return partidoService.detalle(partido, usuarioId);
    }

    /** El capitán del lado que no cargó el resultado, con el resultado pendiente: el que lo acepta u objeta. */
    private static Juega exigirRival(Partido partido, List<Juega> lados, Long usuarioId, String accion) {
        Juega mio = Partidos.exigirCapitan(lados, usuarioId, partido.getId());
        if (partido.getEstado() != EstadoPartido.RESULTADO_CARGADO) {
            throw new ConflictoException(CodigoError.RESULTADO_NO_PENDIENTE,
                    "El partido " + partido.getId() + " está " + partido.getEstado() + ": no hay resultado para "
                            + accion);
        }
        if (partido.getResultadoCargadoPor().getId().equals(mio.getId().getInscripcionId())) {
            throw new ConflictoException(CodigoError.RESULTADO_PROPIO,
                    "El resultado del partido " + partido.getId() + " lo cargó este lado: lo puede " + accion
                            + " el rival");
        }
        return mio;
    }

    /**
     * Lo llama {@link ConfirmacionAutomatica} por cada partido con el plazo vencido. Vuelve a mirar con la fila
     * bloqueada: entre la consulta y acá el rival pudo haberlo aceptado u objetado.
     *
     * @return si lo confirmó
     */
    @Transactional
    public boolean confirmarSiVencio(Long partidoId, Instant ahora) {
        Partido partido = partidos.buscarBloqueado(partidoId);
        if (partido.getEstado() != EstadoPartido.RESULTADO_CARGADO || partido.getObjecionVenceEn().isAfter(ahora)) {
            return false;
        }
        avanceLlave.confirmar(partido, juegaRepository.delPartido(partidoId), EstadoPartido.CONFIRMADO);
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("resultado", "CONFIRMADO_POR_PLAZO");
        detalle.put("objecionVenceEn", partido.getObjecionVenceEn().toString());
        auditar(partido, null, detalle);
        return true;
    }

    /** Todos los errores juntos en un 400, como el resto de las validaciones. */
    private static void validar(ResultadoRequest request) {
        lanzarSiHay(erroresDelMarcador(request.puntajeLocal(), request.puntajeVisitante(), request.penalesLocal(),
                request.penalesVisitante()));
    }

    static List<ErrorCampo> erroresDelMarcador(Integer puntajeLocal, Integer puntajeVisitante,
                                                       Integer penalesLocal, Integer penalesVisitante) {
        List<ErrorCampo> errores = new ArrayList<>();
        if (puntajeLocal.equals(puntajeVisitante)) {
            if (penalesLocal == null) {
                errores.add(new ErrorCampo("penalesLocal", ErrorCampo.Codigo.REQUERIDO,
                        "Con empate, el partido se define por penales"));
            }
            if (penalesVisitante == null) {
                errores.add(new ErrorCampo("penalesVisitante", ErrorCampo.Codigo.REQUERIDO,
                        "Con empate, el partido se define por penales"));
            }
            if (errores.isEmpty() && penalesLocal.equals(penalesVisitante)) {
                errores.add(new ErrorCampo("penalesVisitante", ErrorCampo.Codigo.VALOR_INVALIDO,
                        "Los penales tienen que definir un ganador"));
            }
        } else {
            if (penalesLocal != null) {
                errores.add(new ErrorCampo("penalesLocal", ErrorCampo.Codigo.VALOR_INVALIDO,
                        "Solo hay penales si el marcador termina empatado"));
            }
            if (penalesVisitante != null) {
                errores.add(new ErrorCampo("penalesVisitante", ErrorCampo.Codigo.VALOR_INVALIDO,
                        "Solo hay penales si el marcador termina empatado"));
            }
        }
        return errores;
    }

    /** Puntajes, penales y ganador de cada lado; el marcador ya está validado. */
    static void fijarMarcador(List<Juega> lados, int puntajeLocal, int puntajeVisitante, Integer penalesLocal,
                              Integer penalesVisitante) {
        Juega local = Partidos.lado(lados, Lado.LOCAL).orElseThrow();
        Juega visitante = Partidos.lado(lados, Lado.VISITANTE).orElseThrow();
        local.setPuntaje(puntajeLocal);
        local.setPenales(penalesLocal);
        visitante.setPuntaje(puntajeVisitante);
        visitante.setPenales(penalesVisitante);
        boolean ganaLocal = puntajeLocal > puntajeVisitante
                || (puntajeLocal == puntajeVisitante && penalesLocal > penalesVisitante);
        local.setEsGanador(ganaLocal);
        visitante.setEsGanador(!ganaLocal);
    }

    static void lanzarSiHay(List<ErrorCampo> errores) {
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
    }

    /** El registro de algo que hizo un lado: el capitán de esa inscripción. */
    static Map<String, Object> detalle(String resultado, Juega lado) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("resultado", resultado);
        detalle.put("inscripcionId", lado.getId().getInscripcionId());
        detalle.put("lado", lado.getLado().name());
        return detalle;
    }

    /** @param usuarioId null si lo hizo el sistema (el plazo venció) */
    void auditar(Partido partido, Long usuarioId, Map<String, Object> detalle) {
        auditar(partido, usuarioId, AccionAuditoria.MODIFICACION, detalle);
    }

    void auditar(Partido partido, Long usuarioId, AccionAuditoria accion, Map<String, Object> detalle) {
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(usuarioId == null ? null : usuarioRepository.getReferenceById(usuarioId));
        auditoria.setAccion(accion);
        auditoria.setEntidad("PARTIDO");
        auditoria.setEntidadId(String.valueOf(partido.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }
}
