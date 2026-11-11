package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.hydra.pica.plataforma_pica.billetera.service.RepartoPorPuesto.Cobro;
import com.hydra.pica.plataforma_pica.billetera.service.RepartoPorPuesto.Posicionado;
import org.junit.jupiter.api.Test;

class RepartoPorPuestoTest {

    @Test
    void cadaPuestoCobraSuPorcentaje() {
        List<Cobro> cobros = RepartoPorPuesto.repartir(new BigDecimal("3500.00"), pct(1, "70", 2, "30"),
                List.of(new Posicionado(10L, 1), new Posicionado(20L, 2), new Posicionado(30L, 3)));

        assertThat(cobros).containsExactly(
                new Cobro(10L, 1, new BigDecimal("2450.00")),
                new Cobro(20L, 2, new BigDecimal("1050.00")));
    }

    @Test
    void losEmpatadosJuntanLosPuestosQueOcupanYLosDividen() {
        // los dos que pierden en semifinales quedan 3.º: se llevan el 3.º y el 4.º, mitad cada uno
        List<Cobro> cobros = RepartoPorPuesto.repartir(new BigDecimal("1000.00"), pct(1, "50", 2, "20", 3, "20", 4, "10"),
                List.of(new Posicionado(4L, 3), new Posicionado(1L, 1), new Posicionado(3L, 3), new Posicionado(2L, 2)));

        assertThat(cobros).containsExactly(
                new Cobro(1L, 1, new BigDecimal("500.00")),
                new Cobro(2L, 2, new BigDecimal("200.00")),
                new Cobro(3L, 3, new BigDecimal("150.00")),
                new Cobro(4L, 3, new BigDecimal("150.00")));
    }

    @Test
    void loDeUnPuestoQueNadieOcupaSeReparteEntreLosQueCobran() {
        // con dos participantes el 3.º no existe: su 10 % se reparte 60 a 30 entre el 1.º y el 2.º
        List<Cobro> cobros = RepartoPorPuesto.repartir(new BigDecimal("900.00"), pct(1, "60", 2, "30", 3, "10"),
                List.of(new Posicionado(1L, 1), new Posicionado(2L, 2)));

        assertThat(cobros).containsExactly(
                new Cobro(1L, 1, new BigDecimal("600.00")),
                new Cobro(2L, 2, new BigDecimal("300.00")));
    }

    @Test
    void losCentavosDelRedondeoVanAlMejorPuesto() {
        List<Cobro> cobros = RepartoPorPuesto.repartir(new BigDecimal("100.00"), pct(1, "34", 2, "33", 3, "33"),
                List.of(new Posicionado(1L, 1), new Posicionado(2L, 2), new Posicionado(3L, 3), new Posicionado(4L, 3)));

        assertThat(cobros).containsExactly(
                new Cobro(1L, 1, new BigDecimal("34.00")),
                new Cobro(2L, 2, new BigDecimal("33.00")),
                new Cobro(3L, 3, new BigDecimal("16.50")),
                new Cobro(4L, 3, new BigDecimal("16.50")));
        assertThat(RepartoPorPuesto.repartir(new BigDecimal("100.00"), pct(1, "50", 2, "50"),
                List.of(new Posicionado(1L, 1), new Posicionado(2L, 2), new Posicionado(3L, 2), new Posicionado(4L, 2))))
                .extracting(Cobro::monto)
                .containsExactly(new BigDecimal("50.02"), new BigDecimal("16.66"), new BigDecimal("16.66"),
                        new BigDecimal("16.66"));
    }

    @Test
    void sinNadieQueCobreNoHayReparto() {
        assertThat(RepartoPorPuesto.repartir(new BigDecimal("500.00"), Map.of(),
                List.of(new Posicionado(1L, 1)))).isEmpty();
        assertThat(RepartoPorPuesto.repartir(new BigDecimal("500.00"), pct(1, "100"), List.of())).isEmpty();
        assertThat(RepartoPorPuesto.repartir(BigDecimal.ZERO, pct(1, "100"), List.of(new Posicionado(1L, 1)))).isEmpty();
    }

    @Test
    void loRepartidoSiempreSumaElPozo() {
        Random azar = new Random(606);
        for (int vuelta = 0; vuelta < 500; vuelta++) {
            BigDecimal pozo = BigDecimal.valueOf(azar.nextInt(10_000_000) + 1, 2);
            int participantes = 1 + azar.nextInt(16);
            List<Posicionado> posicionados = new ArrayList<>();
            for (long i = 1; i <= participantes; i++) {
                // como en una llave: 1, 2, 3, 3, 5, 5, 5, 5...
                int posicion = i == 1 ? 1 : Integer.highestOneBit((int) i - 1) + 1;
                posicionados.add(new Posicionado(i, posicion));
            }
            Map<Integer, BigDecimal> premios = new HashMap<>();
            for (int puesto = 1; puesto <= 1 + azar.nextInt(5); puesto++) {
                premios.put(puesto, BigDecimal.valueOf(1 + azar.nextInt(60)));
            }

            BigDecimal repartido = RepartoPorPuesto.repartir(pozo, premios, posicionados).stream()
                    .map(Cobro::monto).reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(repartido).as("pozo %s, %d participantes, premios %s", pozo, participantes, premios)
                    .isEqualByComparingTo(pozo);
        }
    }

    private static Map<Integer, BigDecimal> pct(Object... puestoYPct) {
        Map<Integer, BigDecimal> pct = new HashMap<>();
        for (int i = 0; i < puestoYPct.length; i += 2) {
            pct.put((Integer) puestoYPct[i], new BigDecimal((String) puestoYPct[i + 1]));
        }
        return pct;
    }
}
