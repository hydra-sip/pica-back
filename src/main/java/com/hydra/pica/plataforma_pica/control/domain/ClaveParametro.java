package com.hydra.pica.plataforma_pica.control.domain;

/**
 * Los parámetros de la plataforma que existen (V26, V33, V35 y V42), en el orden en que se muestran en el admin
 * (HU-702, PICA-282). Uno que no está acá no se puede editar.
 */
public enum ClaveParametro {

    GASTOS_ADMINISTRATIVOS(Unidad.PORCENTAJE),
    GASTOS_ADMINISTRATIVOS_TOPE(Unidad.PORCENTAJE),
    MARGEN_CIERRE_INICIO_MIN(Unidad.MINUTOS),
    CHECKIN_DURACION_MAX_MIN(Unidad.MINUTOS),
    RETIRO_MINIMO(Unidad.PESOS),
    RETIRO_MAXIMO(Unidad.PESOS),
    CANCELACION_GASTOS_TOPE(Unidad.PORCENTAJE);

    /** Cómo se valida y se muestra: un % va de 0 a 100, los minutos son enteros y los pesos llevan centavos. */
    public enum Unidad {
        PORCENTAJE,
        MINUTOS,
        PESOS
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
