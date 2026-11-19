package com.hydra.pica.plataforma_pica.control.domain;

/**
 * Los parámetros de la plataforma que existen (V26, V33, V35, V42 y V57), en el orden en que se muestran en el admin
 * (HU-702, PICA-282). Uno que no está acá no se puede editar.
 */
public enum ClaveParametro {

    GASTOS_ADMINISTRATIVOS(Unidad.PORCENTAJE),
    GASTOS_ADMINISTRATIVOS_TOPE(Unidad.PORCENTAJE),
    MARGEN_CIERRE_INICIO_MIN(Unidad.MINUTOS),
    CHECKIN_DURACION_MAX_MIN(Unidad.MINUTOS),
    RETIRO_MINIMO(Unidad.PESOS),
    RETIRO_MAXIMO(Unidad.PESOS),
    CANCELACION_GASTOS_TOPE(Unidad.PORCENTAJE),
    FAIR_PLAY_INICIAL(Unidad.PUNTOS),
    FAIR_PLAY_MAXIMO(Unidad.PUNTOS),
    FAIR_PLAY_CHECKIN(Unidad.PUNTOS),
    FAIR_PLAY_AUSENCIA(Unidad.PUNTOS),
    FAIR_PLAY_SANCION_LEVE(Unidad.PUNTOS),
    FAIR_PLAY_SANCION_MEDIA(Unidad.PUNTOS),
    FAIR_PLAY_SANCION_GRAVE(Unidad.PUNTOS),
    INDICE_ORGANIZADOR_INICIAL(Unidad.PUNTOS),
    INDICE_ORGANIZADOR_CANCELACION(Unidad.PUNTOS),
    INDICE_ORGANIZADOR_CANCELACION_CON_GASTOS(Unidad.PUNTOS);

    /**
     * Cómo se valida y se muestra: un % va de 0 a 100, los minutos son enteros, los pesos llevan centavos y los puntos
     * del índice de fair play (HU-802) son enteros de 0 a 1000.
     */
    public enum Unidad {
        PORCENTAJE,
        MINUTOS,
        PESOS,
        PUNTOS
    }

    private final Unidad unidad;

    ClaveParametro(Unidad unidad) {
        this.unidad = unidad;
    }

    public Unidad unidad() {
        return unidad;
    }

    /** Null si la clave no es un parámetro conocido. */
    public static ClaveParametro de(String clave) {
        for (ClaveParametro c : values()) {
            if (c.name().equals(clave)) {
                return c;
            }
        }
        return null;
    }
}
