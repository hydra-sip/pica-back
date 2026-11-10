package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * El reintegro de un torneo cancelado (HU-605): a cada billetera que pagó con saldo le devuelve lo que pagó, inscripción
 * y gastos administrativos, del retenido al disponible, y libera la custodia como REINTEGRADA. No es una ganancia: no
 * suma a la ganancia acumulada. Antes de pagar controla que lo devuelto sume lo retenido; si no da, no paga nada.
 */
@Component
@RequiredArgsConstructor
public class Reintegrador {

    private final CustodiaRepository custodiaRepository;
    private final CustodiaService custodiaService;
    private final MovimientoRepository movimientoRepository;
    private final BilleteraRepository billeteraRepository;
    private final TorneoRepository torneoRepository;
    private final AuditoriaService auditoriaService;

    /**
     * Reintegra lo retenido de un torneo ya CANCELADO.
     *
     * @return lo que devolvió
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal reintegrarTorneo(Long torneoId, String nombre) {
        Optional<Custodia> custodia = custodiaRepository.findParaLiberarPorTorneoId(torneoId);
        if (custodia.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Long custodiaId = custodia.get().getId();
        BigDecimal retenido = LiquidacionService.retenido(movimientoRepository, custodiaId).total();
        List<Object[]> pagos = movimientoRepository.pagosConSaldoPorBilletera(custodiaId);
        BigDecimal devuelto = pagos.stream().map(p -> (BigDecimal) p[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (devuelto.compareTo(retenido) != 0) {
            throw new IllegalStateException("El reintegro del torneo " + torneoId + " devuelve " + devuelto
                    + " y la custodia retiene " + retenido + ": no se pagó nada");
        }

        custodiaService.liberarTorneo(torneoId); // saca lo pagado del retenido y deja la sesión vacía
        for (Object[] pago : pagos) {
            devolver(torneoId, nombre, (Long) pago[0], (BigDecimal) pago[1]);
        }
        return devuelto;
    }

    private void devolver(Long torneoId, String nombre, Long billeteraId, BigDecimal monto) {
        Billetera billetera = billeteraRepository.findConBloqueoPorId(billeteraId).orElseThrow();
        billetera.setSaldoDisponible(billetera.getSaldoDisponible().add(monto));
        billeteraRepository.save(billetera);

        Movimiento movimiento = new Movimiento();
        movimiento.setBilletera(billetera);
        movimiento.setTorneo(torneoRepository.getReferenceById(torneoId));
        movimiento.setTipo(TipoMovimiento.REINTEGRO);
        movimiento.setMonto(monto);
        movimiento.setMoneda(billetera.getMoneda());
        movimiento.setSaldoResultante(billetera.getSaldoDisponible());
        movimiento.setEstado(EstadoMovimiento.CONFIRMADO);
        movimiento.setReferenciaExterna("reint-t" + torneoId + "-b" + billeteraId);
        String descripcion = "Reintegro por la cancelación del torneo " + nombre;
        movimiento.setDescripcion(descripcion.length() > 255 ? descripcion.substring(0, 255) : descripcion);
        Movimiento guardado = movimientoRepository.save(movimiento);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("movimientoId", guardado.getId());
        detalle.put("billeteraId", billeteraId);
        detalle.put("monto", monto);
        auditoriaService.registrar(null, AccionAuditoria.REINTEGRO, "TORNEO", torneoId.toString(), detalle);
    }
}
