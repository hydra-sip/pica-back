package com.hydra.pica.plataforma_pica.billetera.service;

/**
 * Algoritmo oficial del BCRA para validar CBU y CVU de 22 dígitos mediante doble dígito verificador.
 */
public final class ValidadorCbuCvu {

    private static final int[] PONDERADOR_BLOQUE_1 = {7, 1, 3, 9, 7, 1, 3};
    private static final int[] PONDERADOR_BLOQUE_2 = {3, 9, 7, 1, 3, 9, 7, 1, 3, 9, 7, 1, 3};

    private ValidadorCbuCvu() {
    }

    public static boolean esValido(String cbuCvu) {
        if (cbuCvu == null || !cbuCvu.matches("^\\d{22}$")) {
            return false;
        }

        // Bloque 1: posiciones 0..6, verificador en posición 7
        int suma1 = 0;
        for (int i = 0; i < 7; i++) {
            suma1 += Character.digit(cbuCvu.charAt(i), 10) * PONDERADOR_BLOQUE_1[i];
        }
        int verificador1 = (10 - (suma1 % 10)) % 10;
        if (Character.digit(cbuCvu.charAt(7), 10) != verificador1) {
            return false;
        }

        // Bloque 2: posiciones 8..20, verificador en posición 21
        int suma2 = 0;
        for (int i = 0; i < 13; i++) {
            suma2 += Character.digit(cbuCvu.charAt(8 + i), 10) * PONDERADOR_BLOQUE_2[i];
        }
        int verificador2 = (10 - (suma2 % 10)) % 10;
        return Character.digit(cbuCvu.charAt(21), 10) == verificador2;
    }
}

