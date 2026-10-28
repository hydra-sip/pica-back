package com.hydra.pica.plataforma_pica.torneo.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Los sellos SHA-256 que se fijan al publicar el torneo (PICA-204), en hexadecimal como las columnas {@code *_hash}.
 *
 * <p>El del esquema económico se calcula sobre un texto de formato fijo, para que cualquiera lo pueda recalcular con
 * los datos publicados: una línea por dato separadas con {@code \n}, en UTF-8, y montos y porcentajes con dos
 * decimales. Primero el torneo, después la inscripción, el % de gastos administrativos que paga aparte quien se
 * inscribe (PICA-283), un premio por puesto y un gasto previsto por número, los dos en orden; la descripción del gasto
 * va al final de su línea. Los torneos publicados antes de V27 llevan en su lugar el reparto viejo
 * ({@code reparto:competidores|rifa|comisión}) y su sello no se recalcula.
 * <pre>
 * torneo:17
 * inscripcion:20000.00 ARS
 * gastos-administrativos:10.00
 * premio:1:60.00
 * gasto:1:20000.00:Honorarios árbitros
 * </pre>
 * Lo que se paga de un gasto después no entra: pagarlo no cambia el sello.
 *
 * <p>El de la lista de inscriptos se fija al cerrar la inscripción (HU-206): una línea por inscripción confirmada,
 * ordenadas por id y separadas con {@code \n}, sin salto al final. La individual lleva el usuario; la de equipo, el
 * equipo y su nómina con el capitán, de menor a mayor. Sin confirmadas, el texto es vacío.
 * <pre>
 * 31;U;7
 * 32;E;4;7,9,12
 * </pre>
 */
public final class SelloTorneo {

    private SelloTorneo() {
    }

    public static String esquema(Torneo torneo, List<Premio> premios, List<Gasto> gastos) {
        return sha256(textoEsquema(torneo, premios, gastos));
    }

    public static String textoEsquema(Torneo torneo, List<Premio> premios, List<Gasto> gastos) {
        List<String> lineas = new ArrayList<>();
        lineas.add("torneo:" + torneo.getId());
        lineas.add("inscripcion:" + decimal(torneo.getValorInscripcion()) + " " + torneo.getMoneda());
        lineas.add("gastos-administrativos:" + decimal(torneo.getPctGastosAdministrativos()));
        premios.forEach(p -> lineas.add("premio:" + p.getId().getPuesto() + ":" + decimal(p.getPct())));
        gastos.forEach(g -> lineas.add("gasto:" + g.getId().getGastoId() + ":" + decimal(g.getMonto()) + ":"
                + g.getDescripcion()));
        return String.join("\n", lineas);
    }

    /** Una línea de la lista de inscriptos: la de una inscripción individual. */
    public static String lineaIndividual(Long inscripcionId, Long usuarioId) {
        return inscripcionId + ";U;" + usuarioId;
    }

    /** Una línea de la lista de inscriptos: la de una inscripción de equipo, con la nómina en cualquier orden. */
    public static String lineaDeEquipo(Long inscripcionId, Long equipoId, List<Long> nomina) {
        return inscripcionId + ";E;" + equipoId + ";"
                + nomina.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** El sello de la lista con las líneas ya en el orden de las inscripciones. */
    public static String lista(List<String> lineas) {
        return sha256(String.join("\n", lineas));
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
