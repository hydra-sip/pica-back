package com.hydra.pica.plataforma_pica.partido.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import org.junit.jupiter.api.Test;

/** El reparto de los grupos (CR-02, PICA-397): el orden de la llave en serpentina. */
class SorteoGruposTest {

    private static final String SEMILLA = "a3f1";

    @Test
    void repartenEnSerpentinaConElOrdenDeLaLlave() {
        List<Long> ids = ids(8);
        List<Long> orden = SorteoBracket.orden(SEMILLA, ids);

        List<List<Long>> grupos = SorteoGrupos.repartir(SEMILLA, ids, 4);

        // A B C D D C B A
        assertThat(grupos.get(0)).containsExactly(orden.get(0), orden.get(7));
        assertThat(grupos.get(1)).containsExactly(orden.get(1), orden.get(6));
        assertThat(grupos.get(2)).containsExactly(orden.get(2), orden.get(5));
        assertThat(grupos.get(3)).containsExactly(orden.get(3), orden.get(4));
    }

    @Test
    void conLaMismaSemillaSaleLoMismoYConOtraNo() {
        List<Long> ids = ids(16);

        assertThat(SorteoGrupos.repartir(SEMILLA, ids, 4)).isEqualTo(SorteoGrupos.repartir(SEMILLA, ids, 4));
        assertThat(SorteoGrupos.repartir("otra", ids, 4)).isNotEqualTo(SorteoGrupos.repartir(SEMILLA, ids, 4));
    }

    @Test
    void elOrdenEnQueVienenLasInscripcionesNoImporta() {
        List<Long> ids = ids(12);

        assertThat(SorteoGrupos.repartir(SEMILLA, ids.reversed(), 4)).isEqualTo(SorteoGrupos.repartir(SEMILLA, ids, 4));
    }

    @Test
    void sinElCupoLlenoLosGruposDifierenEnUnoComoMucho() {
        List<List<Long>> grupos = SorteoGrupos.repartir(SEMILLA, ids(14), 4);

        // A B C D D C B A A B C D D C: la vuelta incompleta es de los últimos
        assertThat(grupos).extracting(List::size).containsExactly(3, 3, 4, 4);
        assertThat(grupos.stream().flatMap(List::stream)).doesNotHaveDuplicates().hasSize(14);
    }

    @Test
    void lasLetrasYElCompromiso() {
        assertThat(SorteoGrupos.letra(0)).isEqualTo("A");
        assertThat(SorteoGrupos.letra(7)).isEqualTo("H");
        assertThat(SorteoGrupos.compromiso("abc", 42)).isEqualTo(SelloTorneo.sha256("abc:42"));
    }

    private static List<Long> ids(int cantidad) {
        return LongStream.rangeClosed(101, 100 + cantidad).boxed().toList();
    }
}
