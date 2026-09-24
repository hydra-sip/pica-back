package com.hydra.pica.plataforma_pica.user.dto;

import java.time.LocalDate;

import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.validation.ConDocumento;
import com.hydra.pica.plataforma_pica.user.validation.DocumentoValido;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body de PUT /me (MeUpdateRequest en el contrato). Reemplaza los datos editables de la persona:
 * un campo opcional en null lo borra. El documento es la excepción: solo se carga si la persona no
 * tenía, y si no viene se mantiene el que está (ver {@code PerfilService#actualizar}).
 */
@DocumentoValido
public record MeUpdateRequest(
        @NotBlank
        @Size(max = 100)
        String nombres,
        @NotBlank
        @Size(max = 100)
        String apellidos,
        @PastOrPresent
        LocalDate fechaNacimiento,
        @Size(max = 200)
        String domicilioPostal,
        @Size(max = 30)
        String telefono,
        TipoDoc tipoDoc,
        @Size(min = 5, max = 20)
        @Pattern(regexp = "^[0-9A-Za-z]+$")
        String nroDoc) implements ConDocumento {

    /**
     * Un formulario con el documento sin completar manda "" en vez de null (el caso normal del
     * usuario de Google que todavía no lo carga): se toma como "sin documento" antes de validar.
     */
    public MeUpdateRequest {
        if (nroDoc != null && nroDoc.isBlank()) {
            nroDoc = null;
        }
    }
}
