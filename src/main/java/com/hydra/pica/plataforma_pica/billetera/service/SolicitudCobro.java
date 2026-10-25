package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;

/**
 * Lo que necesita {@link CobroService} para cobrar. Los datos inválidos son un error de quien llama,
 * no del usuario, así que fallan con {@link IllegalArgumentException}.
 *
 * @param usuarioId         quien paga
 * @param tipo              INSCRIPCION, COMPRA_NUMERO o COMISION
 * @param torneoId          opcional
 * @param inscripcionId     obligatorio en una INSCRIPCION
 * @param claveIdempotencia la arma quien cobra ("inscripcion-42", la clave del ticket...): con la
 *                          misma clave no se cobra dos veces
 */
public record SolicitudCobro(
        Long usuarioId,
        TipoMovimiento tipo,
        BigDecimal monto,
        Long torneoId,
        Long inscripcionId,
        String claveIdempotencia,
        String descripcion) {

    private static final Set<TipoMovimiento> TIPOS_DE_PAGO =
            Set.of(TipoMovimiento.INSCRIPCION, TipoMovimiento.COMPRA_NUMERO, TipoMovimiento.COMISION);

    public SolicitudCobro {
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(monto, "monto");
        if (!TIPOS_DE_PAGO.contains(tipo)) {
            throw new IllegalArgumentException("No es un tipo de pago: " + tipo);
        }
        if (monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto tiene que ser positivo: " + monto);
        }
        if (tipo == TipoMovimiento.INSCRIPCION && inscripcionId == null) {
            throw new IllegalArgumentException("Una INSCRIPCION necesita su inscripción");
        }
        if (claveIdempotencia == null || claveIdempotencia.isBlank() || claveIdempotencia.length() > 64) {
            throw new IllegalArgumentException("La clave de idempotencia va de 1 a 64 caracteres");
        }
    }
}
