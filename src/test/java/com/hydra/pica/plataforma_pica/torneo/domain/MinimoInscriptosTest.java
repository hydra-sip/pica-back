package com.hydra.pica.plataforma_pica.torneo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** El mínimo de inscripciones confirmadas para jugar un torneo (PICA-447). */
class MinimoInscriptosTest {

    @Test
    void enEliminacionDirectaHacenFaltaDosSinImportarElCupo() {
        assertThat(MinimoInscriptos.de(Formato.ELIMINACION_DIRECTA, 16, null)).isEqualTo(2);
        assertThat(MinimoInscriptos.de(Formato.ELIMINACION_DIRECTA, 2, null)).isEqualTo(2);
    }

    @Test
    void conGruposCadaGrupoNecesitaUnoMasQueLosQuePasan() {
        // 16 / 4 = 4 grupos; pasan 2, así que cada uno necesita 3
        ReglasGrupos reglas = new ReglasGrupos(4, 2, 3, 1);

        assertThat(MinimoInscriptos.de(Formato.GRUPOS_Y_ELIMINACION, 16, reglas)).isEqualTo(12);
        // con un grupo menos en el cupo, uno menos de tres
        assertThat(MinimoInscriptos.de(Formato.GRUPOS_Y_ELIMINACION, 15, reglas)).isEqualTo(9);
    }

    @Test
    void losGruposSonLosDelCupoMaximoAunqueNoDivida() {
        // 10 / 3 = 3 grupos; pasa 1, así que cada uno necesita 2
        assertThat(MinimoInscriptos.de(Formato.GRUPOS_Y_ELIMINACION, 10, new ReglasGrupos(3, 1, 3, 1))).isEqualTo(6);
    }

    @Test
    void conElCupoLlenoSiempreSeLlegaAlMinimo() {
        for (int tamanio = ReglasGrupos.TAMANIO_MINIMO; tamanio <= ReglasGrupos.TAMANIO_MAXIMO; tamanio++) {
            for (int pasan = 1; pasan < tamanio; pasan++) {
                ReglasGrupos reglas = new ReglasGrupos(tamanio, pasan, 3, 1);
                assertThat(MinimoInscriptos.de(Formato.GRUPOS_Y_ELIMINACION, 32, reglas)).isLessThanOrEqualTo(32);
            }
        }
    }
}
