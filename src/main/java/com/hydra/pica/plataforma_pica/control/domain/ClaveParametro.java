package com.hydra.pica.plataforma_pica.control.domain;

import java.math.BigDecimal;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;

/**
 * Los parámetros de la plataforma que existen (V26, V33, V35, V42, V57, V60 y V61), en el orden en que se muestran en
 * el admin (HU-702, PICA-282). Uno que no está acá no se puede editar.
 */
public enum ClaveParametro {

    GASTOS_ADMINISTRATIVOS(Unidad.PORCENTAJE),
    GASTOS_ADMINISTRATIVOS_TOPE(Unidad.PORCENTAJE),
    MARGEN_CIERRE_INICIO_MIN(Unidad.MINUTOS, true),
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
    FRAUDE_RIFA_NUMEROS_MAX(Unidad.CANTIDAD),
    FRAUDE_RIFA_VENTANA_MIN(Unidad.MINUTOS),
    FRAUDE_CARRUSEL_OPERACIONES_MIN(Unidad.CANTIDAD),
    FRAUDE_CARRUSEL_VENTANA_MIN(Unidad.MINUTOS),
    FRAUDE_CANCELACIONES_MIN(Unidad.CANTIDAD),
    FRAUDE_OBJECIONES_MIN(Unidad.CANTIDAD),
    FRAUDE_CUENTAS_USUARIOS_MIN(Unidad.CANTIDAD),
    INDICE_ORGANIZADOR_INICIAL(Unidad.PUNTOS),
    INDICE_ORGANIZADOR_CANCELACION(Unidad.PUNTOS),
    INDICE_ORGANIZADOR_CANCELACION_CON_GASTOS(Unidad.PUNTOS);

    /**
     * Cómo se valida y se muestra: un % va de 0 a 100, los minutos son enteros, los pesos llevan centavos, los puntos
     * del índice de fair play (HU-802) son enteros de 0 a 1000 y las cantidades (HU-705) son enteros mayores que 0.
     */
    public enum Unidad {
        PORCENTAJE {
            @Override
            Optional<ErrorCampo> validar(ClaveParametro clave, BigDecimal valor) {
                if (valor.signum() < 0 || valor.compareTo(CIEN) > 0) {
                    return Optional.of(new ErrorCampo(clave.name(), ErrorCampo.Codigo.RANGO_INVALIDO,
                            "Tiene que estar entre 0 y 100"));
                }
                return validarHastaDosDecimales(clave, valor);
            }
        },
        MINUTOS {
            @Override
            Optional<ErrorCampo> validar(ClaveParametro clave, BigDecimal valor) {
                return validarEnteroPositivoOMaximo(clave, valor, "Son minutos enteros");
            }
        },
        PESOS {
            @Override
            Optional<ErrorCampo> validar(ClaveParametro clave, BigDecimal valor) {
                if (valor.signum() <= 0) {
                    return Optional.of(new ErrorCampo(clave.name(), ErrorCampo.Codigo.RANGO_INVALIDO,
                            "Tiene que ser mayor que 0"));
                }
                if (valor.compareTo(MAXIMO) > 0) {
                    return Optional.of(new ErrorCampo(clave.name(), ErrorCampo.Codigo.RANGO_INVALIDO,
                            "Es demasiado grande"));
                }
                return validarHastaDosDecimales(clave, valor);
            }
        },
        PUNTOS {
            @Override
            Optional<ErrorCampo> validar(ClaveParametro clave, BigDecimal valor) {
                if (tieneDecimales(valor)) {
                    return Optional.of(new ErrorCampo(clave.name(), ErrorCampo.Codigo.FORMATO_INVALIDO,
                            "Son puntos enteros"));
                }
                if (valor.signum() < 0 || valor.compareTo(MIL) > 0) {
                    return Optional.of(new ErrorCampo(clave.name(), ErrorCampo.Codigo.RANGO_INVALIDO,
                            "Tiene que estar entre 0 y 1000"));
                }
                return Optional.empty();
            }
        },
        CANTIDAD {
            @Override
            Optional<ErrorCampo> validar(ClaveParametro clave, BigDecimal valor) {
                return validarEnteroPositivoOMaximo(clave, valor, "Tiene que ser una cantidad entera");
            }
        };

        /** El máximo de NUMERIC(12,2). */
        private static final BigDecimal MAXIMO = new BigDecimal("9999999999.99");
        private static final BigDecimal CIEN = BigDecimal.valueOf(100);
        private static final BigDecimal MIL = BigDecimal.valueOf(1000);

        abstract Optional<ErrorCampo> validar(ClaveParametro clave, BigDecimal valor);

        private static Optional<ErrorCampo> validarHastaDosDecimales(ClaveParametro clave, BigDecimal valor) {
            if (valor.stripTrailingZeros().scale() > 2) {
                return Optional.of(new ErrorCampo(clave.name(), ErrorCampo.Codigo.FORMATO_INVALIDO,
                        "Hasta dos decimales"));
            }
            return Optional.empty();
        }

        private static Optional<ErrorCampo> validarEnteroPositivoOMaximo(
                ClaveParametro clave,
                BigDecimal valor,
                String mensajeDecimal) {
            if (tieneDecimales(valor)) {
                return Optional.of(new ErrorCampo(clave.name(), ErrorCampo.Codigo.FORMATO_INVALIDO, mensajeDecimal));
            }
            boolean invalido = clave.admiteCero() ? valor.signum() < 0 : valor.signum() <= 0;
            if (invalido) {
                return Optional.of(new ErrorCampo(clave.name(), ErrorCampo.Codigo.RANGO_INVALIDO,
                        clave.admiteCero() ? "No puede ser negativo" : "Tiene que ser mayor que 0"));
            }
            if (valor.compareTo(MAXIMO) > 0) {
                return Optional.of(new ErrorCampo(clave.name(), ErrorCampo.Codigo.RANGO_INVALIDO,
                        "Es demasiado grande"));
            }
            return Optional.empty();
        }

        private static boolean tieneDecimales(BigDecimal valor) {
            return valor.stripTrailingZeros().scale() > 0;
        }
    }

    private final Unidad unidad;
    private final boolean admiteCero;

    ClaveParametro(Unidad unidad) {
        this(unidad, false);
    }

    ClaveParametro(Unidad unidad, boolean admiteCero) {
        this.unidad = unidad;
        this.admiteCero = admiteCero;
    }

    public Unidad unidad() {
        return unidad;
    }

    public boolean admiteCero() {
        return admiteCero;
    }

    /** Valida el rango y formato de {@code valor} según la unidad y reglas propias de esta clave. */
    public Optional<ErrorCampo> validar(BigDecimal valor) {
        return unidad.validar(this, valor);
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
