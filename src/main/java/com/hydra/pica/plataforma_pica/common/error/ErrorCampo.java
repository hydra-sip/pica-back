package com.hydra.pica.plataforma_pica.common.error;

/**
 * Un ítem de la lista {@code errores} en los 400 de validación.
 *
 * @param campo   nombre del campo tal como viene en el JSON ({@code nroDoc}, no {@code nro_doc})
 * @param codigo  qué falló, para que el front elija el mensaje
 * @param mensaje texto de Bean Validation, orientativo
 */
public record ErrorCampo(String campo, Codigo codigo, String mensaje) {

    /**
     * Se deriva de la anotación de Bean Validation que falló. Si aparece una anotación nueva
     * que no está acá, cae en VALOR_INVALIDO; agregarla si el front necesita distinguirla.
     */
    public enum Codigo {
        REQUERIDO,
        FORMATO_INVALIDO,
        LONGITUD,
        FECHA_FUTURA,
        PASSWORD_DEBIL,
        VALOR_INVALIDO;

        /** {@code anotacion} es lo que devuelve {@code FieldError.getCode()}: "NotBlank", "Email", etc. */
        public static Codigo desdeAnotacion(String anotacion) {
            if (anotacion == null) {
                return VALOR_INVALIDO;
            }
            return switch (anotacion) {
                case "NotNull", "NotBlank", "NotEmpty" -> REQUERIDO;
                case "Email", "Pattern", "DocumentoValido" -> FORMATO_INVALIDO;
                case "Size", "Length", "Min", "Max" -> LONGITUD;
                case "Past", "PastOrPresent" -> FECHA_FUTURA;
                case "PasswordValida" -> PASSWORD_DEBIL;
                default -> VALOR_INVALIDO;
            };
        }
    }
}
