package com.hydra.pica.plataforma_pica.billetera.domain;

import java.util.Optional;
import java.util.regex.Pattern;

import com.hydra.pica.plataforma_pica.billetera.service.ValidadorCbuCvu;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;

public enum TipoCuenta {

    CBU {
        @Override
        public Optional<ErrorCampo> validarValor(String valor) {
            return validarCbuOCvu(this, valor);
        }
    },
    CVU {
        @Override
        public Optional<ErrorCampo> validarValor(String valor) {
            return validarCbuOCvu(this, valor);
        }
    },
    ALIAS {
        @Override
        public Optional<ErrorCampo> validarValor(String valor) {
            if (valor.length() < ALIAS_LONGITUD_MINIMA || valor.length() > ALIAS_LONGITUD_MAXIMA) {
                return Optional.of(new ErrorCampo("valor", ErrorCampo.Codigo.LONGITUD,
                        "El alias debe tener entre " + ALIAS_LONGITUD_MINIMA + " y " + ALIAS_LONGITUD_MAXIMA + " caracteres"));
            }
            if (!PATRON_ALIAS.matcher(valor).matches()) {
                return Optional.of(new ErrorCampo("valor", ErrorCampo.Codigo.FORMATO_INVALIDO,
                        "El alias solo puede contener letras, números, puntos y guiones"));
            }
            return Optional.empty();
        }
    };

    private static final int ALIAS_LONGITUD_MINIMA = 6;
    private static final int ALIAS_LONGITUD_MAXIMA = 20;
    private static final int CBU_CVU_DIGITOS = 22;
    private static final Pattern PATRON_ALIAS = Pattern.compile("^[a-zA-Z0-9.-]+$");

    /**
     * Valida el formato de {@code valor} (previamente recortado y no vacío) según este tipo de cuenta.
     */
    public abstract Optional<ErrorCampo> validarValor(String valor);

    private static Optional<ErrorCampo> validarCbuOCvu(TipoCuenta tipo, String valor) {
        if (!ValidadorCbuCvu.esValido(valor)) {
            return Optional.of(new ErrorCampo("valor", ErrorCampo.Codigo.FORMATO_INVALIDO,
                    "El " + tipo + " debe tener " + CBU_CVU_DIGITOS + " dígitos válidos con su dígito verificador"));
        }
        return Optional.empty();
    }
}
