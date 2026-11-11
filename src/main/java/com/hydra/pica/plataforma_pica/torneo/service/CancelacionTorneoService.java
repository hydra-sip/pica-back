package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.CustodiaService;
import com.hydra.pica.plataforma_pica.common.almacenamiento.AlmacenArchivos;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoCancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.GastoCancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionDelTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionRealizada;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDeLaCancelacion;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDeclarado;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.torneo.repository.CancelacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoCancelacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.event.CancelacionResuelta;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El organizador cancela su torneo (HU-605). Sin gastos se aprueba sola: el torneo queda CANCELADO, las inscripciones
 * confirmadas REINTEGRADA, las que no terminaron de pagar CANCELADA, y cada uno recupera todo lo que pagó. Con gastos
 * queda en revisión para soporte (T605-2) y no se toca nada más.
 */
@Service
@RequiredArgsConstructor
public class CancelacionTorneoService {

    private static final Set<EstadoTorneo> CANCELABLES = Set.of(EstadoTorneo.INSCRIPCION_ABIERTA,
            EstadoTorneo.INSCRIPCION_CERRADA, EstadoTorneo.EN_CURSO);
    private static final int MOTIVO_MAXIMO = 500;
    private static final int CONCEPTO_MAXIMO = 255;
    private static final int GASTOS_MAXIMOS = 10;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    static final String SIN_COMPROBANTE = "Debe adjuntar un comprobante válido por cada gasto declarado";

    private final TorneosPropios torneosPropios;
    private final TorneoRepository torneoRepository;
    private final CancelacionRepository cancelacionRepository;
    private final CustodiaService custodiaService;
    private final CierreDeCancelacion cierreDeCancelacion;
    private final GastoCancelacionRepository gastoCancelacionRepository;
    private final AlmacenArchivos almacen;
    private final ParametrosPlataforma parametrosPlataforma;
    private final ApplicationEventPublisher eventos;
    private final MovimientoRepository movimientoRepository;
    private final AuditoriaService auditoriaService;

    /**
     * Cancela el torneo. Sin gastos se aprueba sola y reintegra todo; con gastos queda CANCELACION_EN_REVISION, guarda
     * los comprobantes y la plata sigue en custodia hasta que soporte decida.
     */
    @Transactional
    public CancelacionRealizada cancelar(Long organizadorId, Long id, SolicitudCancelacion solicitud) {
        List<GastoDeclarado> gastos = solicitud.gastos() == null ? List.of() : solicitud.gastos();
        String motivo = validar(solicitud.motivo(), gastos);
        Torneo torneo = torneosPropios.buscarBloqueado(organizadorId, id);
        if (!CANCELABLES.contains(torneo.getEstado())) {
            throw new ConflictoException(CodigoError.TORNEO_NO_CANCELABLE, "El torneo " + id + " está "
                    + torneo.getEstado() + ": solo se cancela con la inscripción abierta o cerrada, o en curso")
                    .con("estado", torneo.getEstado());
        }
        BigDecimal pozo = custodiaService.recaudadoDelTorneo(id);
        BigDecimal totalGastos = gastos.stream().map(GastoDeclarado::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
        exigirDentroDelTope(pozo, totalGastos);
        boolean automatica = gastos.isEmpty();
        EstadoCancelacion estado = automatica ? EstadoCancelacion.AUTOMATICA : EstadoCancelacion.EN_REVISION;

        torneo.setMotivoCancelacion(motivo);
        if (!automatica) {
            torneo.setEstado(EstadoTorneo.CANCELACION_EN_REVISION);
            torneoRepository.saveAndFlush(torneo);
        }

        Cancelacion cancelacion = new Cancelacion();
        cancelacion.setTorneo(torneo);
        cancelacion.setEstado(estado);
        cancelacion = cancelacionRepository.saveAndFlush(cancelacion);
        guardarGastos(cancelacion, gastos);
        Long cancelacionId = cancelacion.getId();

        auditar(organizadorId, id, cancelacionId, estado, motivo, totalGastos);
        BigDecimal reintegrado = automatica ? cierreDeCancelacion.cerrar(torneo) : BigDecimal.ZERO;
        if (automatica) {
            eventos.publishEvent(new CancelacionResuelta(id));
        }
        return new CancelacionRealizada(cancelacionId, id, estado.name(), totalGastos, pozo, reintegrado);
    }

    /**
     * Lo que el organizador ve de la cancelación de su torneo: antes de cancelar, el pozo y el tope de gastos; después,
     * además, la decisión y cuánto se devolvió y se retuvo. El torneo de otro, 404.
     */
    @Transactional(readOnly = true)
    public CancelacionDelTorneo detalle(Long organizadorId, Long id) {
        Torneo torneo = torneosPropios.buscarPropio(organizadorId, id);
        BigDecimal pozo = custodiaService.recaudadoDelTorneo(id);
        BigDecimal pct = parametrosPlataforma.topeGastosCancelacion();
        Cancelacion cancelacion = cancelacionRepository.findByTorneoId(id).orElse(null);
        if (cancelacion == null) {
            return new CancelacionDelTorneo(pozo, pct, maximo(pozo, pct), null, null, null, List.of(), BigDecimal.ZERO,
                    null, null, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        List<GastoDeLaCancelacion> gastos = gastoCancelacionRepository
                .findByCancelacionIdOrderByIdAsc(cancelacion.getId()).stream()
                .map(g -> new GastoDeLaCancelacion(g.getConcepto(), g.getMonto())).toList();
        BigDecimal totalGastos = gastos.stream().map(GastoDeLaCancelacion::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reintegrado = movimientoRepository.reintegrosDelTorneo(id).stream().map(Movimiento::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal retenido = cancelacion.getEstado() == EstadoCancelacion.APROBADA ? totalGastos : BigDecimal.ZERO;
        return new CancelacionDelTorneo(pozo, pct, maximo(pozo, pct), cancelacion.getEstado().name(),
                torneo.getMotivoCancelacion(), cancelacion.getCreadoEn(), gastos, totalGastos,
                cancelacion.getRevisadaEn(), cancelacion.getMotivoRechazo(), reintegrado, retenido);
    }

    // --- validación ---------------------------------------------------------------------------------

    /** Todos los errores juntos, para que el organizador los vea cada uno junto a su gasto. */
    private static String validar(String motivoRecibido, List<GastoDeclarado> gastos) {
        List<ErrorCampo> errores = new ArrayList<>();
        String motivo = motivoRecibido == null ? "" : motivoRecibido.strip();
        if (motivo.isEmpty()) {
            errores.add(new ErrorCampo("motivo", ErrorCampo.Codigo.REQUERIDO, "Indicá el motivo de la cancelación"));
        } else if (motivo.length() > MOTIVO_MAXIMO) {
            errores.add(new ErrorCampo("motivo", ErrorCampo.Codigo.LONGITUD, "Hasta " + MOTIVO_MAXIMO + " caracteres"));
        }
        if (gastos.size() > GASTOS_MAXIMOS) {
            errores.add(new ErrorCampo("gastos", ErrorCampo.Codigo.LONGITUD, "Hasta " + GASTOS_MAXIMOS + " gastos"));
        }
        for (int i = 0; i < gastos.size(); i++) {
            validar(gastos.get(i), "gastos[" + i + "].", errores);
        }
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
        return motivo;
    }

    private static void validar(GastoDeclarado gasto, String prefijo, List<ErrorCampo> errores) {
        BigDecimal monto = gasto.monto();
        if (monto == null || monto.signum() <= 0 || monto.stripTrailingZeros().scale() > 2) {
            errores.add(new ErrorCampo(prefijo + "monto", ErrorCampo.Codigo.VALOR_INVALIDO,
                    "Tiene que ser mayor a 0, con hasta dos decimales"));
        }
        String concepto = gasto.concepto() == null ? "" : gasto.concepto().strip();
        if (concepto.isEmpty()) {
            errores.add(new ErrorCampo(prefijo + "concepto", ErrorCampo.Codigo.REQUERIDO, "Indicá el concepto del gasto"));
        } else if (concepto.length() > CONCEPTO_MAXIMO) {
            errores.add(new ErrorCampo(prefijo + "concepto", ErrorCampo.Codigo.LONGITUD,
                    "Hasta " + CONCEPTO_MAXIMO + " caracteres"));
        }
        byte[] comprobante = gasto.comprobante();
        String campo = prefijo + "comprobante";
        if (comprobante == null || comprobante.length == 0) {
            errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.REQUERIDO, SIN_COMPROBANTE));
        } else if (comprobante.length > ComprobanteDeGasto.TAMANIO_MAXIMO) {
            errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.LONGITUD, "Hasta 5 MB"));
        } else if (ComprobanteDeGasto.de(comprobante).isEmpty()) {
            errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.FORMATO_INVALIDO, "Tiene que ser un PDF, JPG o PNG"));
        }
    }

    private static BigDecimal maximo(BigDecimal pozo, BigDecimal pct) {
        return pozo.multiply(pct).divide(CIEN, 2, RoundingMode.DOWN);
    }

    /** Sin gastos no hay tope que mirar; con gastos y sin pagos, el tope es 0. */
    private void exigirDentroDelTope(BigDecimal pozo, BigDecimal totalGastos) {
        if (totalGastos.signum() == 0) {
            return;
        }
        BigDecimal pct = parametrosPlataforma.topeGastosCancelacion();
        BigDecimal maximo = maximo(pozo, pct);
        if (totalGastos.compareTo(maximo) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.GASTOS_SOBRE_TOPE,
                    "Los gastos a retener no pueden superar el " + pct.stripTrailingZeros().toPlainString()
                            + "% del pozo. El monto excedente debe ser devuelto a los participantes")
                    .con("pctMaximo", pct)
                    .con("pozo", pozo)
                    .con("maximo", maximo)
                    .con("declarado", totalGastos);
        }
    }

    // --- gastos -------------------------------------------------------------------------------------

    private void guardarGastos(Cancelacion cancelacion, List<GastoDeclarado> gastos) {
        Long torneoId = cancelacion.getTorneo().getId();
        for (int i = 0; i < gastos.size(); i++) {
            GastoDeclarado declarado = gastos.get(i);
            ComprobanteDeGasto formato = ComprobanteDeGasto.de(declarado.comprobante()).orElseThrow();
            String clave = "torneos/" + torneoId + "/cancelacion/gasto-" + (i + 1) + "." + formato.extension;
            almacen.guardar(clave, declarado.comprobante(), formato.tipoContenido);

            GastoCancelacion gasto = new GastoCancelacion();
            gasto.setCancelacion(cancelacion);
            gasto.setMonto(declarado.monto());
            gasto.setConcepto(declarado.concepto().strip());
            gasto.setComprobante(clave);
            gasto.setTipoContenido(formato.tipoContenido);
            gastoCancelacionRepository.save(gasto);
        }
    }

    private void auditar(Long organizadorId, Long torneoId, Long cancelacionId, EstadoCancelacion estado,
                         String motivo, BigDecimal totalGastos) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("cancelacionId", cancelacionId);
        detalle.put("estado", estado);
        detalle.put("motivo", motivo);
        detalle.put("totalGastos", totalGastos);
        auditoriaService.registrar(organizadorId, AccionAuditoria.CANCELACION, "TORNEO", torneoId.toString(), detalle);
    }
}
