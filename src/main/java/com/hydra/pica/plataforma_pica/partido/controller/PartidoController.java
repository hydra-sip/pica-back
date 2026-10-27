package com.hydra.pica.plataforma_pica.partido.controller;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.partido.dto.EvidenciaDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.service.EvidenciaService;
import com.hydra.pica.plataforma_pica.partido.service.PartidoService;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * El partido, su resultado y su evidencia (HU-301, PICA-241). Con sesión y sin permiso propio: quién puede cargar, subir
 * o ver lo decide cada servicio según el lado del que es capitán.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class PartidoController {

    private final PartidoService partidoService;
    private final ResultadoService resultadoService;
    private final EvidenciaService evidenciaService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/partidos/{id}")
    public PartidoDetalle ver(@PathVariable Long id) {
        return partidoService.ver(usuarioActual(), id);
    }

    /** 204 si no juega el torneo, si quedó afuera o si ya terminó. */
    @GetMapping("/torneos/{id}/mi-proximo-partido")
    public ResponseEntity<PartidoDetalle> miProximo(@PathVariable Long id) {
        return partidoService.miProximo(usuarioActual(), id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/partidos/{id}/resultado")
    public PartidoDetalle cargarResultado(@PathVariable Long id, @Valid @RequestBody ResultadoRequest request) {
        return resultadoService.cargar(usuarioActual(), id, request);
    }

    /** Multipart con el archivo en la parte {@code archivo}. */
    @PostMapping("/partidos/{id}/evidencias")
    public ResponseEntity<EvidenciaDetalle> subirEvidencia(@PathVariable Long id,
            @RequestParam("archivo") MultipartFile archivo) throws IOException {
        if (archivo.getSize() > EvidenciaService.MAXIMO_VIDEO) {
            throw EvidenciaService.archivoInvalido(ErrorCampo.Codigo.LONGITUD, "Supera el tamaño máximo");
        }
        String urlBase = ServletUriComponentsBuilder.fromCurrentRequestUri().toUriString();
        EvidenciaDetalle subida = evidenciaService.subir(usuarioActual(), id, archivo.getBytes(), urlBase);
        return ResponseEntity.created(URI.create(subida.url())).body(subida);
    }

    @GetMapping("/partidos/{id}/evidencias")
    public List<EvidenciaDetalle> listarEvidencias(@PathVariable Long id) {
        return evidenciaService.listar(usuarioActual(), id);
    }

    @GetMapping("/partidos/{id}/evidencias/{evidenciaId}/archivo")
    public ResponseEntity<byte[]> verEvidencia(@PathVariable Long id, @PathVariable Long evidenciaId) {
        EvidenciaService.Archivo archivo = evidenciaService.archivo(usuarioActual(), id, evidenciaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(archivo.tipoContenido()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("evidencia-" + evidenciaId).build().toString())
                .body(archivo.contenido());
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
