package com.hydra.pica.plataforma_pica.rifa.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;

/**
 * El sorteo de una rifa (HU-504, PICA-265), sin base de datos, como el del bracket: con la misma semilla y los mismos
 * vendidos sale siempre el mismo orden, así cualquiera lo puede recalcular. Cada número vendido se ordena por el
 * SHA-256 en hexadecimal de {@code semilla + ":" + número}, de menor a mayor (a igual hash, por número).
 * <ul>
 * <li>Versión 1: el primero del orden gana el premio del puesto 1, el segundo el del 2, y así.</li>
 * <li>Versión 2 (CR-06): nadie gana dos veces. Se recorre el orden y se saltean los números de un comprador que ya
 * ganó; da lo mismo que sacar sus números y volver a ordenar, porque el orden de cada número no depende de los demás.</li>
 * </ul>
 * Si no alcanzan los números (o los compradores, en la versión 2), los premios que sobran quedan sin ganador.
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

    /**
     * El número ganador de cada premio, del puesto 1 en adelante: a lo sumo {@code premios}, menos si no alcanzan.
     *
     * @param compradores el comprador de cada número, en la versión 2 (la etiqueta de {@link SelloRifa#compradores});
     *                    en la 1 no se usa
     */
    public static List<Integer> ganadores(int version, String semilla, List<Integer> vendidos,
                                          Map<Integer, String> compradores, int premios) {
        List<Integer> ganadores = new ArrayList<>();
        Set<String> yaGanaron = new HashSet<>();
        for (Integer numero : orden(semilla, vendidos)) {
            if (ganadores.size() == premios) {
                break;
            }
            if (version >= 2 && !yaGanaron.add(compradores.get(numero))) {
                continue;
            }
            ganadores.add(numero);
        }
        return ganadores;
    }
}
