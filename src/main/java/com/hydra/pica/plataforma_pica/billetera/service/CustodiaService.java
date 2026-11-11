package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.CustodiaResumen;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.service.ReclamoRifaService;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * La custodia de los fondos de cada torneo y cada rifa (HU-603). Entra el 100 % de cada pago con saldo (valor y gastos)
 * y sale por una sola puerta, {@link #liberarTorneo}/{@link #liberarRifa}, que usa la liquidación (HU-606): antes de
 * que el evento termine falla con FONDOS_EN_CUSTODIA para cualquiera, también para el organizador.
 */
@Service
@RequiredArgsConstructor
public class CustodiaService {

    private static final String ENTIDAD = "CUSTODIA";

    private final CustodiaRepository custodiaRepository;
    private final MovimientoRepository movimientoRepository;
    private final TorneoRepository torneoRepository;
    private final RifaRepository rifaRepository;
    private final AuditoriaService auditoriaService;

    // --- ingreso ------------------------------------------------------------------------------------

    /**
     * La custodia a la que entra un pago: la de la rifa si es una compra de números (aunque la rifa tenga torneo), si
     * no la del torneo. La crea con el primer pago y la deja bloqueada para que no se libere mientras se cobra.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Custodia paraIngresar(SolicitudCobro solicitud) {
        Custodia custodia;
        if (solicitud.rifaId() != null) {
            custodiaRepository.crearDeLaRifa(solicitud.rifaId());
            custodia = custodiaRepository.findParaIngresarPorRifaId(solicitud.rifaId()).orElseThrow();
        } else if (solicitud.torneoId() != null) {
            custodiaRepository.crearDelTorneo(solicitud.torneoId());
            custodia = custodiaRepository.findParaIngresarPorTorneoId(solicitud.torneoId()).orElseThrow();
        } else {
            throw new IllegalArgumentException("Un pago con saldo entra a la custodia de un torneo o de una rifa");
        }
        if (custodia.getEstado() != EstadoCustodia.RETENIDA) {
            // quien cobra ya validó que el evento sigue abierto: esto es un error suyo, no del usuario
            throw new IllegalStateException("La custodia " + custodia.getId() + " ya se liberó");
        }
        return custodia;
    }

    /** Deja en la auditoría el ingreso de un pago a la custodia. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarIngreso(Movimiento pago, Long usuarioId) {
        Custodia custodia = pago.getCustodia();
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("movimientoId", pago.getId());
        detalle.put("tipo", pago.getTipo());
        detalle.put("monto", pago.getMonto());
        detalle.put("evento", custodia.getRifa() != null ? "RIFA" : "TORNEO");
        detalle.put("eventoId", custodia.getRifa() != null ? custodia.getRifa().getId() : custodia.getTorneo().getId());
        auditoriaService.registrar(usuarioId, AccionAuditoria.PAGO, ENTIDAD, custodia.getId().toString(), detalle);
    }

    // --- la puerta de salida ------------------------------------------------------------------------

    /**
     * Saca los fondos de la custodia del torneo: LIBERADA si finalizó (para la liquidación) o REINTEGRADA si se canceló.
     * Lo pagado con saldo sale del retenido de cada pagador. Un torneo sin terminar: 409 FONDOS_EN_CUSTODIA. Una
     * custodia ya liberada: 409 CUSTODIA_LIBERADA, así un reintento no paga dos veces.
     */
    @Transactional
    public LiberacionCustodia liberarTorneo(Long torneoId) {
        Torneo torneo = torneoRepository.findByIdAndEliminadoEnIsNull(torneoId).orElseThrow(() -> torneoNoEncontrado(torneoId));
        EstadoCustodia destino = destino(torneo);
        return liberar(custodiaRepository.findParaLiberarPorTorneoId(torneoId), destino);
    }

    /** Igual que {@link #liberarTorneo}, para una rifa: termina al vencer el plazo de reclamo o si se cancela. */
    @Transactional
    public LiberacionCustodia liberarRifa(Long rifaId) {
        Rifa rifa = rifaRepository.findByIdAndEliminadoEnIsNull(rifaId).orElseThrow(() -> rifaNoEncontrada(rifaId));
        EstadoCustodia destino = destino(rifa, Instant.now());
        return liberar(custodiaRepository.findParaLiberarPorRifaId(rifaId), destino);
    }

    private LiberacionCustodia liberar(Optional<Custodia> encontrada, EstadoCustodia destino) {
        if (encontrada.isEmpty()) {
            return new LiberacionCustodia(null, destino, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Custodia custodia = encontrada.get();
        if (custodia.getEstado() != EstadoCustodia.RETENIDA) {
            throw new ConflictoException(CodigoError.CUSTODIA_LIBERADA,
                    "La custodia " + custodia.getId() + " ya está " + custodia.getEstado())
                    .con("estado", custodia.getEstado());
        }
        Totales totales = totales(custodia.getId());
        Long custodiaId = custodia.getId();
        custodia.setEstado(destino);
        custodia.setLiberadaEn(Instant.now());
        custodiaRepository.saveAndFlush(custodia);
        custodiaRepository.descontarDelRetenido(custodiaId);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("estado", destino);
        detalle.put("total", totales.total());
        detalle.put("gastosAdministrativos", totales.gastos());
        auditoriaService.registrar(null, AccionAuditoria.PAGO, ENTIDAD, custodiaId.toString(), detalle);
        return new LiberacionCustodia(custodiaId, destino, totales.total(), totales.gastos());
    }

    /** El pozo de un torneo: lo recaudado en inscripciones, sin gastos administrativos (HU-605). Sin pagos, 0. */
    @Transactional(readOnly = true)
    public BigDecimal recaudadoDelTorneo(Long torneoId) {
        return custodiaRepository.findByTorneoId(torneoId)
                .map(c -> LiquidacionService.retenido(movimientoRepository, c.getId()).recaudado())
                .orElse(BigDecimal.ZERO);
    }

    // --- el organizador -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CustodiaResumen resumenDelTorneo(Long usuarioId, Long torneoId) {
        Torneo torneo = torneoDelOrganizador(usuarioId, torneoId);
        return resumen(custodiaRepository.findByTorneoId(torneoId), terminado(torneo));
    }

    @Transactional(readOnly = true)
    public CustodiaResumen resumenDeLaRifa(Long usuarioId, Long rifaId) {
        Rifa rifa = rifaDelOrganizador(usuarioId, rifaId);
        return resumen(custodiaRepository.findByRifaId(rifaId), terminada(rifa, Instant.now()));
    }

    /**
     * El organizador quiere sacar plata de la custodia de su torneo. Pasa por la misma regla que la puerta de salida:
     * antes del cierre, FONDOS_EN_CUSTODIA. Después tampoco la saca él: la libera la liquidación (HU-606) y lo suyo va a
     * su billetera, desde donde se retira.
     */
    @Transactional(readOnly = true)
    public void retirarDelTorneo(Long usuarioId, Long torneoId) {
        Torneo torneo = torneoDelOrganizador(usuarioId, torneoId);
        destino(torneo);
        throw sinRetiroDirecto(custodiaRepository.findByTorneoId(torneoId), "del torneo");
    }

    @Transactional(readOnly = true)
    public void retirarDeLaRifa(Long usuarioId, Long rifaId) {
        Rifa rifa = rifaDelOrganizador(usuarioId, rifaId);
        destino(rifa, Instant.now());
        throw sinRetiroDirecto(custodiaRepository.findByRifaId(rifaId), "de la rifa");
    }

    private static ConflictoException sinRetiroDirecto(Optional<Custodia> custodia, String deQue) {
        if (custodia.isPresent() && custodia.get().getEstado() != EstadoCustodia.RETENIDA) {
            return (ConflictoException) new ConflictoException(CodigoError.CUSTODIA_LIBERADA,
                    "La custodia " + deQue + " ya se liquidó: lo que te corresponde está en tu billetera")
                    .con("estado", custodia.get().getEstado());
        }
        return new FondosEnCustodiaException("Los fondos " + deQue + " siguen en custodia hasta la liquidación");
    }

    // --- reglas -------------------------------------------------------------------------------------

    private static EstadoCustodia destino(Torneo torneo) {
        if (torneo.getEstado() == EstadoTorneo.FINALIZADO) {
            return EstadoCustodia.LIBERADA;
        }
        if (torneo.getEstado() == EstadoTorneo.CANCELADO) {
            return EstadoCustodia.REINTEGRADA;
        }
        throw new FondosEnCustodiaException(LiquidacionService.SIN_CERRAR);
    }

    private static EstadoCustodia destino(Rifa rifa, Instant ahora) {
        if (rifa.getEstado() == EstadoRifa.CANCELADA) {
            return EstadoCustodia.REINTEGRADA;
        }
        if (terminada(rifa, ahora)) {
            return EstadoCustodia.LIBERADA;
        }
        throw new FondosEnCustodiaException("Los fondos de la rifa " + rifa.getId()
                + " están en custodia y no se pueden mover hasta que venza el plazo de reclamo del sorteo");
    }

    private static boolean terminado(Torneo torneo) {
        return torneo.getEstado() == EstadoTorneo.FINALIZADO || torneo.getEstado() == EstadoTorneo.CANCELADO;
    }

    /** Liquidada, cancelada o sorteada con el plazo de reclamo vencido; con un reclamo abierto está EN_REVISION. */
    private static boolean terminada(Rifa rifa, Instant ahora) {
        if (rifa.getEstado() == EstadoRifa.LIQUIDADA || rifa.getEstado() == EstadoRifa.CANCELADA) {
            return true;
        }
        Instant plazo = ReclamoRifaService.plazoReclamo(rifa);
        return rifa.getEstado() == EstadoRifa.SORTEADA && plazo != null && ahora.isAfter(plazo);
    }

    private CustodiaResumen resumen(Optional<Custodia> custodia, boolean terminado) {
        if (custodia.isEmpty()) {
            return new CustodiaResumen(EstadoCustodia.RETENIDA, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                    "ARS", terminado, null);
        }
        Totales totales = totales(custodia.get().getId());
        return new CustodiaResumen(custodia.get().getEstado(), totales.total(), totales.total().subtract(totales.gastos()),
                totales.gastos(), totales.pagos(), "ARS", terminado, custodia.get().getLiberadaEn());
    }

    private Totales totales(Long custodiaId) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal gastos = BigDecimal.ZERO;
        long pagos = 0;
        List<Object[]> filas = movimientoRepository.totalesDeLaCustodia(custodiaId);
        for (Object[] fila : filas) {
            TipoMovimiento tipo = (TipoMovimiento) fila[0];
            BigDecimal suma = (BigDecimal) fila[1];
            total = total.add(suma);
            if (tipo == TipoMovimiento.COMISION) {
                gastos = gastos.add(suma);
            } else {
                pagos += (Long) fila[2];
            }
        }
        return new Totales(total, gastos, pagos);
    }

    private record Totales(BigDecimal total, BigDecimal gastos, long pagos) {
    }

    // el de otro organizador no existe para quien pregunta, como los borradores
    private Torneo torneoDelOrganizador(Long usuarioId, Long torneoId) {
        return torneoRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(torneoId, usuarioId)
                .orElseThrow(() -> torneoNoEncontrado(torneoId));
    }

    private Rifa rifaDelOrganizador(Long usuarioId, Long rifaId) {
        return rifaRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(rifaId, usuarioId)
                .orElseThrow(() -> rifaNoEncontrada(rifaId));
    }

    private static NoEncontradoException torneoNoEncontrado(Long torneoId) {
        return new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + torneoId);
    }

    private static NoEncontradoException rifaNoEncontrada(Long rifaId) {
        return new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + rifaId);
    }
}
