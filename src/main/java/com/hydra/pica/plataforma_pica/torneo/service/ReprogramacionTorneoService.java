package com.hydra.pica.plataforma_pica.torneo.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.config.TorneoConfig.TorneoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.EntidadAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.MotivoCierre;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.inscripcion.service.CierreInscripcion;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Reprogramacion;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionRequest;
import com.hydra.pica.plataforma_pica.torneo.event.PlazoReprogramacionVencido;
import com.hydra.pica.plataforma_pica.torneo.event.ReprogramacionPorVencer;
import com.hydra.pica.plataforma_pica.torneo.event.TorneoEstadoCambiado;
import com.hydra.pica.plataforma_pica.torneo.event.TorneoReprogramado;
import com.hydra.pica.plataforma_pica.torneo.repository.ReprogramacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El organizador reprograma su torneo sin cancelarlo (CR-01, PICA-391): cambian las fechas, la plata sigue en custodia
 * y se abre un plazo para que los inscriptos digan que la nueva fecha no les sirve (CR-01-3).
 *
 * <ul>
 * <li>Con la inscripción abierta, sigue abierta; mientras corre el plazo, completar el cupo no la cierra.</li>
 * <li>Con la inscripción cerrada o en curso sin resultados, queda cerrada durante el plazo: la llave (y los grupos) se
 * descartan, y también la lista sellada y el compromiso del sorteo de grupos, que vuelven a fijarse en el próximo
 * cierre. Así los que rechazan liberan lugares sin que nadie los ocupe antes de tiempo.</li>
 * <li>Al vencer el plazo ({@link VencimientoReprogramacion}), si quedaron lugares la inscripción se abre hasta el nuevo
 * cierre; si el cupo sigue completo, se cierra de nuevo y se vuelve a sellar la lista.</li>
 * </ul>
 * Con {@code app.torneos.sin-validar-fechas} (PICA-448) las fechas pueden ser pasadas y el cierre no tiene que ser
 * posterior al plazo; el orden entre inicio, fin y cierre, y el margen, se exigen igual.
 */
@Service
@RequiredArgsConstructor
public class ReprogramacionTorneoService {

    private static final Set<EstadoTorneo> REPROGRAMABLES = Set.of(EstadoTorneo.INSCRIPCION_ABIERTA,
            EstadoTorneo.INSCRIPCION_CERRADA, EstadoTorneo.EN_CURSO);
    private static final int MOTIVO_MAXIMO = 500;
    /** Cuánto antes de que venza el plazo para rechazar sale el recordatorio (CR-01-4). */
    static final Duration RECORDATORIO = Duration.ofDays(1);

    private final TorneosPropios torneosPropios;
    private final TorneoRepository torneoRepository;
    private final ReprogramacionRepository reprogramacionRepository;
    private final InscripcionRepository inscripcionRepository;
    private final UsuarioRepository usuarioRepository;
    private final BracketService bracketService;
    private final CierreInscripcion cierreInscripcion;
    private final AuditoriaService auditoriaService;
    private final ParametrosPlataforma parametrosPlataforma;
    private final TorneoProperties torneoProperties;
    private final ApplicationEventPublisher eventos;

    @Transactional
    public ReprogramacionDetalle reprogramar(Long organizadorId, Long id, ReprogramacionRequest request) {
        Torneo torneo = torneosPropios.buscarBloqueado(organizadorId, id);
        EstadoTorneo anterior = torneo.getEstado();
        if (!REPROGRAMABLES.contains(anterior)) {
            throw new ConflictoException(CodigoError.TORNEO_NO_REPROGRAMABLE, "El torneo " + id + " está " + anterior
                    + ": solo se reprograma con la inscripción abierta o cerrada, o en curso sin resultados")
                    .con("estado", anterior);
        }
        if (reprogramacionRepository.existsByTorneoIdAndPlazoProcesadoEnIsNull(id)) {
            throw new ConflictoException(CodigoError.REPROGRAMACION_EN_CURSO,
                    "El torneo " + id + " ya tiene una reprogramación con el plazo para rechazar abierto");
        }
        String motivo = validar(request);

        Reprogramacion reprogramacion = new Reprogramacion();
        reprogramacion.setTorneo(torneo);
        reprogramacion.setFechaInicioAnterior(torneo.getFechaInicio());
        reprogramacion.setFechaFinAnterior(torneo.getFechaFin());
        reprogramacion.setFechaInicioNueva(request.fechaInicio());
        reprogramacion.setFechaFinNueva(request.fechaFin());
        reprogramacion.setCierreInscripcionNuevo(request.cierreInscripcion());
        reprogramacion.setRechazoHasta(request.rechazoHasta());
        reprogramacion.setMotivo(motivo);
        reprogramacion.setCreadaPor(usuarioRepository.getReferenceById(organizadorId));

        Map<String, Object> descartado = anterior == EstadoTorneo.INSCRIPCION_ABIERTA ? Map.of() : volverACerrada(torneo);
        torneo.setFechaInicio(request.fechaInicio());
        torneo.setFechaFin(request.fechaFin());
        torneo.setCierreInscripcionEn(request.cierreInscripcion());
        torneoRepository.saveAndFlush(torneo);
        reprogramacion = reprogramacionRepository.saveAndFlush(reprogramacion);

        auditar(organizadorId, reprogramacion, anterior, descartado);
        eventos.publishEvent(new TorneoReprogramado(id, reprogramacion.getId()));
        if (torneo.getEstado() != anterior) {
            eventos.publishEvent(new TorneoEstadoCambiado(id, anterior, torneo.getEstado()));
        }
        return ReprogramacionDetalle.de(reprogramacion);
    }

    /** El historial, de la más vieja a la más nueva. Público: el de un torneo publicado. */
    @Transactional(readOnly = true)
    public List<ReprogramacionDetalle> historial(Long id) {
        torneoRepository.findByIdAndEliminadoEnIsNull(id)
                .filter(t -> t.getEstado() != EstadoTorneo.BORRADOR)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo " + id));
        return reprogramacionRepository.findByTorneoIdOrderByIdAsc(id).stream().map(ReprogramacionDetalle::de).toList();
    }

    /**
     * Resuelve el plazo vencido de la reprogramación del torneo: si quedaron lugares, abre la inscripción hasta el
     * nuevo cierre; si el cupo sigue completo, la cierra y vuelve a sellar la lista. Un torneo que mientras tanto se
     * canceló solo queda con el plazo resuelto. Bloquea la fila del torneo y vuelve a mirar: otra réplica pudo
     * resolverlo mientras tanto.
     *
     * @return si lo resolvió
     */
    @Transactional
    public boolean resolverPlazoSiVencio(Long torneoId, Instant ahora) {
        Torneo torneo = torneoRepository.buscarBloqueado(torneoId).orElse(null);
        Reprogramacion reprogramacion = torneo == null ? null
                : reprogramacionRepository.findByTorneoIdAndPlazoProcesadoEnIsNull(torneoId).orElse(null);
        if (reprogramacion == null || reprogramacion.getRechazoHasta().isAfter(ahora)) {
            return false;
        }
        EstadoTorneo anterior = torneo.getEstado();
        if (anterior == EstadoTorneo.INSCRIPCION_ABIERTA || anterior == EstadoTorneo.INSCRIPCION_CERRADA) {
            long confirmadas = inscripcionRepository.countByTorneoIdAndEstado(torneoId, EstadoInscripcion.CONFIRMADA);
            if (confirmadas >= torneo.getCupoMaximo()) {
                cierreInscripcion.cerrar(torneo, MotivoCierre.CUPO, null);
            } else if (anterior == EstadoTorneo.INSCRIPCION_CERRADA) {
                torneo.setEstado(EstadoTorneo.INSCRIPCION_ABIERTA);
                torneoRepository.saveAndFlush(torneo);
            }
        }
        reprogramacion.setPlazoProcesadoEn(ahora);
        reprogramacionRepository.saveAndFlush(reprogramacion);
        if (torneo.getEstado() != anterior) {
            eventos.publishEvent(new TorneoEstadoCambiado(torneoId, anterior, torneo.getEstado()));
        }
        eventos.publishEvent(new PlazoReprogramacionVencido(torneoId, reprogramacion.getId()));
        return true;
    }

    /**
     * El recordatorio a los que siguen inscriptos (CR-01-4): si al plazo para rechazar le queda {@link #RECORDATORIO}
     * o menos y todavía no salió, lo marca y publica {@link ReprogramacionPorVencer}. Bloquea la fila del torneo y
     * vuelve a mirar, así sale una sola vez aunque corra en varias réplicas.
     *
     * @return si lo mandó
     */
    @Transactional
    public boolean recordarSiFaltaPoco(Long torneoId, Instant ahora) {
        Torneo torneo = torneoRepository.buscarBloqueado(torneoId).orElse(null);
        Reprogramacion reprogramacion = torneo == null ? null
                : reprogramacionRepository.findByTorneoIdAndPlazoProcesadoEnIsNull(torneoId).orElse(null);
        if (reprogramacion == null || reprogramacion.getRecordatorioEnviadoEn() != null
                || !reprogramacion.getRechazoHasta().isAfter(ahora)
                || reprogramacion.getRechazoHasta().isAfter(ahora.plus(RECORDATORIO))) {
            return false;
        }
        reprogramacion.setRecordatorioEnviadoEn(ahora);
        reprogramacionRepository.saveAndFlush(reprogramacion);
        eventos.publishEvent(new ReprogramacionPorVencer(torneoId, reprogramacion.getId()));
        return true;
    }

    /** Lo que se fijó al cerrar la inscripción vuelve atrás; devuelve lo descartado, para la auditoría. */
    private Map<String, Object> volverACerrada(Torneo torneo) {
        Map<String, Object> descartado = new LinkedHashMap<>();
        if (torneo.getSemillaBracket() != null) {
            descartado.put("semillaBracket", torneo.getSemillaBracket());
            bracketService.descartar(torneo);
        }
        if (torneo.getListaHash() != null) {
            descartado.put("listaHash", torneo.getListaHash());
        }
        if (torneo.getCompromisoHash() != null) {
            descartado.put("compromisoHash", torneo.getCompromisoHash());
        }
        torneo.setListaHash(null);
        torneo.setListaSelladaEn(null);
        torneo.setSemillaRonda(null);
        torneo.setCompromisoHash(null);
        torneo.setEstado(EstadoTorneo.INSCRIPCION_CERRADA);
        return descartado;
    }

    private void auditar(Long organizadorId, Reprogramacion r, EstadoTorneo estadoAnterior,
                         Map<String, Object> descartado) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("reprogramacionId", r.getId());
        detalle.put("estadoAnterior", estadoAnterior);
        detalle.put("fechaInicioAnterior", r.getFechaInicioAnterior());
        detalle.put("fechaInicioNueva", r.getFechaInicioNueva());
        detalle.put("fechaFinNueva", r.getFechaFinNueva());
        detalle.put("cierreInscripcion", r.getCierreInscripcionNuevo());
        detalle.put("rechazoHasta", r.getRechazoHasta());
        detalle.put("motivo", r.getMotivo());
        detalle.putAll(descartado);
        auditoriaService.registrar(organizadorId, AccionAuditoria.REPROGRAMACION, EntidadAuditoria.TORNEO,
                r.getTorneo().getId().toString(), detalle);
    }

    // --- validación ---------------------------------------------------------------------------------

    /** Todos los errores juntos, en un solo 400. */
    private String validar(ReprogramacionRequest request) {
        List<ErrorCampo> errores = new ArrayList<>();
        String motivo = request.motivo() == null ? "" : request.motivo().strip();
        if (motivo.isEmpty()) {
            errores.add(new ErrorCampo("motivo", ErrorCampo.Codigo.REQUERIDO, "Indicá el motivo de la reprogramación"));
        } else if (motivo.length() > MOTIVO_MAXIMO) {
            errores.add(new ErrorCampo("motivo", ErrorCampo.Codigo.LONGITUD, "Hasta " + MOTIVO_MAXIMO + " caracteres"));
        }
        Instant inicio = request.fechaInicio();
        Instant cierre = request.cierreInscripcion();
        Instant rechazo = request.rechazoHasta();
        requerido(errores, "fechaInicio", inicio);
        requerido(errores, "cierreInscripcion", cierre);
        requerido(errores, "rechazoHasta", rechazo);

        boolean futuras = !torneoProperties.sinValidarFechas();
        if (futuras && rechazo != null && !rechazo.isAfter(Instant.now())) {
            errores.add(fechas("rechazoHasta", "No puede ser anterior a ahora"));
        }
        if (futuras && rechazo != null && cierre != null && !cierre.isAfter(rechazo)) {
            errores.add(fechas("cierreInscripcion",
                    "La inscripción tiene que cerrar después del plazo para rechazar la nueva fecha"));
        }
        if (inicio != null && request.fechaFin() != null && request.fechaFin().isBefore(inicio)) {
            errores.add(fechas("fechaFin", "No puede ser anterior a la fecha de inicio"));
        }
        if (inicio != null && cierre != null) {
            Duration margen = parametrosPlataforma.margenCierreInicio();
            if (cierre.plus(margen).isAfter(inicio)) {
                errores.add(fechas("cierreInscripcion",
                        "La inscripción tiene que cerrar al menos " + texto(margen) + " antes del inicio"));
            }
        }
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
        return motivo;
    }

    private static void requerido(List<ErrorCampo> errores, String campo, Instant valor) {
        if (valor == null) {
            errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.REQUERIDO, "Es obligatoria"));
        }
    }

    private static ErrorCampo fechas(String campo, String mensaje) {
        return new ErrorCampo(campo, ErrorCampo.Codigo.FECHAS_INVALIDAS, mensaje);
    }

    private static String texto(Duration margen) {
        long minutos = margen.toMinutes();
        return minutos % 60 == 0 && minutos > 0 ? minutos / 60 + " h" : minutos + " min";
    }
}
