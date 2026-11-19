package com.hydra.pica.plataforma_pica.control.domain;

/**
 * Reglas iniciales de detección de conductas fraudulentas (HU-705, PICA-416).
 */
public enum ReglaFraude {
    COMPRA_MASIVA_RIFA,
    CARRUSEL_CARGA_RETIRO,
    CANCELACIONES_REITERADAS,
    OBJECIONES_ABUSIVAS,
    CUENTAS_COMPARTIDAS
}
