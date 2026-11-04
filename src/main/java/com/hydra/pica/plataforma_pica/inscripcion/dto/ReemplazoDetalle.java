package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.inscripcion.domain.Reemplazo;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

public record ReemplazoDetalle(
        UsuarioPublico sale,
        UsuarioPublico entra,
        Instant reemplazadoEn,
        UsuarioPublico realizadoPor) {

    public static ReemplazoDetalle de(Reemplazo reemplazo) {
        return new ReemplazoDetalle(
                UsuarioPublico.desde(reemplazo.getSale()),
                UsuarioPublico.desde(reemplazo.getEntra()),
                reemplazo.getCreadoEn(),
                UsuarioPublico.desde(reemplazo.getRealizadoPor()));
    }
}
