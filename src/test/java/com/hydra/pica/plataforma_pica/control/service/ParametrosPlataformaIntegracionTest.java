package com.hydra.pica.plataforma_pica.control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class ParametrosPlataformaIntegracionTest {

    @Autowired private ParametrosPlataforma parametrosPlataforma;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void losGastosAdministrativosVienenPreconfiguradosEn10() {
        assertThat(parametrosPlataforma.gastosAdministrativos()).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void elMargenEntreElCierreYElInicioVienePreconfiguradoEnUnaHora() {
        assertThat(parametrosPlataforma.margenCierreInicio()).isEqualTo(Duration.ofHours(1));
    }

    // Los CHECK de la base: el margen son minutos enteros y no negativos, y el porcentaje va de 0 a 100
    @ParameterizedTest(name = "{0} = {1} no entra")
    @CsvSource({
            "MARGEN_CIERRE_INICIO_MIN, 1.5",
            "MARGEN_CIERRE_INICIO_MIN, -1",
            "GASTOS_ADMINISTRATIVOS, 101",
            "GASTOS_ADMINISTRATIVOS, -0.01"
    })
    void unValorFueraDeRangoLoRechazaLaBase(String clave, BigDecimal valor) {
        assertThatThrownBy(() -> jdbc.update("UPDATE parametro_plataforma SET valor = ? WHERE clave = ?", valor, clave))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leeElValorVigente() {
        jdbc.update("UPDATE parametro_plataforma SET valor = 7.5 WHERE clave = 'GASTOS_ADMINISTRATIVOS'");

        assertThat(parametrosPlataforma.gastosAdministrativos()).isEqualByComparingTo("7.5");
    }

    @Test
    void losParametrosDeV35TienenSusRangosEnLaBase() {
        assertThat(parametrosPlataforma.checkinDuracionMaxima()).isEqualTo(Duration.ofHours(1));
        assertThat(parametrosPlataforma.retiroMinimo()).isEqualByComparingTo("1000");
        assertThat(parametrosPlataforma.retiroMaximo()).isEqualByComparingTo("500000");
        for (String invalido : List.of(
                "UPDATE parametro_plataforma SET valor = 101 WHERE clave = 'GASTOS_ADMINISTRATIVOS_TOPE'",
                "UPDATE parametro_plataforma SET valor = 0 WHERE clave = 'CHECKIN_DURACION_MAX_MIN'",
                "UPDATE parametro_plataforma SET valor = 10.5 WHERE clave = 'CHECKIN_DURACION_MAX_MIN'",
                "UPDATE parametro_plataforma SET valor = 0 WHERE clave = 'RETIRO_MINIMO'")) {
            assertThatThrownBy(() -> jdbc.execute("SAVEPOINT v35; " + invalido))
                    .as(invalido)
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.execute("ROLLBACK TO SAVEPOINT v35");
        }
    }

    @Test
    void sinElParametroFallaDiciendoCualFalta() {
        jdbc.update("DELETE FROM parametro_plataforma WHERE clave = 'GASTOS_ADMINISTRATIVOS'");

        assertThatThrownBy(parametrosPlataforma::gastosAdministrativos)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GASTOS_ADMINISTRATIVOS");
    }
}
