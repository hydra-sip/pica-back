package com.hydra.pica.plataforma_pica.rifa.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import org.junit.jupiter.api.Test;

/** El orden del sorteo de una rifa (PICA-265), con las pruebas de aceptación de HU-504 sobre el cálculo. */
class SorteoRifaTest {

    private static final List<Integer> VENDIDOS = List.of(3, 7, 12, 25, 40, 58, 77, 91);
    private static final String SEMILLA = "9b41c0f2a7d35e8814b6c9a0e2f17d3c5b8a6e4d2c1f0e9d8b7a6c5d4e3f2a1b";

    @Test
    void conLaMismaListaYLaMismaSemillaSaleElMismoResultado() {
        assertThat(SorteoRifa.orden(SEMILLA, VENDIDOS)).isEqualTo(SorteoRifa.orden(SEMILLA, List.copyOf(VENDIDOS)));
    }

    @Test
    void conOtraSemillaElResultadoCambia() {
        assertThat(SorteoRifa.orden(SEMILLA, VENDIDOS)).isNotEqualTo(SorteoRifa.orden(SEMILLA + "0", VENDIDOS));
    }

    @Test
    void elOrdenEsElDelHashDeSemillaYNumeroDeMenorAMayor() {
        List<Integer> esperado = VENDIDOS.stream()
                .sorted(Comparator.comparing(n -> SelloTorneo.sha256(SEMILLA + ":" + n)))
                .toList();

        assertThat(SorteoRifa.orden(SEMILLA, VENDIDOS)).isEqualTo(esperado);
    }

    @Test
    void elOrdenNoDependeDeComoVieneLaLista() {
        assertThat(SorteoRifa.orden(SEMILLA, VENDIDOS.reversed())).isEqualTo(SorteoRifa.orden(SEMILLA, VENDIDOS));
    }
}
