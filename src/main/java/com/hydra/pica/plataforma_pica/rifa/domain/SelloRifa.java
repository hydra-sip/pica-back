package com.hydra.pica.plataforma_pica.rifa.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;

/**
 * El sello SHA-256 de la lista de números vendidos, que se fija al cerrar la venta (HU-407), en hexadecimal como
 * {@code rifa.lista_hash}. Se calcula sobre un texto de formato fijo para que cualquiera lo pueda recalcular: una
 * línea por número vendido, de menor a mayor, separadas con {@code \n} y sin salto al final. Sin vendidos, el texto es
 * vacío. El formato depende de la versión del sorteo de la rifa:
 * <ul>
 * <li>Versión 1: el número y el código de su ticket ({@code 5;R7-4F2K9Q}).</li>
 * <li>Versión 2 (CR-06): además, quién lo compró, con una etiqueta anónima de la rifa ({@code 5;R7-4F2K9Q;C1}). Las
 * etiquetas van en orden del número más bajo de cada comprador: el de ese número es C1, el siguiente C2. Con ellas el
 * sorteo saltea a quien ya ganó ({@link SorteoRifa#ganadores}) y cualquiera lo puede recalcular, sin saber quién es.</li>
 * </ul>
 * El compromiso del sorteo (HU-502) es el SHA-256 de {@code listaHash:fechaSorteo}, con la fecha programada en
 * ISO-8601 UTC como la devuelve la API ({@code 2026-10-31T15:00:00Z}): fija qué se sortea y cuándo, antes de que
 * exista la semilla.
 */
public final class SelloRifa {

    /** La que se usa al sellar desde CR-06. */
    public static final int VERSION_ACTUAL = 2;

    private SelloRifa() {
    }

    public static String lista(List<Numero> vendidos, int version) {
        return SelloTorneo.sha256(textoLista(vendidos, version));
    }

    public static String compromiso(String listaHash, Instant fechaSorteo) {
        return SelloTorneo.sha256(listaHash + ":" + fechaSorteo);
    }

    public static String textoLista(List<Numero> vendidos, int version) {
        Map<Integer, String> compradores = version >= 2 ? compradores(vendidos) : Map.of();
        return vendidos.stream()
                .sorted(Comparator.comparingInt(n -> n.getId().getNumero()))
                .map(n -> n.getId().getNumero() + ";" + n.getTicket().getCodigo()
                        + (version >= 2 ? ";" + compradores.get(n.getId().getNumero()) : ""))
                .collect(Collectors.joining("\n"));
    }

    /** La etiqueta anónima del comprador de cada número: C1 el del número más bajo, C2 el siguiente comprador... */
    public static Map<Integer, String> compradores(List<Numero> vendidos) {
        Map<Long, String> etiquetaDe = new HashMap<>();
        Map<Integer, String> porNumero = new HashMap<>();
        vendidos.stream()
                .sorted(Comparator.comparingInt(n -> n.getId().getNumero()))
                .forEach(n -> porNumero.put(n.getId().getNumero(), etiquetaDe.computeIfAbsent(
                        n.getTicket().getComprador().getId(), comprador -> "C" + (etiquetaDe.size() + 1))));
        return porNumero;
    }
}
