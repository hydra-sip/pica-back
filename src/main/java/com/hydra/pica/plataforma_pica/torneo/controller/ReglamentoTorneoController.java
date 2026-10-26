package com.hydra.pica.plataforma_pica.torneo.controller;

import java.io.IOException;

import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.service.ReglamentoTorneoService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * El PDF del reglamento (HU-201, PICA-200). Subir y quitar son del asistente; verlo es público si el torneo está
 * publicado, así que el GET no pide permiso y las reglas están en {@link ReglamentoTorneoService}.
 */
@RestController
@RequestMapping("/api/v1/torneos/{id}/reglamento")
public class ReglamentoTorneoController {

    private final ReglamentoTorneoService reglamentoService;
    private final CurrentUserProvider currentUserProvider;

    public ReglamentoTorneoController(ReglamentoTorneoService reglamentoService,
            CurrentUserProvider currentUserProvider) {
        this.reglamentoService = reglamentoService;
        this.currentUserProvider = currentUserProvider;
    }

    /** Multipart con el archivo en la parte {@code archivo}. */
    @PostMapping
    @PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
    public TorneoInformacion subir(@PathVariable Long id, @RequestParam("archivo") MultipartFile archivo)
            throws IOException {
        if (archivo.getSize() > ReglamentoTorneoService.TAMANIO_MAXIMO) {
            throw ReglamentoTorneoService.archivoInvalido(ErrorCampo.Codigo.LONGITUD, "Supera el tamaño máximo");
        }
        // la misma URL del POST, absoluta: es la del GET
        String url = ServletUriComponentsBuilder.fromCurrentRequestUri().toUriString();
        return reglamentoService.subir(usuarioActual(), id, archivo.getBytes(), url);
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
    public TorneoInformacion quitar(@PathVariable Long id) {
        return reglamentoService.quitar(usuarioActual(), id);
    }

    @GetMapping
    public ResponseEntity<byte[]> ver(@PathVariable Long id) {
        byte[] pdf = reglamentoService.pdf(currentUserProvider.getCurrentUserId(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("reglamento-" + id + ".pdf").build().toString())
                .body(pdf);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
