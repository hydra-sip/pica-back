package com.hydra.pica.plataforma_pica.torneo.controller;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.RechazoCancelacionRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.ResolucionCancelacion;
import com.hydra.pica.plataforma_pica.torneo.service.RevisionCancelacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Soporte revisa las cancelaciones de torneos con gastos (HU-605, PICA-348). Las reglas están en
 * {@link RevisionCancelacionService}.
 */
@RestController
@RequestMapping("/api/v1/admin/cancelaciones")
@PreAuthorize("hasAuthority('CANCELACION_REVISAR')")
@RequiredArgsConstructor
public class RevisionCancelacionController {

    private final RevisionCancelacionService revisionCancelacionService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/{torneoId}/rechazar")
    public ResolucionCancelacion rechazar(@PathVariable Long torneoId, @RequestBody RechazoCancelacionRequest request) {
        return revisionCancelacionService.rechazar(usuarioActual(), torneoId, request.motivo());
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
