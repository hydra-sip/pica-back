package com.hydra.pica.plataforma_pica.common.error;

/**
 * Códigos de error que devuelve la API en el campo {@code codigo} del ProblemDetail.
 * Es la misma lista que {@code CodigoError} en docs/api/openapi.yaml: si se agrega uno acá,
 * se agrega allá también, porque el front mapea cada código a un mensaje.
 */
public enum CodigoError {

    // Genéricos
    VALIDACION,
    NO_AUTENTICADO,
    TOKEN_INVALIDO,
    TOKEN_VENCIDO,
    SIN_PERMISO,
    ERROR_INTERNO,

    // 404
    USUARIO_NO_ENCONTRADO,
    PERSONA_NO_ENCONTRADA,
    ROL_NO_ENCONTRADO,
    PERMISO_NO_ENCONTRADO,

    // Auth
    CREDENCIALES_INVALIDAS,
    EMAIL_NO_VERIFICADO,
    USUARIO_BLOQUEADO,
    REFRESH_INVALIDO,
    REFRESH_REUTILIZADO,
    TOKEN_EXPIRADO,
    TOKEN_USADO,
    CODIGO_INVALIDO,
    PASSWORD_ACTUAL_INCORRECTA,

    // Reglas de negocio (409)
    USERNAME_DUPLICADO,
    EMAIL_DUPLICADO,
    DOCUMENTO_DUPLICADO,
    ROL_DUPLICADO,
    PERSONA_INACTIVA,
    PERSONA_CON_USUARIO,
    DOCUMENTO_NO_EDITABLE,
    ROL_INACTIVO,
    USUARIO_NO_ACTIVO,

    // Protecciones (403)
    USUARIO_PROTEGIDO,
    ROL_PROTEGIDO,
    ULTIMO_ASIGNADOR
}
