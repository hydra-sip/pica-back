package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
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
 * Suma el pozo neto de las inscripciones más lo que aporta la rifa asociada: el % de lo vendido que la rifa destina al
 * torneo ({@code pctDestinadoTorneo}, HU-401).
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

        // la misma cuenta que el desglose de la rifa (HU-405)
        BigDecimal aporteRifa = rifaRepository.findByTorneoIdAndEliminadoEnIsNull(torneo.getId())
                .map(rifa -> RepartoRifa.de(rifa,
                        numeroRepository.countByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO)).alPozoTorneo())
                .orElse(BigDecimal.ZERO);

        return pozoBase.add(aporteRifa);
    }
}
