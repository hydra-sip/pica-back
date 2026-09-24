package com.hydra.pica.plataforma_pica.user.dto;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.validation.PasswordValida;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UsuarioCreateRequest(
        @NotBlank @Size(min = 3, max = 30) @Pattern(regexp = "^[a-zA-Z0-9._-]+$") String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @PasswordValida @Size(max = 72) String passwordTemporal,
        @Size(max = 500) String descripcion,
        EstadoAlta estado,
        @NotNull @Positive Long personaId,
        @NotNull List<@NotNull @Positive Long> roles) {

    /** El alta por admin nace ACTIVO o BLOQUEADO; PENDIENTE_VERIFICACION es solo del autoregistro. */
    public enum EstadoAlta {
        ACTIVO,
        BLOQUEADO
    }
}
