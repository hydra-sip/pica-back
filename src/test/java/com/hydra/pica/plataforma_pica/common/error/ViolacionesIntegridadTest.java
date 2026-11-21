package com.hydra.pica.plataforma_pica.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ViolacionesIntegridadTest {

    @Test
    @DisplayName("nombreDeConstraint extrae la constraint incluso cuando está anidada")
    void extraeConstraintAnidada() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("error",
                new RuntimeException("wrapper",
                        new ConstraintViolationException("dup", new SQLException(), "uq_usuario_email_lower")));

        assertThat(ViolacionesIntegridad.nombreDeConstraint(ex)).contains("uq_usuario_email_lower");
        assertThat(ViolacionesIntegridad.esConstraint(ex, "uq_usuario_email_lower")).isTrue();
        assertThat(ViolacionesIntegridad.esConstraint(ex, "UQ_USUARIO_EMAIL_LOWER")).isFalse();
    }

    @Test
    @DisplayName("Devuelve vacío cuando no hay ConstraintViolationException o su nombre es nulo")
    void sinConstraintONombreNulo() {
        DataIntegrityViolationException sinCausa = new DataIntegrityViolationException("otro error");
        DataIntegrityViolationException nombreNulo = new DataIntegrityViolationException("error",
                new ConstraintViolationException("dup", new SQLException(), null));

        assertThat(ViolacionesIntegridad.nombreDeConstraint(sinCausa)).isEmpty();
        assertThat(ViolacionesIntegridad.nombreDeConstraint(nombreNulo)).isEmpty();
        assertThat(ViolacionesIntegridad.esConstraint(sinCausa, "uq_x")).isFalse();
    }

    @Test
    @DisplayName("esConstraintOMensaje coincide ignorando mayúsculas o por texto del mensaje")
    void esConstraintOMensaje() {
        DataIntegrityViolationException porNombre = new DataIntegrityViolationException("error",
                new ConstraintViolationException("dup", new SQLException(), "UQ_MOVIMIENTO_REFERENCIA_EXTERNA"));
        DataIntegrityViolationException porMensaje = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint uq_movimiento_referencia_externa");

        assertThat(ViolacionesIntegridad.esConstraintOMensaje(porNombre, "uq_movimiento_referencia_externa")).isTrue();
        assertThat(ViolacionesIntegridad.esConstraintOMensaje(porMensaje, "uq_movimiento_referencia_externa")).isTrue();
        assertThat(ViolacionesIntegridad.esConstraintOMensaje(porMensaje, "otra_constraint")).isFalse();
    }
}
