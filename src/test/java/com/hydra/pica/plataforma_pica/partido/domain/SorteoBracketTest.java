package com.hydra.pica.plataforma_pica.partido.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.LongStream;

import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket.Cruce;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket.Cuadro;
import org.junit.jupiter.api.Test;

/** El sorteo del bracket (PICA-215), sin base: cuadro, BYE y que se pueda recalcular. */
class SorteoBracketTest {

    private static final String SEMILLA = "a".repeat(64);

    @Test
    void conOchoNoHayByeYSonTresRondas() {
        Cuadro cuadro = SorteoBracket.sortear(SEMILLA, ids(8));

        assertThat(cuadro.rondas()).isEqualTo(3);
        assertThat(cuadro.cruces()).hasSize(4).noneMatch(Cruce::bye);
        assertThat(List.of(cuadro.partidosDeLaRonda(1), cuadro.partidosDeLaRonda(2), cuadro.partidosDeLaRonda(3)))
                .containsExactly(4, 2, 1);
        assertThat(participantes(cuadro)).containsExactlyInAnyOrderElementsOf(ids(8));
    }

    @Test
    void conOnceHayCincoByeSoloEnLaPrimeraRondaYCadaUnoUnaVez() {
        Cuadro cuadro = SorteoBracket.sortear(SEMILLA, ids(11));

        assertThat(cuadro.rondas()).isEqualTo(4);
        assertThat(cuadro.cruces()).hasSize(8);
        assertThat(cuadro.cruces().stream().filter(Cruce::bye)).hasSize(5);
        assertThat(participantes(cuadro)).containsExactlyInAnyOrderElementsOf(ids(11));
        // primero las impares: cada BYE de esas va a un partido distinto de la segunda ronda
        assertThat(cuadro.cruces().stream().filter(Cruce::bye).map(Cruce::posicion))
                .containsExactly(1, 2, 3, 5, 7);
    }

    @Test
    void losByeSonLosPrimerosDelSorteoYNoLosPrimerosInscriptos() {
        List<Long> inscriptos = ids(11);
        List<Long> orden = inscriptos.stream()
                .sorted(Comparator.comparing((Long id) -> sha256(SEMILLA + ":" + id)))
                .toList();

        Cuadro cuadro = SorteoBracket.sortear(SEMILLA, inscriptos);

        assertThat(cuadro.cruces().stream().filter(Cruce::bye).map(Cruce::local))
                .containsExactlyInAnyOrderElementsOf(orden.subList(0, 5))
                .isNotEqualTo(inscriptos.subList(0, 5));
    }

    @Test
    void conLaMismaSemillaSaleElMismoCuadroYConOtraNo() {
        Cuadro uno = SorteoBracket.sortear(SEMILLA, ids(16));

        assertThat(SorteoBracket.sortear(SEMILLA, ids(16))).isEqualTo(uno);
        assertThat(SorteoBracket.sortear("b".repeat(64), ids(16))).isNotEqualTo(uno);
    }

    @Test
    void conDosEsLaFinalYConUnoNoSeSortea() {
        Cuadro final_ = SorteoBracket.sortear(SEMILLA, ids(2));

        assertThat(final_.rondas()).isEqualTo(1);
        assertThat(final_.cruces()).singleElement().matches(c -> !c.bye());
        assertThatThrownBy(() -> SorteoBracket.sortear(SEMILLA, ids(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elGanadorSigueALaMitadDeLaPosicionYLosImparesComoLocal() {
        assertThat(SorteoBracket.posicionSiguiente(1)).isEqualTo(1);
        assertThat(SorteoBracket.posicionSiguiente(2)).isEqualTo(1);
        assertThat(SorteoBracket.posicionSiguiente(7)).isEqualTo(4);
        assertThat(SorteoBracket.ladoSiguiente(3)).isEqualTo(Lado.LOCAL);
        assertThat(SorteoBracket.ladoSiguiente(4)).isEqualTo(Lado.VISITANTE);
    }

    @Test
    void laSemillaSonTreintaYDosBytesEnHexadecimal() {
        assertThat(SorteoBracket.nuevaSemilla()).hasSize(64).matches("[0-9a-f]+")
                .isNotEqualTo(SorteoBracket.nuevaSemilla());
    }

    private static List<Long> ids(int cantidad) {
        return LongStream.rangeClosed(101, 100 + cantidad).boxed().toList();
    }

    private static List<Long> participantes(Cuadro cuadro) {
        List<Long> todos = new ArrayList<>();
        cuadro.cruces().forEach(c -> {
            todos.add(c.local());
            if (c.visitante() != null) {
                todos.add(c.visitante());
            }
        });
        return todos;
    }

    private static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
