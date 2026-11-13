package com.hydra.pica.plataforma_pica.billetera.controller;

import java.util.Locale;

import com.hydra.pica.plataforma_pica.billetera.dto.PanelFinanciero;
import com.hydra.pica.plataforma_pica.billetera.service.ArchivoLiquidacion;
import com.hydra.pica.plataforma_pica.billetera.service.PanelFinancieroService;
import com.hydra.pica.plataforma_pica.billetera.service.PanelFinancieroService.LiquidacionFinal;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El panel financiero del torneo y la liquidación final para descargar (HU-608, PICA-342). Sin {@code formato},
 * GET /torneos/{id}/liquidacion sigue siendo el reparto en JSON de {@link CustodiaController}.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/torneos/{id}")
public class PanelFinancieroController {

    private static final MediaType CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

    private final PanelFinancieroService panelFinancieroService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/panel-financiero")
    public PanelFinanciero panel(@PathVariable Long id) {
        return panelFinancieroService.delTorneo(usuarioActual(), id);
    }

    @GetMapping(value = "/liquidacion", params = "formato")
    public ResponseEntity<byte[]> descargar(@PathVariable Long id, @RequestParam String formato) {
        String pedido = formato.trim().toLowerCase(Locale.ROOT);
        if (!pedido.equals("csv") && !pedido.equals("pdf")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "El formato tiene que ser csv o pdf");
        }
        LiquidacionFinal liquidacion = panelFinancieroService.liquidacionFinal(usuarioActual(), id);
        boolean esPdf = pedido.equals("pdf");
        return ResponseEntity.ok()
                .contentType(esPdf ? MediaType.APPLICATION_PDF : CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("liquidacion-torneo-" + id + "." + pedido).build().toString())
                .body(esPdf ? ArchivoLiquidacion.pdf(liquidacion) : ArchivoLiquidacion.csv(liquidacion));
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
