package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.ResolucionCancelacion;
import com.hydra.pica.plataforma_pica.torneo.repository.CancelacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soporte revisa una cancelación con gastos (HU-605, T605-2). Rechazar devuelve el 100 % a cada uno que pagó. Quien
 * revisa no puede ser el organizador del torneo, y cada cancelación se resuelve una sola vez.
 */
@Service
@RequiredArgsConstructor
public class RevisionCancelacionService {

    private static final int MOTIVO_MAXIMO = 500;

    private final TorneoRepository torneoRepository;
    private final CancelacionRepository cancelacionRepository;
    private final UsuarioRepository usuarioRepository;
    private final CierreDeCancelacion cierreDeCancelacion;
    private final AuditoriaService auditoriaService;

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
