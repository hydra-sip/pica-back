package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Datos generales de una rifa en BORRADOR, para el alta y la modificación (PICA-226). Los premios van
 * aparte, en {@link PremiosRifaRequest}. Mientras es borrador pueden faltar la ventana de venta (las
 * dos fechas van juntas) y la fecha programada del sorteo (PICA-219), que es posterior a
 * {@code ventaHasta}. Lo que compara un campo con otro (rango, ventana, sorteo) lo valida
 * {@code RifaService}.
 *
 * @param torneoId opcional (HU-401): un torneo del mismo organizador, en borrador o publicado sin empezar
 * @param pctDestinadoTorneo % entero de lo recaudado que va al pozo del torneo, de 0 a 99; sin torneo, 0 o
 *     que no venga. Llega como BigDecimal para rechazar decimales en vez de truncarlos.
 */
public record RifaRequest(
        @NotBlank @Size(max = 100) String titulo,
        @Size(max = 2000) String descripcion,
        @NotNull @Min(0) Integer numeroDesde,
        @NotNull @Min(0) Integer numeroHasta,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal precioNumero,
        Instant ventaDesde,
        Instant ventaHasta,
        Instant fechaSorteo,
        Long torneoId,
        @DecimalMin("0") @DecimalMax("99") @Digits(integer = 2, fraction = 0) BigDecimal pctDestinadoTorneo) {

    public int pctDestinadoTorneoEntero() {
        return pctDestinadoTorneo == null ? 0 : pctDestinadoTorneo.intValueExact();
    }
}
