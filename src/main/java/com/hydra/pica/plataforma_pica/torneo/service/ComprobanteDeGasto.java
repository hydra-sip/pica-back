package com.hydra.pica.plataforma_pica.torneo.service;

import java.util.Arrays;
import java.util.Optional;

/** Los formatos que se aceptan como comprobante de un gasto (HU-605): se mira el contenido, no la extensión. */
enum ComprobanteDeGasto {
    PDF("application/pdf", "pdf", new byte[]{'%', 'P', 'D', 'F', '-'}),
    JPG("image/jpeg", "jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    PNG("image/png", "png", new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});

    static final int TAMANIO_MAXIMO = 5 * 1024 * 1024;

    final String tipoContenido;
    final String extension;
    private final byte[] firma;

    ComprobanteDeGasto(String tipoContenido, String extension, byte[] firma) {
        this.tipoContenido = tipoContenido;
        this.extension = extension;
        this.firma = firma;
    }

    static Optional<ComprobanteDeGasto> de(byte[] contenido) {
        return Arrays.stream(values())
                .filter(f -> contenido.length >= f.firma.length
                        && Arrays.equals(contenido, 0, f.firma.length, f.firma, 0, f.firma.length))
                .findFirst();
    }
}
