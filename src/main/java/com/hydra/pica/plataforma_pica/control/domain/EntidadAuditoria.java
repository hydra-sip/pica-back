package com.hydra.pica.plataforma_pica.control.domain;

/**
 * Tipos de entidad registrados en la tabla {@code auditoria} (campo {@code entidad}).
 */
public enum EntidadAuditoria {
    TORNEO,
    PARTIDO,
    RIFA,
    CUSTODIA,
    BILLETERA,
    CARGA_CRIPTO,
    CUENTA_BANCARIA,
    RETIRO,
    RETIRO_CRIPTO,
    USUARIO,
    PARAMETRO,
    RECLAMO_RIFA,
    ALERTA_FRAUDE,
    INSCRIPCION
}
