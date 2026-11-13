package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.PanelFinanciero;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
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

    public PanelFinanciero delTorneo(Long usuarioId, Long torneoId) {
        Torneo torneo = visible(usuarioId, torneoId);
        Optional<Custodia> custodia = custodiaRepository.findByTorneoId(torneoId);

        Retenido retenido = custodia
                .map(c -> LiquidacionService.retenido(movimientoRepository, c.getId()))
                .orElse(new Retenido(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO));
        // como la liquidación: un honorario se reintegra solo si se depositó en la custodia (HU-604)
        BigDecimal honorarios = custodia
                .map(c -> gastoRepository.honorariosEnCustodia(c.getId()).stream()
                        .map(g -> g.getMovimiento().getMonto())
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .orElse(BigDecimal.ZERO);
        BigDecimal gastos = gastoRepository.totalComunes(torneoId).add(honorarios);
        BigDecimal reintegro = gastos.min(retenido.recaudado());
        BigDecimal aporteRifa = aporteDeLaRifa(torneoId);

        Instant liquidadoEn = custodia.filter(c -> c.getEstado() == EstadoCustodia.LIBERADA)
                .map(Custodia::getLiberadaEn)
                .orElse(null);
        return new PanelFinanciero(torneo.getId(), torneo.getNombre(), torneo.getEstado(), liquidadoEn != null,
                liquidadoEn, MONEDA, retenido.recaudado(), retenido.gastosAdministrativos(), aporteRifa, gastos,
                reintegro, retenido.recaudado().subtract(reintegro).add(aporteRifa),
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
        return new LiquidacionFinal(torneo.getId(), torneo.getNombre(), reparto);
    }

    /** Lo que se descarga: el reparto con el torneo al que pertenece. */
    public record LiquidacionFinal(Long torneoId, String torneoNombre, RepartoLiquidacion reparto) {
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
