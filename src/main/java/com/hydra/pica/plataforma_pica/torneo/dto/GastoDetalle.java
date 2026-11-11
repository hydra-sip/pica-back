package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoPagoGasto;
import com.hydra.pica.plataforma_pica.torneo.domain.Gasto;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoGasto;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

/**
 * Un gasto previsto; {@code gastoId} lo numera dentro del torneo. Con HU-604, también si es un honorario arbitral y
 * dónde está su pago.
 *
 * @param beneficiario el árbitro de un honorario depositado; null si no se depositó
 * @param abonadoEn    cuándo se depositó o se pagó por fuera
 * @param comprobante  el de un gasto común pagado por fuera, o la transferencia interna de un honorario depositado
 */
public record GastoDetalle(
        int gastoId,
        String descripcion,
        BigDecimal monto,
        TipoGasto tipo,
        EstadoPagoGasto estadoPago,
        UsuarioPublico beneficiario,
        Instant abonadoEn,
        String comprobante) {

    public static GastoDetalle de(Gasto gasto) {
        return new GastoDetalle(gasto.getId().getGastoId(), gasto.getDescripcion(), gasto.getMonto(), gasto.getTipo(),
                estadoPago(gasto), gasto.getBeneficiario() == null ? null : UsuarioPublico.desde(gasto.getBeneficiario()),
                gasto.getFechaAbonado(), gasto.getComprobante());
    }

    public static EstadoPagoGasto estadoPago(Gasto gasto) {
        if (gasto.getMovimiento() != null) {
            Custodia custodia = gasto.getMovimiento().getCustodia();
            if (custodia == null || custodia.getEstado() == EstadoCustodia.RETENIDA) {
                return EstadoPagoGasto.EN_CUSTODIA;
            }
            return custodia.getEstado() == EstadoCustodia.LIBERADA ? EstadoPagoGasto.ACREDITADO
                    : EstadoPagoGasto.REINTEGRADO;
        }
        return gasto.getMontoAbonado().signum() > 0 ? EstadoPagoGasto.PAGADO : EstadoPagoGasto.PENDIENTE;
    }
}
