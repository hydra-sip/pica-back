package com.hydra.pica.plataforma_pica.rifa.domain;

import java.util.Comparator;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;

/**
 * El sorteo de una rifa (HU-504, PICA-265), sin base de datos, como el del bracket: con la misma semilla y los mismos
 * vendidos sale siempre el mismo orden, así cualquiera lo puede recalcular. Cada número vendido se ordena por el
 * SHA-256 en hexadecimal de {@code semilla + ":" + número}, de menor a mayor (a igual hash, por número); el primero
 * gana el premio del puesto 1, el segundo el del 2, y así. Si hay más premios que vendidos, los que sobran quedan sin
 * ganador.
 */
public final class SorteoRifa {

    private SorteoRifa() {
    }

    public static List<Integer> orden(String semilla, List<Integer> vendidos) {
        return vendidos.stream()
                .sorted(Comparator.comparing((Integer numero) -> SelloTorneo.sha256(semilla + ":" + numero))
                        .thenComparing(numero -> numero))
                .toList();
    }
}
