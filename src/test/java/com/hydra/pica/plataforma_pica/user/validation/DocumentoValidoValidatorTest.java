package com.hydra.pica.plataforma_pica.user.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentoValidoValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @DocumentoValido
    private record Pedido(TipoDoc tipoDoc, String nroDoc) implements ConDocumento {
    }

    private boolean esValido(TipoDoc tipo, String nro) {
        return validator.validate(new Pedido(tipo, nro)).isEmpty();
    }

    @Test
    @DisplayName("DNI, LC y LE aceptan solo dígitos, 7 u 8")
    void documentosNumericos() {
        for (TipoDoc tipo : new TipoDoc[] {TipoDoc.DNI, TipoDoc.LC, TipoDoc.LE}) {
            assertThat(esValido(tipo, "30123456")).as(tipo + " con 8 dígitos").isTrue();
            assertThat(esValido(tipo, "3012345")).as(tipo + " con 7 dígitos").isTrue();
            assertThat(esValido(tipo, "301234")).as(tipo + " con 6 dígitos").isFalse();
            assertThat(esValido(tipo, "301234567")).as(tipo + " con 9 dígitos").isFalse();
            assertThat(esValido(tipo, "ABCDE")).as(tipo + " con letras").isFalse();
            assertThat(esValido(tipo, "3012345A")).as(tipo + " con una letra al final").isFalse();
            assertThat(esValido(tipo, "A3012345")).as(tipo + " con una letra al principio").isFalse();
            assertThat(esValido(tipo, "30 123456")).as(tipo + " con espacio").isFalse();
        }
    }

    @Test
    @DisplayName("PASAPORTE y CI aceptan letras y dígitos, pero al menos un dígito")
    void documentosAlfanumericos() {
        for (TipoDoc tipo : new TipoDoc[] {TipoDoc.PASAPORTE, TipoDoc.CI}) {
            assertThat(esValido(tipo, "AAB123456")).as(tipo + " letras al principio").isTrue();
            assertThat(esValido(tipo, "12345678K")).as(tipo + " letra al final").isTrue();
            assertThat(esValido(tipo, "12345678")).as(tipo + " solo dígitos").isTrue();
            assertThat(esValido(tipo, "ABCDEFGH")).as(tipo + " sin ningún dígito").isFalse();
            assertThat(esValido(tipo, "AAB-12345")).as(tipo + " con guion").isFalse();
        }
    }

    @Test
    @DisplayName("El error sale en el campo nroDoc")
    void elErrorEstaEnNroDoc() {
        Set<ConstraintViolation<Pedido>> errores = validator.validate(new Pedido(TipoDoc.DNI, "ABCDE"));

        assertThat(errores).hasSize(1);
        ConstraintViolation<Pedido> error = errores.iterator().next();
        assertThat(error.getPropertyPath().toString()).isEqualTo("nroDoc");
        assertThat(error.getMessage()).contains("DNI");
    }

    @Test
    @DisplayName("Si falta el tipo, el número o viene en blanco, no opina (lo resuelven otras reglas)")
    void sinDatosNoOpina() {
        assertThat(esValido(null, "ABCDE")).isTrue();
        assertThat(esValido(TipoDoc.DNI, null)).isTrue();
        assertThat(esValido(TipoDoc.DNI, "")).isTrue();
        assertThat(esValido(TipoDoc.DNI, "   ")).isTrue();
    }
}
