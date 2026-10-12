package com.hydra.pica.plataforma_pica.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.hydra.pica.plataforma_pica.common.config.SesionConfig.SesionProperties;
import org.junit.jupiter.api.Test;

class SesionPropertiesTest {

    @Test
    void losValoresQueFaltanTomanElDefault() {
        var sesion = new SesionProperties(null, Duration.ofHours(2), null);

        assertThat(sesion.accessTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(sesion.refreshTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(sesion.sesionMaxima()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void rechazaDuracionesEnCeroONegativas() {
        assertThatThrownBy(() -> new SesionProperties(Duration.ZERO, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.auth.access-ttl");
        assertThatThrownBy(() -> new SesionProperties(null, Duration.ofHours(-1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.auth.refresh-ttl");
        assertThatThrownBy(() -> new SesionProperties(null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.auth.sesion-maxima");
    }

    @Test
    void rechazaUnMaximoMenorQueLaInactividad() {
        // el típico 7h en lugar de 7d, con la inactividad de 24 h por defecto
        assertThatThrownBy(() -> new SesionProperties(null, null, Duration.ofHours(7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.auth.sesion-maxima")
                .hasMessageContaining("app.auth.refresh-ttl");
    }

    @Test
    void aceptaUnMaximoIgualALaInactividad() {
        var sesion = new SesionProperties(null, Duration.ofHours(24), Duration.ofHours(24));

        assertThat(sesion.sesionMaxima()).isEqualTo(sesion.refreshTtl());
    }
}
