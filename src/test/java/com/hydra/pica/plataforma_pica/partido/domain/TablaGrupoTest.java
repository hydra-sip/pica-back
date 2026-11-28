package com.hydra.pica.plataforma_pica.partido.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.hydra.pica.plataforma_pica.partido.domain.TablaGrupo.Criterio;
import com.hydra.pica.plataforma_pica.partido.domain.TablaGrupo.Fila;
import com.hydra.pica.plataforma_pica.partido.domain.TablaGrupo.Resultado;
import com.hydra.pica.plataforma_pica.partido.domain.TablaGrupo.Tabla;
import org.junit.jupiter.api.Test;

/**
 * La tabla y la cascada de desempate (CR-02-5, PICA-399), en un grupo de 4 (A=1, B=2, C=3, D=4) donde pasan 2, con 3
 * puntos por ganar y 1 por empatar.
 */
class TablaGrupoTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long D = 4;
    private static final List<Long> GRUPO = List.of(A, B, C, D);

    private final List<Resultado> resultados = new ArrayList<>();

    @Test
    void sinEmpatesOrdenaPorPuntosYPasanLosDosPrimeros() {
        gana(A, B, 2, 0);
        gana(A, C, 1, 0);
        gana(A, D, 3, 1);
        gana(B, C, 2, 1);
        gana(B, D, 1, 0);
        gana(C, D, 4, 0);

        Tabla tabla = calcular(true);

        assertThat(tabla.filas()).extracting(Fila::inscripcionId).containsExactly(A, B, C, D);
        assertThat(tabla.filas()).extracting(Fila::puntos).containsExactly(9, 6, 3, 0);
        assertThat(tabla.filas()).extracting(Fila::criterio).containsOnly(Criterio.PUNTOS);
        assertThat(tabla.filas()).extracting(Fila::clasifica).containsExactly(true, true, false, false);
        assertThat(tabla.desempatesPendientes()).isEmpty();
        Fila a = tabla.filas().getFirst();
        assertThat(List.of(a.jugados(), a.ganados(), a.empatados(), a.perdidos(), a.aFavor(), a.enContra(),
                a.diferencia())).containsExactly(3, 3, 0, 0, 6, 1, 5);
    }

    @Test
    void dosIgualadosEnPuntosLosSeparaElResultadoEntreSi() {
        gana(A, C, 1, 0);
        gana(B, A, 2, 1); // B le ganó a A
        gana(A, D, 1, 0);
        gana(C, B, 1, 0);
        gana(B, D, 1, 0);
        gana(D, C, 1, 0);

        Tabla tabla = calcular(true);

        // A y B con 6: pasa primero B por entre sí
        assertThat(tabla.filas()).extracting(Fila::inscripcionId).startsWith(B, A);
        assertThat(tabla.filas().subList(0, 2)).extracting(Fila::criterio).containsOnly(Criterio.ENTRE_SI);
        assertThat(tabla.desempatesPendientes()).isEmpty();
    }

    @Test
    void unTripleEmpateCircularQuedaParaElPartidoDeDesempate() {
        empata(A, B, 0, 0);
        gana(A, C, 1, 0);
        gana(D, A, 1, 0);   // A: 1G 1E 1P = 4
        empata(B, C, 1, 1);
        empata(B, D, 2, 2); // B: 3E = 3
        gana(C, D, 1, 0);   // C: 1G 1E 1P = 4; D: 1G 1E 1P = 4

        Tabla tabla = calcular(true);

        // A, C y D con 4: entre sí A le ganó a C, C a D y D a A, y los tres ganaron uno
        assertThat(tabla.filas()).extracting(Fila::posicion).containsExactly(1, 1, 1, 4);
        assertThat(tabla.filas().subList(0, 3)).extracting(Fila::criterio).containsOnly(Criterio.EMPATE);
        assertThat(tabla.desempatesPendientes()).containsExactly(List.of(A, C, D));
        assertThat(tabla.filas()).extracting(Fila::clasifica).containsOnly(false);
    }

    @Test
    void conIgualPuntajeLosSeparaElResultadoEntreSiAunqueNoSeanLosPrimeros() {
        empata(A, B, 1, 1);
        gana(A, C, 2, 0);
        gana(D, A, 1, 0);   // A: 1G 1E 1P = 4
        gana(B, C, 1, 0);
        gana(B, D, 1, 0);   // B: 2G 1E = 7
        empata(C, D, 0, 0); // C: 1E 2P = 1; D: 1G 1E 1P = 4

        Tabla tabla = calcular(true);

        // A y D con 4: D le ganó a A
        assertThat(tabla.filas()).extracting(Fila::inscripcionId).containsExactly(B, D, A, C);
        assertThat(tabla.filas().get(1).criterio()).isEqualTo(Criterio.ENTRE_SI);
    }

    @Test
    void siEmpataronEntreSiLosSeparanLosPartidosGanados() {
        // 2 por ganar y 1 por empatar
        empata(A, B, 0, 0);
        gana(A, C, 1, 0);
        gana(D, A, 1, 0);   // A: 1G 1E 1P = 3
        empata(B, C, 1, 1);
        empata(B, D, 2, 2); // B: 3E = 3
        gana(D, C, 1, 0);   // D: 2G 1E = 5; C: 1E 2P = 1

        Tabla tabla = TablaGrupo.calcular(GRUPO, resultados, 2, 1, 2, true, Map.of());

        // A y B con 3, empataron entre sí: A ganó uno y B ninguno
        assertThat(tabla.filas()).extracting(Fila::inscripcionId).containsExactly(D, A, B, C);
        assertThat(tabla.filas().subList(1, 3)).extracting(Fila::criterio).containsOnly(Criterio.GANADOS);
        assertThat(tabla.desempatesPendientes()).isEmpty();
    }

    @Test
    void elPartidoDeDesempateDefineLoQueNadaMasDefine() {
        circularEntreABYC();

        Tabla sinJugar = calcular(true);
        Tabla jugado = TablaGrupo.calcular(GRUPO, resultados, 3, 1, 2, true, Map.of(Set.of(A, B, C), List.of(C, A, B)));

        assertThat(sinJugar.desempatesPendientes()).containsExactly(List.of(A, B, C));
        assertThat(jugado.filas()).extracting(Fila::inscripcionId).containsExactly(C, A, B, D);
        assertThat(jugado.filas().subList(0, 3)).extracting(Fila::criterio).containsOnly(Criterio.DESEMPATE);
        assertThat(jugado.filas()).extracting(Fila::clasifica).containsExactly(true, true, false, false);
        assertThat(jugado.desempatesPendientes()).isEmpty();
    }

    @Test
    void conElGrupoSinTerminarNoHayDesempatesPendientes() {
        circularEntreABYC();

        assertThat(calcular(false).desempatesPendientes()).isEmpty();
    }

    @Test
    void unEmpateQueNoTocaLosQuePasanNoSeJuega() {
        gana(A, B, 1, 0);
        gana(A, C, 1, 0);
        gana(A, D, 1, 0);
        gana(B, C, 1, 0);
        gana(B, D, 1, 0);
        empata(C, D, 0, 0);

        Tabla tabla = calcular(true);

        assertThat(tabla.filas()).extracting(Fila::posicion).containsExactly(1, 2, 3, 3);
        assertThat(tabla.desempatesPendientes()).isEmpty();
    }

    @Test
    void dosIgualadosArribaPasanLosDosPeroElOrdenSeJuega() {
        empata(A, B, 1, 1);
        gana(A, C, 1, 0);
        gana(A, D, 1, 0);
        gana(B, C, 1, 0);
        gana(B, D, 1, 0);
        gana(C, D, 1, 0);

        Tabla tabla = calcular(true);

        assertThat(tabla.filas().subList(0, 2)).extracting(Fila::clasifica).containsOnly(true);
        assertThat(tabla.desempatesPendientes()).containsExactly(List.of(A, B));
    }

    @Test
    void elWalkoverSumaLaVictoriaSinTantosYSinPresentesNadieSuma() {
        resultados.add(new Resultado(A, B, null, null, true, false));
        resultados.add(new Resultado(C, D, null, null, false, false));

        Tabla tabla = calcular(false);

        Fila a = fila(tabla, A);
        assertThat(List.of(a.puntos(), a.ganados(), a.aFavor())).containsExactly(3, 1, 0);
        assertThat(fila(tabla, C).puntos()).isZero();
        assertThat(fila(tabla, C).perdidos()).isEqualTo(1);
        assertThat(fila(tabla, D).perdidos()).isEqualTo(1);
    }

    // --- datos ----------------------------------------------------------------

    /** A le gana a B, B a C y C a A, y los tres a D: 6 puntos cada uno. */
    private void circularEntreABYC() {
        gana(A, B, 1, 0);
        gana(B, C, 1, 0);
        gana(C, A, 1, 0);
        gana(A, D, 1, 0);
        gana(B, D, 1, 0);
        gana(C, D, 1, 0);
    }

    private Tabla calcular(boolean terminado) {
        return TablaGrupo.calcular(GRUPO, resultados, 3, 1, 2, terminado, Map.of());
    }

    private static Fila fila(Tabla tabla, long id) {
        return tabla.filas().stream().filter(f -> f.inscripcionId() == id).findFirst().orElseThrow();
    }

    private void gana(long ganador, long perdedor, int tantosGanador, int tantosPerdedor) {
        resultados.add(new Resultado(ganador, perdedor, tantosGanador, tantosPerdedor, true, false));
    }

    private void empata(long local, long visitante, int tantos, int igual) {
        resultados.add(new Resultado(local, visitante, tantos, igual, false, false));
    }
}
