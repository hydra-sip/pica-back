package com.hydra.pica.plataforma_pica.persona;

import java.time.LocalDate;

/**
 * Lo que llega de afuera para buscar o crear una persona: el registro manda documento y datos,
 * el login con Google manda solo nombres y apellidos. Es la entrada de
 * {@code PersonaService.buscarOCrear}; no es la entidad ni un DTO de respuesta.
 *
 * Documento nulo (tipo y número) significa "sin documento": en ese caso siempre se crea
 * una persona nueva porque no hay con qué buscar.
 */
public record DatosPersona(
        TipoDoc tipoDoc,
        String nroDoc,
        String nombres,
        String apellidos,
        LocalDate fechaNacimiento,
        String domicilioPostal,
        String telefono
) {

    public DatosPersona {
        // Van los dos o ninguno; un documento a medias no se puede buscar ni guardar.
        if ((tipoDoc == null) != (nroDoc == null || nroDoc.isBlank())) {
            throw new IllegalArgumentException("tipoDoc y nroDoc van juntos: los dos o ninguno");
        }
        if (nroDoc != null) {
            nroDoc = nroDoc.strip();
        }
    }

    public boolean tieneDocumento() {
        return tipoDoc != null;
    }

    /** Caso Google: nombres y apellidos nada más; el resto lo completa después desde Mi perfil. */
    public static DatosPersona sinDocumento(String nombres, String apellidos) {
        return new DatosPersona(null, null, nombres, apellidos, null, null, null);
    }
}
