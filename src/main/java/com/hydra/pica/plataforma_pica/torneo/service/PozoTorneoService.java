package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calcula el pozo acumulado/vigente de un torneo (HU-305, HU-601).
 * Suma el pozo neto de las inscripciones más el porcentaje recaudado por la venta de números de la rifa asociada.
 */
@Service
@RequiredArgsConstructor
public class PozoTorneoService {

    private final TorneoRepository torneoRepository;
    private final GastoRepository gastoRepository;
    private final RifaRepository rifaRepository;
    private final NumeroRepository numeroRepository;

    @Transactional(readOnly = true)
    public BigDecimal calcularPozo(Long torneoId) {
        return torneoRepository.findById(torneoId)
                .map(this::calcularPozo)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public BigDecimal calcularPozo(Torneo torneo) {
        BigDecimal gastos = gastoRepository.totalDelTorneo(torneo.getId());
        int cupo = torneo.getCupoMaximo() != null ? torneo.getCupoMaximo() : 0;
        CuentasTorneo cuentas = CuentasTorneo.de(torneo, cupo, gastos);
        BigDecimal pozoBase = cuentas.pozoNeto() != null ? cuentas.pozoNeto() : BigDecimal.ZERO;

        BigDecimal aporteRifa = BigDecimal.ZERO;
        if (torneo.getPctPremioRifa() != null && torneo.getPctPremioRifa().signum() > 0) {
            var rifaOpt = rifaRepository.findByTorneoIdAndEliminadoEnIsNull(torneo.getId());
            if (rifaOpt.isPresent()) {
                Rifa rifa = rifaOpt.get();
                long vendidos = numeroRepository.countByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO);
                BigDecimal recaudacionRifa = rifa.getPrecioNumero() != null
                        ? rifa.getPrecioNumero().multiply(BigDecimal.valueOf(vendidos))
                        : BigDecimal.ZERO;
                aporteRifa = recaudacionRifa.multiply(torneo.getPctPremioRifa())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }
        }

        return pozoBase.add(aporteRifa);
    }
}
