package com.hydra.pica.plataforma_pica.partido.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** El fixture todos contra todos de un grupo (CR-02-4, PICA-398). */
class FixtureGruposTest {

    @Test
    void unGrupoDeCuatroSonSeisPartidosEnTresFechasYCadaUnoJuegaTres() {
        List<List<FixtureGrupos.Cruce>> fechas = FixtureGrupos.fechas(ids(4));

        assertThat(fechas).hasSize(3).allSatisfy(f -> assertThat(f).hasSize(2));
        Map<Long, Long> partidos = todos(fechas).stream()
                .flatMap(c -> LongStream.of(c.local(), c.visitante()).boxed())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(partidos).containsOnlyKeys(ids(4)).allSatisfy((id, jugados) -> assertThat(jugados).isEqualTo(3));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 6, 7, 8})
    void cadaParJuegaUnaSolaVezYNadieJuegaDosVecesEnUnaFecha(int integrantes) {
        List<List<FixtureGrupos.Cruce>> fechas = FixtureGrupos.fechas(ids(integrantes));

        Set<Set<Long>> pares = new HashSet<>();
        for (List<FixtureGrupos.Cruce> fecha : fechas) {
            Set<Long> juegan = new HashSet<>();
            for (FixtureGrupos.Cruce cruce : fecha) {
                assertThat(juegan.add(cruce.local())).isTrue();
                assertThat(juegan.add(cruce.visitante())).isTrue();
                assertThat(pares.add(Set.of(cruce.local(), cruce.visitante()))).isTrue();
            }
        }
        assertThat(pares).hasSize(integrantes * (integrantes - 1) / 2);
        assertThat(fechas).hasSize(integrantes % 2 == 0 ? integrantes - 1 : integrantes);
    }

    @Test
    void conTresUnoQuedaLibreCadaFecha() {
        assertThat(FixtureGrupos.fechas(ids(3))).hasSize(3).allSatisfy(f -> assertThat(f).hasSize(1));
    }

    @Test
    void enUnGrupoDeCuatroNadieEsSiempreLocalNiSiempreVisitante() {
        List<FixtureGrupos.Cruce> cruces = todos(FixtureGrupos.fechas(ids(4)));

        for (long id : ids(4)) {
            long local = cruces.stream().filter(c -> c.local() == id).count();
            assertThat(local).as("veces local de %d", id).isBetween(1L, 2L);
        }
    }

    private static List<FixtureGrupos.Cruce> todos(List<List<FixtureGrupos.Cruce>> fechas) {
        return fechas.stream().flatMap(List::stream).toList();
    }

    private static List<Long> ids(int cantidad) {
        return LongStream.rangeClosed(1, cantidad).boxed().toList();
    }
}
