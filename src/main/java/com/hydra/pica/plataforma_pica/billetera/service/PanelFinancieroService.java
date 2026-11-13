package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.IngresoInscripcion;
import com.hydra.pica.plataforma_pica.billetera.dto.PanelFinanciero;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;
import com.hydra.pica.plataforma_pica.billetera.service.LiquidacionService.Retenido;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El panel financiero de un torneo y su liquidación final para descargar (HU-608, PICA-342). Lo ven el organizador y
 * quien tiene TORNEO_ADMINISTRAR; para el resto, 404 como si no existiera. Las cuentas son las de la liquidación
 * (HU-606) sobre lo que hay en custodia, así el panel da lo mismo que después se paga.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PanelFinancieroService {

    static final String PERMISO_ADMINISTRAR = "TORNEO_ADMINISTRAR";
    private static final String MONEDA = "ARS";

    private final TorneoRepository torneoRepository;
    private final CustodiaRepository custodiaRepository;
    private final MovimientoRepository movimientoRepository;
    private final GastoRepository gastoRepository;
    private final RifaRepository rifaRepository;
    private final RepartoLiquidacionService repartoLiquidacionService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * En curso, lo estimado. Liquidado, lo comprometido en premios es lo que se acreditó en premios, y lo que fue al
     * organizador por encima de sus gastos es el pozo que nadie ganó.
     */
    public PanelFinanciero delTorneo(Long usuarioId, Long torneoId) {
        Torneo torneo = visible(usuarioId, torneoId);
        Optional<Custodia> custodia = custodiaRepository.findByTorneoId(torneoId);
        Cuentas cuentas = cuentas(torneoId, custodia);
        BigDecimal aporteRifa = aporteDeLaRifa(torneoId);
        BigDecimal premios = cuentas.retenido().recaudado().subtract(cuentas.reintegro()).add(aporteRifa);
        BigDecimal alOrganizador = BigDecimal.ZERO;

        Instant liquidadoEn = custodia.filter(c -> c.getEstado() == EstadoCustodia.LIBERADA)
                .map(Custodia::getLiberadaEn)
                .orElse(null);
        if (liquidadoEn != null) {
            RepartoLiquidacion reparto = repartoLiquidacionService.delTorneo(torneo);
            premios = suma(reparto, Rol.PREMIO);
            alOrganizador = suma(reparto, Rol.ORGANIZADOR).subtract(cuentas.reintegro()).max(BigDecimal.ZERO);
        }
        return new PanelFinanciero(torneo.getId(), torneo.getNombre(), torneo.getEstado(), liquidadoEn != null,
                liquidadoEn, MONEDA, cuentas.retenido().recaudado(), cuentas.retenido().gastosAdministrativos(),
                aporteRifa, cuentas.gastos(), cuentas.reintegro(), premios, alOrganizador,
                custodia.map(c -> pagos(c.getId())).orElse(0L));
    }

    /** El detalle de lo que se repartió. Antes de liquidar no hay reporte final: 409 FONDOS_EN_CUSTODIA. */
    public LiquidacionFinal liquidacionFinal(Long usuarioId, Long torneoId) {
        Torneo torneo = visible(usuarioId, torneoId);
        RepartoLiquidacion reparto = repartoLiquidacionService.delTorneo(torneo);
        if (!reparto.liquidado()) {
            throw new FondosEnCustodiaException(
                    "El reporte de liquidación final solo se emite después de que el torneo se liquida");
        }
        Cuentas cuentas = cuentas(torneoId, custodiaRepository.findByTorneoId(torneoId));
        return new LiquidacionFinal(torneo.getId(), torneo.getNombre(), reparto, cuentas.reintegro());
    }

    /**
     * Lo que se descarga: el reparto con el torneo al que pertenece.
     *
     * @param reintegro la parte de lo que cobró el organizador que son sus gastos; el resto es el pozo que nadie ganó
     *                  (la liquidación le acredita las dos cosas en un solo movimiento)
     */
    public record LiquidacionFinal(Long torneoId, String torneoNombre, RepartoLiquidacion reparto,
                                   BigDecimal reintegro) {
    }

    /**
     * El libro de ingresos (wireframe «Dashboard Contable y Liquidación»): cada pago de inscripción que entró a la
     * custodia, con sus gastos administrativos y quién pagó; el más nuevo primero. Sin pagos, vacío.
     */
    public List<IngresoInscripcion> ingresos(Long usuarioId, Long torneoId) {
        Torneo torneo = visible(usuarioId, torneoId);
        return custodiaRepository.findByTorneoId(torneoId)
                .map(c -> ingresos(torneo.getId(), movimientoRepository.ingresosDeLaCustodia(c.getId())))
                .orElse(List.of());
    }

    /** Los gastos de cada pago son los de su inscripción en el mismo estado: un pago devuelto y otro nuevo no se mezclan. */
    static List<IngresoInscripcion> ingresos(Long torneoId, List<Movimiento> movimientos) {
        Map<String, BigDecimal> gastos = movimientos.stream()
                .filter(m -> m.getTipo() == TipoMovimiento.COMISION && m.getInscripcion() != null)
                .collect(Collectors.groupingBy(m -> m.getInscripcion().getId() + ":" + m.getEstado(),
                        Collectors.reducing(BigDecimal.ZERO, Movimiento::getMonto, BigDecimal::add)));
        return movimientos.stream()
                .filter(m -> m.getTipo() == TipoMovimiento.INSCRIPCION)
                .map(pago -> {
                    Inscripcion inscripcion = pago.getInscripcion();
                    Usuario pagador = pago.getBilletera().getUsuario();
                    BigDecimal deGastos = inscripcion == null ? BigDecimal.ZERO
                            : gastos.getOrDefault(inscripcion.getId() + ":" + pago.getEstado(), BigDecimal.ZERO);
                    return new IngresoInscripcion(pago.getId(), "I" + torneoId + "-" + pago.getId(),
                            pago.getCreadoEn(), inscripcion == null ? null : inscripcion.getId(),
                            inscripcion != null && inscripcion.getEquipo() != null
                                    ? inscripcion.getEquipo().getNombre() : null,
                            pagador.getPersona().getNombres() + " " + pagador.getPersona().getApellidos(),
                            pagador.getUsername(), pago.getMonto(), deGastos, pago.getMonto().add(deGastos),
                            pago.getEstado());
                })
                .toList();
    }

    /** Como la liquidación: sus gastos se le reintegran al organizador hasta lo recaudado. */
    private record Cuentas(Retenido retenido, BigDecimal gastos, BigDecimal reintegro) {
    }

    private Cuentas cuentas(Long torneoId, Optional<Custodia> custodia) {
        Retenido retenido = custodia
                .map(c -> LiquidacionService.retenido(movimientoRepository, c.getId()))
                .orElse(new Retenido(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO));
        // un honorario se reintegra solo si se depositó en la custodia (HU-604)
        BigDecimal honorarios = custodia
                .map(c -> gastoRepository.honorariosEnCustodia(c.getId()).stream()
                        .map(g -> g.getMovimiento().getMonto())
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .orElse(BigDecimal.ZERO);
        BigDecimal gastos = gastoRepository.totalComunes(torneoId).add(honorarios);
        return new Cuentas(retenido, gastos, gastos.min(retenido.recaudado()));
    }

    private static BigDecimal suma(RepartoLiquidacion reparto, Rol rol) {
        return reparto.lineas().stream().filter(l -> l.rol() == rol).map(RepartoLiquidacion.Linea::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal aporteDeLaRifa(Long torneoId) {
        return rifaRepository.findByTorneoIdAndEliminadoEnIsNull(torneoId)
                .flatMap(rifa -> custodiaRepository.findByRifaId(rifa.getId())
                        .map(c -> RepartoRifa.deLoRecaudado(
                                LiquidacionService.retenido(movimientoRepository, c.getId()).vendido(),
                                rifa.getPctDestinadoTorneo()).alPozoTorneo()))
                .orElse(BigDecimal.ZERO);
    }

    private long pagos(Long custodiaId) {
        return movimientoRepository.totalesDeLaCustodia(custodiaId).stream()
                .filter(fila -> fila[0] == TipoMovimiento.INSCRIPCION)
                .mapToLong(fila -> (Long) fila[2])
                .sum();
    }

    private Torneo visible(Long usuarioId, Long torneoId) {
        Optional<Torneo> torneo = currentUserProvider.getPermisos().contains(PERMISO_ADMINISTRAR)
                ? torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                : torneoRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(torneoId, usuarioId);
        return torneo.orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                "No existe el torneo " + torneoId));
    }
}
