package com.hydra.pica.plataforma_pica.persona;

/**
 * Tipos de documento aceptados. Coincide con el enum {@code TipoDoc} del contrato OpenAPI.
 * En la base se guarda como texto ({@code @Enumerated(EnumType.STRING)}), así que el orden acá no importa.
 */
public enum TipoDoc {
    DNI,
    LC,
    LE,
    CI,
    PASAPORTE
}
