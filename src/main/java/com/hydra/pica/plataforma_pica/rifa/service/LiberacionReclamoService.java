package com.hydra.pica.plataforma_pica.rifa.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion;
import com.hydra.pica.plataforma_pica.billetera.service.FondosEnCustodiaException;
import com.hydra.pica.plataforma_pica.billetera.service.LiquidacionRifaService;
import com.hydra.pica.plataforma_pica.billetera.service.LiquidacionRifaService.LiquidacionRifa;
import com.hydra.pica.plataforma_pica.billetera.service.Reintegrador;
import com.hydra.pica.plataforma_pica.billetera.service.Reintegrador.ReintegroRifa;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.EntidadAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.DecisionReclamo;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoAdminDetalle;
import com.hydra.pica.plataforma_pica.rifa.event.FondosReclamoLiberados;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ejecuta la liberación de los fondos retenidos tras la resolución definitiva de un reclamo de rifa (HU-707, PICA-22,
 * PICA-341).
 * <ul>
 * <li>{@code RECHAZADO} ({@code SIN_FRAUDE}): delega en la liquidación normal de la rifa (T606-2) respetando sus
 * reglas vigentes.</li>
 * <li>{@code ACEPTADO} ({@code FRAUDE_CONFIRMADO}): cancela la rifa, marca los números vendidos como reintegrados y
 * devuelve el 100 % pagado (números + comisión) como {@code REINTEGRO} a las billeteras de los compradores.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LiberacionReclamoService {

    private final ReclamoRifaRepository reclamoRifaRepository;
    private final RifaRepository rifaRepository;
    private final UsuarioRepository usuarioRepository;
    private final NumeroRepository numeroRepository;
    private final LiquidacionRifaService liquidacionRifaService;
    private final Reintegrador reintegrador;
    private final AuditoriaService auditoriaService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Libera los fondos congelados de un reclamo resuelto, registrando en auditoría la rifa, el monto, el destino y el
     * usuario de soporte que ejecutó la liberación.
     */
    @Transactional
    public ReclamoAdminDetalle liberarFondos(Long ejecutorId, Long reclamoId) {
        Usuario ejecutor = usuarioRepository.findById(ejecutorId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO,
                        "No existe el usuario " + ejecutorId));

        Long rifaId = reclamoRifaRepository.findRifaIdById(reclamoId)
                .orElseThrow(() -> noEncontrado(reclamoId));
        Rifa rifa = rifaRepository.buscarBloqueado(rifaId)
                .orElseThrow(() -> noEncontrado(reclamoId));
        ReclamoRifa reclamo = reclamoRifaRepository.buscarBloqueado(reclamoId)
                .orElseThrow(() -> noEncontrado(reclamoId));

        validarPrecondiciones(rifaId, reclamoId, rifa, reclamo);

        if (reclamo.getEstado() == EstadoReclamo.RECHAZADO) {
            return liberarSinFraude(ejecutor, rifa, reclamoId);
        }
        return liberarConFraude(ejecutor, rifa, reclamoId, reclamo.getUsuario().getId());
    }

    private void validarPrecondiciones(Long rifaId, Long reclamoId, Rifa rifa, ReclamoRifa reclamo) {
        if (reclamo.getFondosLiberadosEn() != null) {
            throw new ConflictoException(CodigoError.CUSTODIA_LIBERADA,
                    "Los fondos del reclamo " + reclamoId + " ya fueron liberados")
                    .con("estado", estadoCustodiaDe(rifa));
        }
        if (reclamo.getEstado() == EstadoReclamo.PENDIENTE) {
            throw new ConflictoException(CodigoError.RECLAMO_NO_RESUELTO,
                    "El reclamo " + reclamoId + " debe estar resuelto antes de liberar los fondos")
                    .con("estado", reclamo.getEstado());
        }
        if (rifa.getEstado() == EstadoRifa.LIQUIDADA || rifa.getEstado() == EstadoRifa.CANCELADA) {
            throw new ConflictoException(CodigoError.CUSTODIA_LIBERADA,
                    "La custodia de la rifa " + rifaId + " ya fue liberada")
                    .con("estado", estadoCustodiaDe(rifa));
        }
        if (rifa.getEstado() == EstadoRifa.EN_REVISION
                || reclamoRifaRepository.existsByRifaIdAndEstado(rifaId, EstadoReclamo.PENDIENTE)) {
            throw new FondosEnCustodiaException(
                    "La rifa " + rifaId + " todavía tiene reclamos pendientes de resolución");
        }
    }

    private static EstadoCustodia estadoCustodiaDe(Rifa rifa) {
        return rifa.getEstado() == EstadoRifa.CANCELADA ? EstadoCustodia.REINTEGRADA : EstadoCustodia.LIBERADA;
    }

    private ReclamoAdminDetalle liberarSinFraude(Usuario ejecutor, Rifa rifa, Long reclamoId) {
        // Se captura antes porque CustodiaService.liberarRifa limpia el EntityManager.
        RifaResumen resumen = RifaResumen.de(rifa);

        LiquidacionRifa liquidacion = liquidacionRifaService.liquidarRifa(resumen.id());
        BigDecimal sumaAcreditaciones = liquidacion.acreditaciones().stream()
                .map(Acreditacion::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal montoAcreditado = liquidacion.aportePendiente()
                ? sumaAcreditaciones
                : liquidacion.retenido();

        marcarLiberado(reclamoId, ejecutor.getUsername(), montoAcreditado);

        List<Map<String, Object>> destinatarios = new ArrayList<>(liquidacion.acreditaciones().stream()
                .map(a -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rol", a.rol().name());
                    item.put("usuarioId", a.usuarioId());
                    item.put("monto", a.monto());
                    item.put("referencia", a.referencia());
                    return item;
                })
                .toList());
        if (!liquidacion.aportePendiente() && sumaAcreditaciones.compareTo(montoAcreditado) < 0) {
            Map<String, Object> aporte = new LinkedHashMap<>();
            aporte.put("rol", "APORTE_TORNEO");
            aporte.put("torneoId", resumen.torneoId());
            aporte.put("monto", liquidacion.aporteAlTorneo());
            destinatarios.add(aporte);
        }

        auditarLiquidacion(ejecutor, reclamoId, resumen, montoAcreditado, destinatarios, liquidacion);

        Map<Long, BigDecimal> acreditados = new LinkedHashMap<>();
        liquidacion.acreditaciones().stream()
                .filter(a -> a.usuarioId() != null)
                .forEach(a -> acreditados.merge(a.usuarioId(), a.monto(), BigDecimal::add));
        eventPublisher.publishEvent(new FondosReclamoLiberados(reclamoId, resumen.id(), resumen.torneoId(),
                EstadoRifa.LIQUIDADA, acreditados));
        return detalleActualizado(reclamoId);
    }

    private ReclamoAdminDetalle liberarConFraude(Usuario ejecutor, Rifa rifa, Long reclamoId, Long reclamanteId) {
        RifaResumen resumen = RifaResumen.de(rifa);

        rifa.setEstado(EstadoRifa.CANCELADA);
        rifaRepository.saveAndFlush(rifa);
        numeroRepository.reintegrarVendidos(resumen.id());

        ReintegroRifa reintegro = reintegrador.reintegrarRifa(resumen.id(), resumen.titulo());

        marcarLiberado(reclamoId, ejecutor.getUsername(), reintegro.totalDevuelto());

        List<Map<String, Object>> destinatarios = reintegro.destinatarios().stream()
                .map(d -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("usuarioId", d.usuarioId());
                    item.put("billeteraId", d.billeteraId());
                    item.put("monto", d.monto());
                    return item;
                })
                .toList();

        auditarReintegro(ejecutor, reclamoId, resumen, reintegro.totalDevuelto(), destinatarios, reclamanteId);

        Map<Long, BigDecimal> acreditados = new LinkedHashMap<>();
        reintegro.destinatarios().forEach(d -> acreditados.merge(d.usuarioId(), d.monto(), BigDecimal::add));
        eventPublisher.publishEvent(new FondosReclamoLiberados(reclamoId, resumen.id(), resumen.torneoId(),
                EstadoRifa.CANCELADA, acreditados));
        return detalleActualizado(reclamoId);
    }

    private void marcarLiberado(Long reclamoId, String ejecutadoPor, BigDecimal monto) {
        // Re-lee con bloqueo porque CustodiaService.liberarRifa limpia el EntityManager.
        ReclamoRifa reclamo = reclamoRifaRepository.buscarBloqueado(reclamoId)
                .orElseThrow(() -> noEncontrado(reclamoId));
        reclamo.setFondosLiberadosEn(Instant.now());
        reclamo.setFondosLiberadosPor(ejecutadoPor);
        reclamo.setFondosLiberadosMonto(monto);
        reclamoRifaRepository.saveAndFlush(reclamo);
    }

    private void auditarLiquidacion(
            Usuario ejecutor,
            Long reclamoId,
            RifaResumen rifa,
            BigDecimal monto,
            List<Map<String, Object>> destinatarios,
            LiquidacionRifa liquidacion) {
        Map<String, Object> detalle = detalleBaseAuditoria(reclamoId, rifa, DecisionReclamo.SIN_FRAUDE,
                EstadoRifa.LIQUIDADA, monto, "LIQUIDACION_GANADORES", destinatarios, ejecutor.getUsername());
        if (liquidacion.aportePendiente()) {
            detalle.put("aportePendiente", true);
            detalle.put("aporteAlTorneo", liquidacion.aporteAlTorneo());
        }
        auditoriaService.registrar(ejecutor.getId(), AccionAuditoria.LIQUIDACION, EntidadAuditoria.RECLAMO_RIFA,
                reclamoId.toString(), detalle);
    }

    private void auditarReintegro(
            Usuario ejecutor,
            Long reclamoId,
            RifaResumen rifa,
            BigDecimal monto,
            List<Map<String, Object>> destinatarios,
            Long reclamanteId) {
        Map<String, Object> detalle = detalleBaseAuditoria(reclamoId, rifa, DecisionReclamo.FRAUDE_CONFIRMADO,
                EstadoRifa.CANCELADA, monto, "REINTEGRO_COMPRADORES", destinatarios, ejecutor.getUsername());
        if (reclamanteId != null) {
            detalle.put("reclamanteId", reclamanteId);
        }
        auditoriaService.registrar(ejecutor.getId(), AccionAuditoria.REINTEGRO, EntidadAuditoria.RECLAMO_RIFA,
                reclamoId.toString(), detalle);
    }

    private static Map<String, Object> detalleBaseAuditoria(
            Long reclamoId,
            RifaResumen rifa,
            DecisionReclamo resolucion,
            EstadoRifa rifaEstadoFinal,
            BigDecimal monto,
            String destino,
            List<Map<String, Object>> destinatarios,
            String ejecutadoPor) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("tipo", "LIBERACION_RECLAMO");
        detalle.put("reclamoId", reclamoId);
        detalle.put("rifaId", rifa.id());
        detalle.put("rifaTitulo", rifa.titulo());
        if (rifa.torneoId() != null) {
            detalle.put("torneoId", rifa.torneoId());
        }
        detalle.put("resolucion", resolucion.name());
        detalle.put("rifaEstadoFinal", rifaEstadoFinal.name());
        detalle.put("monto", monto);
        detalle.put("destino", destino);
        detalle.put("destinatarios", destinatarios);
        detalle.put("ejecutadoPor", ejecutadoPor);
        return detalle;
    }

    private record RifaResumen(Long id, String titulo, Long torneoId) {
        static RifaResumen de(Rifa rifa) {
            Long torneoId = rifa.getTorneo() != null ? rifa.getTorneo().getId() : null;
            return new RifaResumen(rifa.getId(), rifa.getTitulo(), torneoId);
        }
    }

    private ReclamoAdminDetalle detalleActualizado(Long reclamoId) {
        return reclamoRifaRepository.findDetalleById(reclamoId)
                .map(ReclamoAdminDetalle::de)
                .orElseThrow(() -> noEncontrado(reclamoId));
    }

    private static NoEncontradoException noEncontrado(Long reclamoId) {
        return new NoEncontradoException(CodigoError.RECLAMO_NO_ENCONTRADO,
                "No existe el reclamo " + reclamoId);
    }
}
