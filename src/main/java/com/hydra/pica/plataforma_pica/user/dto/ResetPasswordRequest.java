package com.hydra.pica.plataforma_pica.user.dto;

import com.hydra.pica.plataforma_pica.common.validation.PasswordValida;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body de PUT /admin/usuarios/{id}/password: la contraseña temporal que define el admin. */
public record ResetPasswordRequest(@NotBlank @PasswordValida @Size(max = 72) String password) {
}
