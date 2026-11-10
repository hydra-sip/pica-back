package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.billetera.service.CustodiaService;
import com.hydra.pica.plataforma_pica.billetera.service.Reintegrador;
import com.hydra.pica.plataforma_pica.common.almacenamiento.AlmacenArchivos;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoCancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.GastoCancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionEnRevision;
import com.hydra.pica.plataforma_pica.torneo.dto.Comprobante;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoEnRevision;
import com.hydra.pica.plataforma_pica.torneo.dto.OrganizadorResumen;
import com.hydra.pica.plataforma_pica.torneo.dto.ResolucionCancelacion;
import com.hydra.pica.plataforma_pica.torneo.repository.CancelacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoCancelacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soporte revisa una cancelación con gastos (HU-605, T605-2). Aprobar descuenta a cada uno su parte de los gastos y se
 * los paga al organizador; rechazar devuelve el 100 % a cada uno que pagó. Quien
 * revisa no puede ser el organizador del torneo, y cada cancelación se resuelve una sola vez.
 */
@Service
@RequiredArgsConstructor
public class RevisionCancelacionService {

    private static final int MOTIVO_MAXIMO = 500;

    private final TorneoRepository torneoRepository;
    private final CancelacionRepository cancelacionRepository;
    private final UsuarioRepository usuarioRepository;
    private final GastoCancelacionRepository gastoCancelacionRepository;
    private final CierreDeCancelacion cierreDeCancelacion;
    private final CustodiaService custodiaService;
    private final AlmacenArchivos almacen;
    private final AuditoriaService auditoriaService;

    /** Las cancelaciones en revisión, de la más vieja a la más nueva; el orden no se elige desde afuera. */
    @Transactional(readOnly = true)
    public Page<CancelacionEnRevision> cola(Pageable pageable) {
        Pageable enOrden = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return cancelacionRepository.conEstado(EstadoCancelacion.EN_REVISION, enOrden).map(c -> {
            Torneo torneo = c.getTorneo();
            List<GastoEnRevision> gastos = gastoCancelacionRepository.findByCancelacionIdOrderByIdAsc(c.getId())
                    .stream().map(GastoEnRevision::de).toList();
            BigDecimal total = gastos.stream().map(GastoEnRevision::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new CancelacionEnRevision(torneo.getId(), torneo.getNombre(), torneo.getSlug(),
                    OrganizadorResumen.de(torneo.getOrganizador()), torneo.getMotivoCancelacion(), c.getCreadoEn(),
                    custodiaService.recaudadoDelTorneo(torneo.getId()), total, gastos);
        });
    }

    /** El archivo de un gasto de la cancelación de ese torneo; de otra, o sin archivo, 404 COMPROBANTE_NO_ENCONTRADO. */
    @Transactional(readOnly = true)
    public Comprobante comprobante(Long torneoId, Long gastoId) {
        GastoCancelacion gasto = gastoCancelacionRepository.findById(gastoId)
                .filter(g -> g.getCancelacion().getTorneo().getId().equals(torneoId))
                .orElseThrow(() -> comprobanteNoEncontrado(gastoId));
        byte[] contenido = almacen.leer(gasto.getComprobante()).orElseThrow(() -> comprobanteNoEncontrado(gastoId));
        return new Comprobante(contenido, gasto.getTipoContenido());
    }

    private static NoEncontradoException comprobanteNoEncontrado(Long gastoId) {
        return new NoEncontradoException(CodigoError.COMPROBANTE_NO_ENCONTRADO,
                "No existe el comprobante del gasto " + gastoId);
    }

    /**
     * Aprueba la retención: a cada uno que pagó se le descuenta su parte de los gastos y el organizador los cobra.
     */
    @Transactional
    public ResolucionCancelacion aprobar(Long revisorId, Long torneoId) {
        Torneo torneo = enRevision(revisorId, torneoId);
        Cancelacion cancelacion = cancelacionRepository.findByTorneoId(torneoId).orElseThrow();
        BigDecimal gastos = gastoCancelacionRepository.totalDeLaCancelacion(cancelacion.getId());
        Long organizadorId = torneo.getOrganizador().getId();
        cancelacion.setEstado(EstadoCancelacion.APROBADA);
        resolver(cancelacion, revisorId);

        BigDecimal reintegrado = cierreDeCancelacion.cerrar(torneo, new Reintegrador.Retencion(organizadorId, gastos));
        auditar(revisorId, torneoId, EstadoCancelacion.APROBADA, null, reintegrado, gastos);
        return new ResolucionCancelacion(torneoId, EstadoCancelacion.APROBADA.name(), reintegrado, gastos, gastos);
    }

    @Transactional
    public ResolucionCancelacion rechazar(Long revisorId, Long torneoId, String motivoRecibido) {
        String motivo = motivoRecibido == null ? "" : motivoRecibido.strip();
        if (motivo.isEmpty() || motivo.length() > MOTIVO_MAXIMO) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", List.of(motivo.isEmpty()
                            ? new ErrorCampo("motivo", ErrorCampo.Codigo.REQUERIDO, "Indicá el motivo del rechazo")
                            : new ErrorCampo("motivo", ErrorCampo.Codigo.LONGITUD,
                                    "Hasta " + MOTIVO_MAXIMO + " caracteres")));
        }
        Torneo torneo = enRevision(revisorId, torneoId);
        Cancelacion cancelacion = cancelacionRepository.findByTorneoId(torneoId).orElseThrow();
        cancelacion.setEstado(EstadoCancelacion.RECHAZADA);
        cancelacion.setMotivoRechazo(motivo);
        resolver(cancelacion, revisorId);

        BigDecimal reintegrado = cierreDeCancelacion.cerrar(torneo);
        auditar(revisorId, torneoId, EstadoCancelacion.RECHAZADA, motivo, reintegrado, BigDecimal.ZERO);
        return new ResolucionCancelacion(torneoId, EstadoCancelacion.RECHAZADA.name(), reintegrado, BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    /**
     * El torneo bloqueado, con su cancelación en revisión. Sin cancelación, 404; si el que revisa lo organiza, 409
     * CANCELACION_PROPIA; ya resuelta (o el torneo no está en revisión), 409 CANCELACION_YA_RESUELTA.
     */
    private Torneo enRevision(Long revisorId, Long torneoId) {
        Torneo torneo = torneoRepository.buscarBloqueado(torneoId).filter(t -> t.getEliminadoEn() == null)
                .orElseThrow(() -> noEncontrada(torneoId));
        Cancelacion cancelacion = cancelacionRepository.findByTorneoId(torneoId)
                .orElseThrow(() -> noEncontrada(torneoId));
        if (torneo.getOrganizador().getId().equals(revisorId)) {
            throw new ConflictoException(CodigoError.CANCELACION_PROPIA,
                    "La cancelación del torneo " + torneoId + " la tiene que revisar otro: sos su organizador");
        }
        if (cancelacion.getEstado() != EstadoCancelacion.EN_REVISION
                || torneo.getEstado() != EstadoTorneo.CANCELACION_EN_REVISION) {
            throw new ConflictoException(CodigoError.CANCELACION_YA_RESUELTA,
                    "La cancelación del torneo " + torneoId + " ya está " + cancelacion.getEstado())
                    .con("estado", cancelacion.getEstado());
        }
        return torneo;
    }

    private void resolver(Cancelacion cancelacion, Long revisorId) {
        cancelacion.setRevisor(usuarioRepository.getReferenceById(revisorId));
        cancelacion.setRevisadaEn(Instant.now());
        cancelacionRepository.saveAndFlush(cancelacion);
    }

    private void auditar(Long revisorId, Long torneoId, EstadoCancelacion decision, String motivo,
                         BigDecimal reintegrado, BigDecimal alOrganizador) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("estado", decision);
        if (motivo != null) {
            detalle.put("motivoRechazo", motivo);
        }
        detalle.put("reintegrado", reintegrado);
        detalle.put("alOrganizador", alOrganizador);
        auditoriaService.registrar(revisorId, AccionAuditoria.CANCELACION, "TORNEO", torneoId.toString(), detalle);
    }

    private static NoEncontradoException noEncontrada(Long torneoId) {
        return new NoEncontradoException(CodigoError.CANCELACION_NO_ENCONTRADA,
                "El torneo " + torneoId + " no tiene una cancelación");
    }
}
