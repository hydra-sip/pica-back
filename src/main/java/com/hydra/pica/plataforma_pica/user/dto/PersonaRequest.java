package com.hydra.pica.plataforma_pica.user.dto;

import java.time.LocalDate;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.validation.ConDocumento;
import com.hydra.pica.plataforma_pica.user.validation.DocumentoValido;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body de POST y PUT /admin/personas (PersonaRequest en el contrato). El documento es obligatorio:
 * la única persona sin documento es la que crea el login con Google. Si no viene {@code estado}, el
 * alta queda ACTIVO y la modificación conserva el que tenía.
 */
@DocumentoValido
public record PersonaRequest(
        @NotBlank @Size(max = 100) String nombres,
        @NotBlank @Size(max = 100) String apellidos,
        @NotNull TipoDoc tipoDoc,
        @NotBlank @Size(min = 5, max = 20) @Pattern(regexp = "^[0-9A-Za-z]+$") String nroDoc,
        @PastOrPresent LocalDate fechaNacimiento,
        @Size(max = 200) String domicilioPostal,
        @Size(max = 30) String telefono,
        @Size(max = 500) String descripcion,
        EstadoGeneral estado) implements ConDocumento {
}
