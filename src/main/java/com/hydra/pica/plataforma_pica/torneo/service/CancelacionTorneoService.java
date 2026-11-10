package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hydra.pica.plataforma_pica.billetera.service.CustodiaService;
import com.hydra.pica.plataforma_pica.billetera.service.Reintegrador;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoCancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionRealizada;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.torneo.repository.CancelacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El organizador cancela su torneo (HU-605). Sin gastos se aprueba sola: el torneo queda CANCELADO, las inscripciones
 * confirmadas REINTEGRADA, las que no terminaron de pagar CANCELADA, y cada uno recupera todo lo que pagó.
 */
@Service
@RequiredArgsConstructor
public class CancelacionTorneoService {

    private static final Set<EstadoTorneo> CANCELABLES = Set.of(EstadoTorneo.INSCRIPCION_ABIERTA,
            EstadoTorneo.INSCRIPCION_CERRADA, EstadoTorneo.EN_CURSO);
    private static final int MOTIVO_MAXIMO = 500;

    private final TorneosPropios torneosPropios;
    private final TorneoRepository torneoRepository;
    private final InscripcionRepository inscripcionRepository;
    private final CancelacionRepository cancelacionRepository;
    private final CustodiaService custodiaService;
    private final Reintegrador reintegrador;
    private final AuditoriaService auditoriaService;

    @Transactional
    public CancelacionRealizada cancelar(Long organizadorId, Long id, SolicitudCancelacion solicitud) {
        String motivo = motivo(solicitud);
        Torneo torneo = torneosPropios.buscarBloqueado(organizadorId, id);
        if (!CANCELABLES.contains(torneo.getEstado())) {
            throw new ConflictoException(CodigoError.TORNEO_NO_CANCELABLE, "El torneo " + id + " está "
                    + torneo.getEstado() + ": solo se cancela con la inscripción abierta o cerrada, o en curso")
                    .con("estado", torneo.getEstado());
        }
        BigDecimal pozo = custodiaService.recaudadoDelTorneo(id);
        String nombre = torneo.getNombre();

        torneo.setEstado(EstadoTorneo.CANCELADO);
        torneo.setMotivoCancelacion(motivo);
        resolverInscripciones(id);
        torneoRepository.saveAndFlush(torneo);

        Cancelacion cancelacion = new Cancelacion();
        cancelacion.setTorneo(torneo);
        cancelacion.setEstado(EstadoCancelacion.AUTOMATICA);
        cancelacion = cancelacionRepository.saveAndFlush(cancelacion);
        Long cancelacionId = cancelacion.getId();

        auditar(organizadorId, id, cancelacionId, EstadoCancelacion.AUTOMATICA, motivo);
        BigDecimal reintegrado = reintegrador.reintegrarTorneo(id, nombre);
        return new CancelacionRealizada(cancelacionId, id, EstadoCancelacion.AUTOMATICA.name(), BigDecimal.ZERO, pozo,
                reintegrado);
    }

    private static String motivo(SolicitudCancelacion solicitud) {
        String motivo = solicitud.motivo() == null ? "" : solicitud.motivo().strip();
        if (motivo.isEmpty()) {
            throw invalido("motivo", ErrorCampo.Codigo.REQUERIDO, "Indicá el motivo de la cancelación");
        }
        if (motivo.length() > MOTIVO_MAXIMO) {
            throw invalido("motivo", ErrorCampo.Codigo.LONGITUD, "Hasta " + MOTIVO_MAXIMO + " caracteres");
        }
        return motivo;
    }

    static ApiException invalido(String campo, ErrorCampo.Codigo codigo, String mensaje) {
        return new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                .con("errores", List.of(new ErrorCampo(campo, codigo, mensaje)));
    }

    private void auditar(Long organizadorId, Long torneoId, Long cancelacionId, EstadoCancelacion estado,
                         String motivo) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("cancelacionId", cancelacionId);
        detalle.put("estado", estado);
        detalle.put("motivo", motivo);
        auditoriaService.registrar(organizadorId, AccionAuditoria.CANCELACION, "TORNEO", torneoId.toString(), detalle);
    }

    private void resolverInscripciones(Long torneoId) {
        for (Inscripcion inscripcion : inscripcionRepository.findByTorneoIdAndEstadoInOrderByIdAsc(torneoId,
                Set.of(EstadoInscripcion.BORRADOR, EstadoInscripcion.PENDIENTE_PAGO, EstadoInscripcion.CONFIRMADA))) {
            inscripcion.setEstado(inscripcion.getEstado() == EstadoInscripcion.CONFIRMADA
                    ? EstadoInscripcion.REINTEGRADA : EstadoInscripcion.CANCELADA);
        }
    }
}
