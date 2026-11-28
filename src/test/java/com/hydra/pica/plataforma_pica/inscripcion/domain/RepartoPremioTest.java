package com.hydra.pica.plataforma_pica.inscripcion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RepartoPremioTest {

    @Test
    void dosIntegrantesSePartenPorLaMitad() {
        Map<Long, BigDecimal> reparto = RepartoPremio.partesIguales(List.of(7L, 9L), 7L);

        assertThat(reparto).containsEntry(7L, new BigDecimal("50.00")).containsEntry(9L, new BigDecimal("50.00"));
    }

    @Test
    void tresIntegrantesDejanElSobranteAlCapitan() {
        Map<Long, BigDecimal> reparto = RepartoPremio.partesIguales(List.of(7L, 9L, 12L), 7L);

        assertThat(reparto).containsEntry(7L, new BigDecimal("33.34"))
                .containsEntry(9L, new BigDecimal("33.33"))
                .containsEntry(12L, new BigDecimal("33.33"));
    }

    @Test
    void cincoIntegrantesDanExacto() {
        Map<Long, BigDecimal> reparto = RepartoPremio.partesIguales(List.of(1L, 2L, 3L, 4L, 5L), 3L);

        assertThat(reparto.values()).allMatch(pct -> pct.compareTo(new BigDecimal("20")) == 0);
    }

    @Test
    void sieteIntegrantesDejanElSobranteAlCapitanAunqueNoSeaElPrimeroPorId() {
        Map<Long, BigDecimal> reparto = RepartoPremio.partesIguales(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), 5L);

        assertThat(reparto).containsEntry(5L, new BigDecimal("14.32")).containsEntry(1L, new BigDecimal("14.28"));
        assertThat(reparto.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100");
    }

    @Test
    void sinElCapitanEnLaNominaFalla() {
        assertThatThrownBy(() -> RepartoPremio.partesIguales(List.of(1L, 2L), 9L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
