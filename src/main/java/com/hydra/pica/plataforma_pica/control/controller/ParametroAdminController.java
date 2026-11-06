package com.hydra.pica.plataforma_pica.control.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.control.dto.ActualizarParametrosRequest;
import com.hydra.pica.plataforma_pica.control.dto.ParametroDetalle;
import com.hydra.pica.plataforma_pica.control.service.ParametrosAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Los parámetros de la plataforma en el admin (HU-702, PICA-282); las reglas están en {@link ParametrosAdminService}. */
@RestController
@RequestMapping("/api/v1/admin/parametros")
@RequiredArgsConstructor
public class ParametroAdminController {

    private final ParametrosAdminService parametrosAdminService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('PARAMETRO_ADMINISTRAR')")
    public List<ParametroDetalle> listar() {
        return parametrosAdminService.listar();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('PARAMETRO_ADMINISTRAR')")
    public List<ParametroDetalle> actualizar(@Valid @RequestBody ActualizarParametrosRequest request) {
        Long usuarioId = currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
        return parametrosAdminService.actualizar(usuarioId, request.valores());
    }
}
