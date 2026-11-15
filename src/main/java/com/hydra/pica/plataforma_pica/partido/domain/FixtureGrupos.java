package com.hydra.pica.plataforma_pica.partido.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * El fixture de un grupo (CR-02, PICA-398): todos contra todos una vez, por el método del círculo. Con n integrantes
 * son n - 1 fechas si n es par y n si es impar (cada fecha, uno queda libre). El primero queda fijo y los demás
 * rotan; quién es local se alterna para que nadie sea siempre local.
 */
public final class FixtureGrupos {

    private FixtureGrupos() {
    }

    public record Cruce(long local, long visitante) {
    }

    /** @return por fecha, sus cruces */
    public static List<List<Cruce>> fechas(List<Long> integrantes) {
        List<Long> ronda = new ArrayList<>(integrantes);
        if (ronda.size() % 2 == 1) {
            ronda.add(null); // el que cruza con null queda libre esa fecha
        }
        int n = ronda.size();
        List<List<Cruce>> fechas = new ArrayList<>();
        for (int fecha = 0; fecha < n - 1; fecha++) {
            List<Cruce> cruces = new ArrayList<>();
            for (int i = 0; i < n / 2; i++) {
                Long a = ronda.get(i);
                Long b = ronda.get(n - 1 - i);
                if (a == null || b == null) {
                    continue;
                }
                boolean invertir = (i == 0 && fecha % 2 == 1) || (i > 0 && i % 2 == 1);
                cruces.add(invertir ? new Cruce(b, a) : new Cruce(a, b));
            }
            fechas.add(List.copyOf(cruces));
            ronda.add(1, ronda.removeLast());
        }
        return List.copyOf(fechas);
    }
}
