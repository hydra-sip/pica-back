package com.hydra.pica.plataforma_pica.rifa.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;

/**
 * El sello SHA-256 de la lista de números vendidos, que se fija al cerrar la venta (HU-407), en hexadecimal como
 * {@code rifa.lista_hash}. Se calcula sobre un texto de formato fijo para que cualquiera lo pueda recalcular: una
 * línea por número vendido, de menor a mayor, con el número y el código de su ticket, separadas con {@code \n} y sin
 * salto al final. Sin vendidos, el texto es vacío.
 * <pre>
 * 5;R7-4F2K9Q
 * 12;R7-8HX2LM
 * </pre>
 * El compromiso del sorteo (HU-502) es el SHA-256 de {@code listaHash:fechaSorteo}, con la fecha programada en
 * ISO-8601 UTC como la devuelve la API ({@code 2026-10-31T15:00:00Z}): fija qué se sortea y cuándo, antes de que
 * exista la semilla.
 */
public final class SelloRifa {

    private SelloRifa() {
    }

    public static String lista(List<Numero> vendidos) {
        return SelloTorneo.sha256(textoLista(vendidos));
    }

    public static String compromiso(String listaHash, Instant fechaSorteo) {
        return SelloTorneo.sha256(listaHash + ":" + fechaSorteo);
    }

    public static String textoLista(List<Numero> vendidos) {
        return vendidos.stream()
                .sorted(Comparator.comparingInt(n -> n.getId().getNumero()))
                .map(n -> n.getId().getNumero() + ";" + n.getTicket().getCodigo())
                .collect(Collectors.joining("\n"));
    }
}
