package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
import com.hydra.pica.plataforma_pica.billetera.service.Acreditador.Evento;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * El reintegro de un torneo cancelado (HU-605): a cada billetera que pagó con saldo le devuelve lo que pagó, del retenido
 * al disponible, y libera la custodia como REINTEGRADA. No es una ganancia: no suma a la ganancia acumulada.
 * <p>
 * Con una retención aprobada por soporte, a cada billetera se le descuenta su parte de los gastos, proporcional a lo que
 * pagó de inscripción (los gastos administrativos vuelven enteros), y el organizador cobra los gastos como una
 * liquidación. Antes de pagar controla que lo devuelto más lo retenido sume lo que hay en custodia; si no da, no paga
 * nada.
 */
@Component
@RequiredArgsConstructor
public class Reintegrador {

    private static final BigDecimal CENTAVO = new BigDecimal("0.01");

    private final CustodiaRepository custodiaRepository;
    private final CustodiaService custodiaService;
    private final MovimientoRepository movimientoRepository;
    private final BilleteraRepository billeteraRepository;
    private final TorneoRepository torneoRepository;
    private final AuditoriaService auditoriaService;
    private final Acreditador acreditador;

    /** Los gastos que soporte aprobó y el organizador que los cobra. */
    public record Retencion(Long organizadorId, BigDecimal gastos) {
    }

    /** Reintegra todo lo retenido de un torneo ya CANCELADO. @return lo que devolvió */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal reintegrarTorneo(Long torneoId, String nombre) {
        return reintegrarTorneo(torneoId, nombre, null);
    }

    /**
     * Reintegra lo retenido de un torneo ya CANCELADO, descontando la retención si la hay.
     *
     * @return lo que devolvió a las billeteras de quienes pagaron
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal reintegrarTorneo(Long torneoId, String nombre, Retencion retencion) {
        BigDecimal gastos = retencion == null ? BigDecimal.ZERO : retencion.gastos();
        Optional<Custodia> custodia = custodiaRepository.findParaLiberarPorTorneoId(torneoId);
        if (custodia.isEmpty()) {
            if (gastos.signum() > 0) {
                throw new IllegalStateException("El torneo " + torneoId + " no tiene custodia de donde retener gastos");
            }
            return BigDecimal.ZERO;
        }
        Long custodiaId = custodia.get().getId();
        BigDecimal retenido = LiquidacionService.retenido(movimientoRepository, custodiaId).total();
        List<Pago> pagos = pagos(custodiaId);
        repartir(pagos, gastos);
        BigDecimal devuelto = pagos.stream().map(Pago::devuelto).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (devuelto.add(gastos).compareTo(retenido) != 0) {
            throw new IllegalStateException("El reintegro del torneo " + torneoId + " devuelve " + devuelto
                    + " y retiene " + gastos + ", pero la custodia retiene " + retenido + ": no se pagó nada");
        }

        custodiaService.liberarTorneo(torneoId); // saca lo pagado del retenido y deja la sesión vacía
        for (Pago pago : pagos) {
            devolver(torneoId, nombre, pago);
        }
        if (gastos.signum() > 0) {
            acreditador.acreditar(Evento.torneo(torneoId), List.of(new Acreditacion(Acreditacion.Rol.ORGANIZADOR,
                    retencion.organizadorId(), gastos, null, null, "canc-t" + torneoId + "-organizador",
                    "Gastos aprobados de la cancelación del torneo " + nombre)));
        }
        return devuelto;
    }

    /** Lo que pagó cada billetera, en orden de billetera: el total y la parte que fue inscripción. */
    private List<Pago> pagos(Long custodiaId) {
        Map<Long, Pago> porBilletera = new LinkedHashMap<>();
        for (Object[] fila : movimientoRepository.pagosConSaldoPorBilletera(custodiaId)) {
            Pago pago = porBilletera.computeIfAbsent((Long) fila[0], Pago::new);
            BigDecimal suma = (BigDecimal) fila[2];
            pago.total = pago.total.add(suma);
            if (fila[1] == TipoMovimiento.INSCRIPCION) {
                pago.inscripcion = pago.inscripcion.add(suma);
            }
        }
        return new ArrayList<>(porBilletera.values());
    }

    /**
     * La parte de los gastos de cada uno: proporcional a su inscripción, hacia abajo al centavo; los centavos que sobran
     * van de a uno a las primeras billeteras. Así lo descontado suma exactamente los gastos.
     */
    private static void repartir(List<Pago> pagos, BigDecimal gastos) {
        if (gastos.signum() == 0) {
            return;
        }
        BigDecimal pozo = pagos.stream().map(p -> p.inscripcion).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal repartido = BigDecimal.ZERO;
        for (Pago pago : pagos) {
            pago.descuento = gastos.multiply(pago.inscripcion).divide(pozo, 2, RoundingMode.DOWN);
            repartido = repartido.add(pago.descuento);
        }
        BigDecimal sobra = gastos.subtract(repartido);
        for (int i = 0; sobra.signum() > 0; i = (i + 1) % pagos.size()) {
            Pago pago = pagos.get(i);
            if (pago.inscripcion.signum() > 0) {
                pago.descuento = pago.descuento.add(CENTAVO);
                sobra = sobra.subtract(CENTAVO);
            }
        }
    }

    private void devolver(Long torneoId, String nombre, Pago pago) {
        Billetera billetera = billeteraRepository.findConBloqueoPorId(pago.billeteraId).orElseThrow();
        BigDecimal monto = pago.devuelto();
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
        movimiento.setReferenciaExterna("reint-t" + torneoId + "-b" + pago.billeteraId);
        String descripcion = "Reintegro por la cancelación del torneo " + nombre;
        if (pago.descuento.signum() > 0) {
            descripcion += ": se retuvieron " + pesos(pago.descuento) + " de gastos";
        }
        movimiento.setDescripcion(descripcion.length() > 255 ? descripcion.substring(0, 255) : descripcion);
        Movimiento guardado = movimientoRepository.save(movimiento);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("movimientoId", guardado.getId());
        detalle.put("billeteraId", pago.billeteraId);
        detalle.put("monto", monto);
        detalle.put("retenido", pago.descuento);
        auditoriaService.registrar(null, AccionAuditoria.REINTEGRO, "TORNEO", torneoId.toString(), detalle);
    }

    /** $150,17: como se lee en Mi billetera y en los mails. */
    public static String pesos(BigDecimal monto) {
        return "$" + monto.setScale(2, RoundingMode.UNNECESSARY).toPlainString().replace('.', ',');
    }

    private static final class Pago {
        private final Long billeteraId;
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal inscripcion = BigDecimal.ZERO;
        private BigDecimal descuento = BigDecimal.ZERO;

        private Pago(Long billeteraId) {
            this.billeteraId = billeteraId;
        }

        private BigDecimal devuelto() {
            return total.subtract(descuento);
        }
    }
}
