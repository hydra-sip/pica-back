package com.hydra.pica.plataforma_pica.partido.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import com.hydra.pica.plataforma_pica.partido.domain.CrucesLlave.Cruce;
import org.junit.jupiter.api.Test;

/** Los cruces fijos de la llave con los que pasan de los grupos (CR-02-6, PICA-400). */
class CrucesLlaveTest {

    // grupo A: 11 primero, 12 segundo…; grupo B: 21, 22…
    private static final List<Long> A = List.of(11L, 12L, 13L, 14L);
    private static final List<Long> B = List.of(21L, 22L, 23L, 24L);
    private static final List<Long> C = List.of(31L, 32L);
    private static final List<Long> D = List.of(41L, 42L);

    @Test
    void conDosGruposYDosQuePasan1AContra2BY1BContra2A() {
        assertThat(CrucesLlave.armar(List.of(A.subList(0, 2), B.subList(0, 2))))
                .containsExactly(new Cruce(1, 11, 22), new Cruce(2, 21, 12));
    }

    @Test
    void conCuatroGruposLosDeUnMismoGrupoQuedanEnMitadesDistintas() {
        List<Cruce> cruces = CrucesLlave.armar(List.of(A.subList(0, 2), B.subList(0, 2), C, D));

        assertThat(cruces).containsExactly(new Cruce(1, 11, 22), new Cruce(2, 31, 42),
                new Cruce(3, 21, 12), new Cruce(4, 41, 32));
        // mitad de arriba: posiciones 1 y 2; de abajo: 3 y 4
        assertThat(cruces.subList(0, 2)).noneMatch(c -> c.local() == 12 || c.visitante() == 12);
    }

    @Test
    void conUnSoloGrupoElPrimeroJuegaConElUltimoQuePasa() {
        assertThat(CrucesLlave.armar(List.of(A))).containsExactly(new Cruce(1, 11, 14), new Cruce(2, 12, 13));
        assertThat(CrucesLlave.armar(List.of(A.subList(0, 2)))).containsExactly(new Cruce(1, 11, 12));
    }

    @Test
    void conCuatroQuePasanPorGrupoLosDosPrimerosDeCadaUnoQuedanEnMitadesDistintas() {
        List<Cruce> cruces = CrucesLlave.armar(List.of(A, B));

        assertThat(cruces).containsExactly(new Cruce(1, 11, 24), new Cruce(2, 13, 22),
                new Cruce(3, 23, 12), new Cruce(4, 21, 14));
    }

    @Test
    void conUnoQuePasaPorGrupoSeCruzanLosGanadores() {
        assertThat(CrucesLlave.armar(List.of(List.of(11L), List.of(21L), List.of(31L), List.of(41L))))
                .containsExactly(new Cruce(1, 11, 21), new Cruce(2, 31, 41));
    }
}
