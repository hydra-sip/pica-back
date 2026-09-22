package com.hydra.pica.plataforma_pica.user.domain;

/**
 * Tipos de documento aceptados. Coincide con el enum {@code TipoDoc} del contrato OpenAPI.
 * {@link Persona#getTipoDoc()} lo guarda como texto, así que al persistir se usa {@code name()}
 * y el orden acá no importa.
 */
public enum TipoDoc {
    DNI,
    LC,
    LE,
    CI,
    PASAPORTE
}
