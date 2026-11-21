package com.hydra.pica.plataforma_pica.rifa.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EstadoRifaTest {

    @Test
    @DisplayName("Los predicados de ciclo de vida particionan exactamente todos los estados de EstadoRifa")
    void predicadosParticionanTodosLosEstados() {
        assertThat(EstadoRifa.BORRADOR.esPreSorteo()).isTrue();
        assertThat(EstadoRifa.EN_VENTA.esPreSorteo()).isTrue();
        assertThat(EstadoRifa.CERRADA.esPreSorteo()).isTrue();

        assertThat(EstadoRifa.SORTEADA.admiteReclamos()).isTrue();
        assertThat(EstadoRifa.EN_REVISION.admiteReclamos()).isTrue();

        assertThat(EstadoRifa.LIQUIDADA.esTerminal()).isTrue();
        assertThat(EstadoRifa.CANCELADA.esTerminal()).isTrue();

        for (EstadoRifa estado : EstadoRifa.values()) {
            int activos = (estado.esPreSorteo() ? 1 : 0)
                    + (estado.admiteReclamos() ? 1 : 0)
                    + (estado.esTerminal() ? 1 : 0);
            assertThat(activos)
                    .as("El estado %s debe pertenecer exactamente a una categoría", estado)
                    .isEqualTo(1);
        }
    }
}
