package com.hydra.pica.plataforma_pica.torneo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** El texto del esquema sellado con fase de grupos (CR-02, PICA-396). */
class SelloTorneoTest {

    @Test
    void conGruposSellaElFormatoLasReglasYElDesempate() {
        Torneo torneo = torneo();
        torneo.setFormato(Formato.GRUPOS_Y_ELIMINACION);
        torneo.setReglasGrupos(new ReglasGrupos(4, 2, 3, 1));

        assertThat(SelloTorneo.textoEsquema(torneo, List.of(), List.of())).isEqualTo("""
                torneo:18
                formato:GRUPOS_Y_ELIMINACION
                grupos:4:2
                puntos:3:1
                desempate:puntos,entre-si,ganados,partido
                inscripcion:20000.00 ARS
                gastos-administrativos:10.00""");
    }

    @Test
    void enEliminacionDirectaElTextoNoCambia() {
        assertThat(SelloTorneo.textoEsquema(torneo(), List.of(), List.of())).isEqualTo("""
                torneo:18
                inscripcion:20000.00 ARS
                gastos-administrativos:10.00""");
    }

    @Test
    void laLineaDeUnEquipoConRepartoLlevaElPorcentajeDeCadaIntegranteOrdenadoPorUsuario() {
        Map<Long, BigDecimal> reparto = new LinkedHashMap<>();
        reparto.put(12L, new BigDecimal("40"));
        reparto.put(7L, new BigDecimal("40.0"));
        reparto.put(9L, new BigDecimal("20.00"));

        assertThat(SelloTorneo.lineaDeEquipoConReparto(32L, 4L, reparto)).isEqualTo("32;E;4;7:40.00,9:20.00,12:40.00");
    }

    @Test
    void laLineaDeUnEquipoSinRepartoConservaElFormatoDeAntesYLasIndividualesNoCambian() {
        assertThat(SelloTorneo.lineaDeEquipo(32L, 4L, List.of(12L, 7L, 9L))).isEqualTo("32;E;4;7,9,12");
        assertThat(SelloTorneo.lineaIndividual(31L, 7L)).isEqualTo("31;U;7");
    }

    private static Torneo torneo() {
        Torneo torneo = new Torneo();
        ReflectionTestUtils.setField(torneo, "id", 18L);
        torneo.setValorInscripcion(new BigDecimal("20000"));
        torneo.setPctGastosAdministrativos(BigDecimal.TEN);
        return torneo;
    }
}
