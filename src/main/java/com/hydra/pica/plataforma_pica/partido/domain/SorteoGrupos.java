package com.hydra.pica.plataforma_pica.partido.domain;

import java.util.ArrayList;
import java.util.List;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;

/**
 * El sorteo de los grupos (CR-02, PICA-397), sin base de datos: con la misma semilla y los mismos inscriptos sale
 * siempre el mismo reparto, así cualquiera lo puede recalcular en el portal.
 *
 * <ol>
 * <li>El orden es el de la llave ({@link SorteoBracket#orden}): SHA-256 en hexadecimal de
 * {@code semilla + ":" + inscripcionId}, de menor a mayor.</li>
 * <li>Se reparte en serpentina: el primero al A, el segundo al B… y al llegar al último grupo se vuelve: con 4
 * grupos, A B C D D C B A A B C D… Así, si no se llena el cupo, los grupos difieren en uno como mucho.</li>
 * </ol>
 * La semilla sale de drand: es el valor de la ronda fijada al sellar la lista, con el compromiso
 * {@code SHA-256(listaHash + ":" + ronda)} publicado antes de que exista ({@link #compromiso}).
 */
public final class SorteoGrupos {

    /** Por si el algoritmo cambia: el portal recalcula según la versión. */
    public static final int VERSION = 1;

    private SorteoGrupos() {
    }

    /** @return por grupo (A, B, C…), sus inscripciones en el orden en que salieron */
    public static List<List<Long>> repartir(String semilla, List<Long> inscripciones, int grupos) {
        if (grupos < 1) {
            throw new IllegalArgumentException("Hace falta al menos un grupo");
        }
        List<Long> orden = SorteoBracket.orden(semilla, inscripciones);
        List<List<Long>> porGrupo = new ArrayList<>();
        for (int i = 0; i < grupos; i++) {
            porGrupo.add(new ArrayList<>());
        }
        for (int i = 0; i < orden.size(); i++) {
            int vuelta = i / grupos;
            int lugar = i % grupos;
            porGrupo.get(vuelta % 2 == 0 ? lugar : grupos - 1 - lugar).add(orden.get(i));
        }
        return porGrupo.stream().map(List::copyOf).toList();
    }

    /** La letra del grupo: 0 es A. */
    public static String letra(int indice) {
        return String.valueOf((char) ('A' + indice));
    }

    public static String compromiso(String listaHash, long ronda) {
        return SelloTorneo.sha256(listaHash + ":" + ronda);
    }
}
