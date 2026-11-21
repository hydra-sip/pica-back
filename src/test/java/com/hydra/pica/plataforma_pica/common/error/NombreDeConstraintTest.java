package com.hydra.pica.plataforma_pica.common.error;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class NombreDeConstraintTest {

    @Test
    @DisplayName("Encuentra la constraint aunque la causa de Hibernate venga envuelta en otra excepción")
    void encuentraLaConstraintEnvuelta() {
        var hibernate = new ConstraintViolationException("dup", new SQLException(), "uq_rol_nombre");
        var e = new DataIntegrityViolationException("dup", new RuntimeException("envoltorio", hibernate));

        assertThat(NombreDeConstraint.de(e)).isEqualTo("uq_rol_nombre");
    }

    @Test
    @DisplayName("Sin una causa de Hibernate devuelve null")
    void sinCausaDeHibernateDevuelveNull() {
        var e = new DataIntegrityViolationException("dup", new SQLException("otra cosa"));

        assertThat(NombreDeConstraint.de(e)).isNull();
    }

    @Test
    @DisplayName("Si la causa de Hibernate no trae nombre devuelve null")
    void causaSinNombreDevuelveNull() {
        var e = new DataIntegrityViolationException("dup",
                new ConstraintViolationException("dup", new SQLException(), null));

        assertThat(NombreDeConstraint.de(e)).isNull();
    }
}
