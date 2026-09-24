package com.hydra.pica.plataforma_pica.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body de PUT /admin/usuarios/{id} (UsuarioUpdateRequest en el contrato). Trae todos los campos y
 * reemplaza: una descripción en null la borra. Los roles y la contraseña van por sus endpoints.
 */
public record UsuarioUpdateRequest(
        @NotBlank @Size(min = 3, max = 30) @Pattern(regexp = "^[a-zA-Z0-9._-]+$") String username,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 500) String descripcion,
        @NotNull EstadoEditable estado,
        @NotNull @Positive Long personaId) {

    /** El admin solo alterna entre ACTIVO y BLOQUEADO; PENDIENTE_VERIFICACION lo maneja el sistema. */
    public enum EstadoEditable {
        ACTIVO,
        BLOQUEADO
    }
}
