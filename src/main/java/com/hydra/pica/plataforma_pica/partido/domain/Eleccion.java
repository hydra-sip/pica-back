package com.hydra.pica.plataforma_pica.partido.domain;

/** Pronóstico de un partido. En la base se guarda como 1, X o 2 ({@link EleccionConverter}). */
public enum Eleccion {
    LOCAL("1"),
    EMPATE("X"),
    VISITANTE("2");

    private final String codigo;

    Eleccion(String codigo) {
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }

    public static Eleccion deCodigo(String codigo) {
        for (Eleccion eleccion : values()) {
            if (eleccion.codigo.equals(codigo)) {
                return eleccion;
            }
        }
        throw new IllegalArgumentException("Elección desconocida: " + codigo);
    }
}
