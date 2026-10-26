package com.hydra.pica.plataforma_pica.torneo.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Los sellos SHA-256 que se fijan al publicar el torneo (PICA-204), en hexadecimal como las columnas {@code *_hash}.
 *
 * <p>El del esquema económico se calcula sobre un texto de formato fijo, para que cualquiera lo pueda recalcular con
 * los datos publicados: una línea por dato separadas con {@code \n}, en UTF-8, y montos y porcentajes con dos
 * decimales. Primero el torneo, después la inscripción, el reparto (competidores|rifa|comisión), un premio por puesto
 * y un gasto previsto por número, los dos en orden; la descripción del gasto va al final de su línea.
 * <pre>
 * torneo:17
 * inscripcion:20000.00 ARS
 * reparto:90.00|0.00|10.00
 * premio:1:60.00
 * gasto:1:20000.00:Honorarios árbitros
 * </pre>
 * Lo que se paga de un gasto después no entra: pagarlo no cambia el sello.
 */
public final class SelloTorneo {

    private SelloTorneo() {
    }

    public static String esquema(Torneo torneo, List<Premio> premios, List<Gasto> gastos) {
        return sha256(textoEsquema(torneo, premios, gastos));
    }

    public static String textoEsquema(Torneo torneo, List<Premio> premios, List<Gasto> gastos) {
        BigDecimal comision = BigDecimal.valueOf(100)
                .subtract(torneo.getPctPozoCompetidores())
                .subtract(torneo.getPctPremioRifa());
        List<String> lineas = new ArrayList<>();
        lineas.add("torneo:" + torneo.getId());
        lineas.add("inscripcion:" + decimal(torneo.getValorInscripcion()) + " " + torneo.getMoneda());
        lineas.add("reparto:" + decimal(torneo.getPctPozoCompetidores()) + "|" + decimal(torneo.getPctPremioRifa())
                + "|" + decimal(comision));
        premios.forEach(p -> lineas.add("premio:" + p.getId().getPuesto() + ":" + decimal(p.getPct())));
        gastos.forEach(g -> lineas.add("gasto:" + g.getId().getGastoId() + ":" + decimal(g.getMonto()) + ":"
                + g.getDescripcion()));
        return String.join("\n", lineas);
    }

    public static String sha256(String texto) {
        return sha256(texto.getBytes(StandardCharsets.UTF_8));
    }

    /** El del PDF del reglamento (PICA-200): sobre el archivo tal cual se subió. */
    public static String sha256(byte[] contenido) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static String decimal(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
