package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;

/**
 * Cómo se repartió la custodia de un torneo o una rifa al liquidarse (HU-606, wireframe 64): una línea por
 * acreditación. Mientras no se liquidó, {@code liquidado} es false y no hay líneas.
 *
 * @param total lo repartido: la suma de las líneas
 */
public record RepartoLiquidacion(boolean liquidado, Instant liquidadoEn, BigDecimal total, String moneda,
                                 List<Linea> lineas) {

    /** De dónde sale la plata de la línea: el torneo, la rifa o lo que la rifa aporta al pozo del torneo. */
    public enum Origen { TORNEO, RIFA, APORTE_RIFA }

    /**
     * @param puesto              el de un premio
     * @param beneficiarioNombre  null en la plataforma
     * @param equipoNombre        el equipo del capitán que cobra, en un torneo por equipos
     */
    public record Linea(Rol rol, Origen origen, Integer puesto, String beneficiarioNombre,
                        String beneficiarioUsername, String equipoNombre, BigDecimal monto, Instant acreditadoEn) {
    }
}
