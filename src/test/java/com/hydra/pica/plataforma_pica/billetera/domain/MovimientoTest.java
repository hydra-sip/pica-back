package com.hydra.pica.plataforma_pica.billetera.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MovimientoTest {

    @Test
    @DisplayName("truncarDescripcion respeta nulo, longitudes dentro del límite y recorta a 255 caracteres")
    void truncarDescripcion() {
        String exacta = "a".repeat(Movimiento.DESCRIPCION_MAX);
        String larga = "b".repeat(300);

        assertThat(Movimiento.truncarDescripcion(null)).isNull();
        assertThat(Movimiento.truncarDescripcion("Premio 1er puesto")).isEqualTo("Premio 1er puesto");
        assertThat(Movimiento.truncarDescripcion(exacta)).isSameAs(exacta);
        assertThat(Movimiento.truncarDescripcion(larga))
                .hasSize(Movimiento.DESCRIPCION_MAX)
                .isEqualTo("b".repeat(Movimiento.DESCRIPCION_MAX));
    }
}
