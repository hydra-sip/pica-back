package com.hydra.pica.plataforma_pica.torneo.dto;

/** El archivo del comprobante de un gasto (HU-605), con su tipo para servirlo inline. */
public record Comprobante(byte[] contenido, String tipoContenido) {
}
