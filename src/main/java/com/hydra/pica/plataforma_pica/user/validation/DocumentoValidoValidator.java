package com.hydra.pica.plataforma_pica.user.validation;

import java.util.regex.Pattern;

import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DocumentoValidoValidator implements ConstraintValidator<DocumentoValido, ConDocumento> {

    private static final Pattern SOLO_DIGITOS = Pattern.compile("^[0-9]+$");
    private static final Pattern ALFANUMERICO_CON_DIGITO = Pattern.compile("^(?=.*[0-9])[0-9A-Za-z]+$");

    @Override
    public boolean isValid(ConDocumento valor, ConstraintValidatorContext context) {
        if (valor == null || valor.tipoDoc() == null || valor.nroDoc() == null || valor.nroDoc().isBlank()) {
            return true;
        }

        TipoDoc tipo = valor.tipoDoc();
        boolean valido = switch (tipo) {
            case DNI, LC, LE -> SOLO_DIGITOS.matcher(valor.nroDoc()).matches();
            case CI, PASAPORTE -> ALFANUMERICO_CON_DIGITO.matcher(valor.nroDoc()).matches();
        };

        if (!valido) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(mensaje(tipo))
                    .addPropertyNode("nroDoc")
                    .addConstraintViolation();
        }
        return valido;
    }

    private static String mensaje(TipoDoc tipo) {
        return switch (tipo) {
            case DNI, LC, LE -> tipo + ": el número lleva solo dígitos";
            case CI, PASAPORTE -> tipo + ": el número es alfanumérico y lleva al menos un dígito";
        };
    }
}
