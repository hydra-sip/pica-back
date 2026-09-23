package com.hydra.pica.plataforma_pica.user.dto;

import java.time.LocalDate;

import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistroRequest(
        @NotBlank
        @Size(min = 3, max = 30)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$")
        String username,
        @NotBlank
        @Email
        @Size(max = 254)
        String email,
        @NotBlank
        @Size(min = 8, max = 72)
        @Pattern(regexp = "^(?=.*[A-Z])(?=.*\\d).{8,}$")
        String password,
        @NotBlank
        @Size(min = 1, max = 100)
        String nombres,
        @NotBlank
        @Size(min = 1, max = 100)
        String apellidos,
        @NotNull
        TipoDoc tipoDoc,
        @NotBlank
        @Size(min = 5, max = 20)
        @Pattern(regexp = "^[0-9A-Za-z]+$")
        String nroDoc,
        @NotNull
        @PastOrPresent
        LocalDate fechaNacimiento) {
}
