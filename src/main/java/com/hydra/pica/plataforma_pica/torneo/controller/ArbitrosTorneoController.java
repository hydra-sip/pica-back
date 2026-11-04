package com.hydra.pica.plataforma_pica.torneo.controller;

import java.net.URI;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.dto.PaginaResponse;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.ArbitroDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.service.ArbitrosTorneoService;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Los árbitros del torneo, en el paso 2 del asistente (HU-203, PICA-236). Las reglas, en {@link ArbitrosTorneoService}. */
@RestController
@PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
@RequestMapping("/api/v1")
public class ArbitrosTorneoController {

    private final ArbitrosTorneoService arbitrosService;
    private final CurrentUserProvider currentUserProvider;

    public ArbitrosTorneoController(ArbitrosTorneoService arbitrosService, CurrentUserProvider currentUserProvider) {
        this.arbitrosService = arbitrosService;
        this.currentUserProvider = currentUserProvider;
    }

    /** Para el buscador del paso 2: sin {@code q}, los primeros por apellido. */
    @GetMapping("/arbitros")
    public PaginaResponse<UsuarioPublico> buscar(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int size) {
        return PaginaResponse.desde(arbitrosService.buscar(q, page, size));
    }

    @GetMapping("/torneos/{id}/arbitros")
    public List<ArbitroDetalle> listar(@PathVariable Long id) {
        return arbitrosService.listar(usuarioActual(), id);
    }

    @PostMapping("/torneos/{id}/arbitros")
    public ResponseEntity<ArbitroDetalle> designar(@PathVariable Long id,
            @Valid @RequestBody DesignarArbitroRequest request) {
        ArbitroDetalle designado = arbitrosService.designar(usuarioActual(), id, request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{usuarioId}")
                .buildAndExpand(designado.usuarioId())
                .toUri();
        return ResponseEntity.created(ubicacion).body(designado);
    }

    @DeleteMapping("/torneos/{id}/arbitros/{usuarioId}")
    public ResponseEntity<Void> quitar(@PathVariable Long id, @PathVariable Long usuarioId) {
        arbitrosService.quitar(usuarioActual(), id, usuarioId);
        return ResponseEntity.noContent().build();
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
