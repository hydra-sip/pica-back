package com.hydra.pica.plataforma_pica.partido.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Cómo se arma la primera ronda de la llave con los que pasan de los grupos (CR-02, PICA-400): cruces fijos, sin
 * sorteo, así no hay otro sorteo que verificar.
 *
 * <ul>
 * <li>Los grupos se cruzan de a pares: A con B, C con D… El i.º de un grupo juega con el que quedó en el puesto
 * {@code pasan + 1 - i} del otro: con 2 por grupo, 1A–2B y 1B–2A.</li>
 * <li>Con un solo grupo, entre ellos: el 1.º con el último que pasa, el 2.º con el anteúltimo…</li>
 * <li>Los cruces de cada par van alternados a la mitad de arriba y a la de abajo del cuadro: los dos primeros de un
 * mismo grupo quedan en mitades distintas y solo se pueden volver a cruzar en la final.</li>
 * </ul>
 */
public final class CrucesLlave {

    private CrucesLlave() {
    }

    /** Un partido de la primera ronda; {@code posicion} empieza en 1. */
    public record Cruce(int posicion, long local, long visitante) {
    }

    /**
     * @param clasificados por grupo (A, B, C…), en el orden en que quedaron; todos con la misma cantidad, y el total
     *                     potencia de 2
     */
    public static List<Cruce> armar(List<List<Long>> clasificados) {
        int pasan = clasificados.getFirst().size();
        List<long[]> arriba = new ArrayList<>();
        List<long[]> abajo = new ArrayList<>();
        if (clasificados.size() == 1) {
            List<Long> grupo = clasificados.getFirst();
            for (int i = 0; i < pasan / 2; i++) {
                (i % 2 == 0 ? arriba : abajo).add(new long[] {grupo.get(i), grupo.get(pasan - 1 - i)});
            }
        } else {
            for (int par = 0; par + 1 < clasificados.size(); par += 2) {
                List<Long> x = clasificados.get(par);
                List<Long> y = clasificados.get(par + 1);
                for (int i = 0; i < pasan; i++) {
                    long[] cruce = i % 2 == 0
                            ? new long[] {x.get(i), y.get(pasan - 1 - i)}
                            : new long[] {y.get(pasan - 1 - i), x.get(i)};
                    (i % 2 == 0 ? arriba : abajo).add(cruce);
                }
            }
        }
        List<Cruce> cruces = new ArrayList<>();
        int posicion = 1;
        for (long[] cruce : arriba) {
            cruces.add(new Cruce(posicion++, cruce[0], cruce[1]));
        }
        for (long[] cruce : abajo) {
            cruces.add(new Cruce(posicion++, cruce[0], cruce[1]));
        }
        return List.copyOf(cruces);
    }
}
