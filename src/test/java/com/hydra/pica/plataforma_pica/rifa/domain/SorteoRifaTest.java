package com.hydra.pica.plataforma_pica.rifa.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    // --- CR-06: nadie gana dos veces -----------------------------------------------------------------

    @Test
    void conLaVersion1LosGanadoresSonLosPrimerosDelOrdenAunqueSeanDelMismoComprador() {
        Map<Integer, String> todosDeUno = VENDIDOS.stream().collect(Collectors.toMap(n -> n, n -> "C1"));

        assertThat(SorteoRifa.ganadores(1, SEMILLA, VENDIDOS, todosDeUno, 3))
                .isEqualTo(SorteoRifa.orden(SEMILLA, VENDIDOS).subList(0, 3));
    }

    @Test
    void conLaVersion2SeSalteaAlCompradorQueYaGano() {
        List<Integer> orden = SorteoRifa.orden(SEMILLA, VENDIDOS);
        // el primero y el segundo del orden son del mismo comprador; el resto, de otros dos
        Map<Integer, String> compradores = new HashMap<>();
        compradores.put(orden.get(0), "C1");
        compradores.put(orden.get(1), "C1");
        compradores.put(orden.get(2), "C2");
        orden.subList(3, orden.size()).forEach(n -> compradores.put(n, "C3"));

        assertThat(SorteoRifa.ganadores(2, SEMILLA, VENDIDOS, compradores, 3))
                .containsExactly(orden.get(0), orden.get(2), orden.get(3));
    }

    @Test
    void conLaVersion2SiHayMasPremiosQueCompradoresLosQueSobranQuedanSinGanador() {
        Map<Integer, String> dos = VENDIDOS.stream().collect(Collectors.toMap(n -> n, n -> n < 40 ? "C1" : "C2"));

        assertThat(SorteoRifa.ganadores(2, SEMILLA, VENDIDOS, dos, 5)).hasSize(2)
                .extracting(dos::get).containsExactlyInAnyOrder("C1", "C2");
    }

    @Test
    void conMasPremiosQueVendidosLosQueSobranQuedanSinGanador() {
        Map<Integer, String> cadaUno = VENDIDOS.stream().collect(Collectors.toMap(n -> n, n -> "C" + n));

        assertThat(SorteoRifa.ganadores(2, SEMILLA, VENDIDOS, cadaUno, 20)).hasSize(VENDIDOS.size());
    }
}
