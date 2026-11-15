package com.hydra.pica.plataforma_pica.torneo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
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

    private static Torneo torneo() {
        Torneo torneo = new Torneo();
        ReflectionTestUtils.setField(torneo, "id", 18L);
        torneo.setValorInscripcion(new BigDecimal("20000"));
        torneo.setPctGastosAdministrativos(BigDecimal.TEN);
        return torneo;
    }
}
