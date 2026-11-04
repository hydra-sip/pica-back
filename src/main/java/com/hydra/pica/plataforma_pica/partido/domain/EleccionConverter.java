package com.hydra.pica.plataforma_pica.partido.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class EleccionConverter implements AttributeConverter<Eleccion, String> {

    @Override
    public String convertToDatabaseColumn(Eleccion eleccion) {
        return eleccion == null ? null : eleccion.getCodigo();
    }

    @Override
    public Eleccion convertToEntityAttribute(String codigo) {
        return codigo == null ? null : Eleccion.deCodigo(codigo);
    }
}
