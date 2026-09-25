package com.hydra.pica.plataforma_pica.user.dto;

import com.hydra.pica.plataforma_pica.common.validation.PasswordValida;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body de PUT /me/password. {@code passwordActual} es opcional en el contrato: solo la exige el servicio,
 * y solo si el usuario ya tiene contraseña (un usuario de Google todavía no tiene y define la primera).
 * Los 72 caracteres son el tope de BCrypt, igual que en el registro.
 */
public record CambioPasswordRequest(
        @Size(max = 72) String passwordActual,
        @NotBlank @PasswordValida @Size(max = 72) String passwordNueva) {
}
