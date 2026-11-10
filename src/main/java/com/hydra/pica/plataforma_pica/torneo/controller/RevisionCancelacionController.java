package com.hydra.pica.plataforma_pica.torneo.controller;

import com.hydra.pica.plataforma_pica.common.dto.PaginaResponse;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionEnRevision;
import com.hydra.pica.plataforma_pica.torneo.dto.Comprobante;
import com.hydra.pica.plataforma_pica.torneo.dto.RechazoCancelacionRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.ResolucionCancelacion;
import com.hydra.pica.plataforma_pica.torneo.service.RevisionCancelacionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** Las que esperan a soporte, de la más vieja a la más nueva. */
    @GetMapping
    public PaginaResponse<CancelacionEnRevision> cola(
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int size) {
        return PaginaResponse.desde(revisionCancelacionService.cola(PageRequest.of(page, size)));
    }

    /** El archivo, para abrirlo en el navegador. */
    @GetMapping("/{torneoId}/gastos/{gastoId}/comprobante")
    public ResponseEntity<byte[]> comprobante(@PathVariable Long torneoId, @PathVariable Long gastoId) {
        Comprobante comprobante = revisionCancelacionService.comprobante(torneoId, gastoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(comprobante.tipoContenido()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("comprobante-" + gastoId).build().toString())
                .body(comprobante.contenido());
    }

    @PostMapping("/{torneoId}/aprobar")
    public ResolucionCancelacion aprobar(@PathVariable Long torneoId) {
        return revisionCancelacionService.aprobar(usuarioActual(), torneoId);
    }

    @PostMapping("/{torneoId}/rechazar")
    public ResolucionCancelacion rechazar(@PathVariable Long torneoId, @RequestBody RechazoCancelacionRequest request) {
        return revisionCancelacionService.rechazar(usuarioActual(), torneoId, request.motivo());
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
