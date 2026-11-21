package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.LadoBracket;
import org.junit.jupiter.api.Test;

class PartidosTest {

    private static final LadoBracket LOCAL = new LadoBracket(1L, "Local", null, null, false);
    private static final LadoBracket VISITANTE = new LadoBracket(2L, "Visitante", null, null, false);

    @Test
    void esByeEnPrimeraRondaConSoloLocal() {
        assertThat(Partidos.esBye(partidoEnRonda(1), LOCAL, null)).isTrue();
    }

    @Test
    void noEsByeEnPrimeraRondaConAmbosLados() {
        assertThat(Partidos.esBye(partidoEnRonda(1), LOCAL, VISITANTE)).isFalse();
    }

    @Test
    void noEsByeEnRondaPosteriorAunqueFalteVisitante() {
        assertThat(Partidos.esBye(partidoEnRonda(2), LOCAL, null)).isFalse();
    }

    @Test
    void noEsByeSinLadoLocal() {
        assertThat(Partidos.esBye(partidoEnRonda(1), null, null)).isFalse();
    }

    private static Partido partidoEnRonda(int ronda) {
        Partido partido = new Partido();
        partido.setRonda(ronda);
        return partido;
    }
}
