package com.hydra.pica.plataforma_pica.torneo.controller;

import java.net.URI;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.ResumenEconomico;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.service.EconomiaTorneoService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Paso 3 del asistente: esquema económico y gastos (HU-601, PICA-203). Las reglas están en {@link EconomiaTorneoService}. */
@RestController
@PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
@RequestMapping("/api/v1/torneos/{id}")
public class EconomiaTorneoController {

    private final EconomiaTorneoService economiaTorneoService;
    private final CurrentUserProvider currentUserProvider;

    public EconomiaTorneoController(EconomiaTorneoService economiaTorneoService,
            CurrentUserProvider currentUserProvider) {
        this.economiaTorneoService = economiaTorneoService;
        this.currentUserProvider = currentUserProvider;
    }

    @PutMapping("/economia")
    public TorneoInformacion actualizarEconomia(@PathVariable Long id,
            @Valid @RequestBody EconomiaTorneoRequest request) {
        return economiaTorneoService.actualizarEconomia(usuarioActual(), id, request);
    }

    @GetMapping("/resumen-economico")
    public ResumenEconomico resumen(@PathVariable Long id) {
        return economiaTorneoService.resumen(usuarioActual(), id);
    }

    @GetMapping("/gastos")
    public List<GastoDetalle> gastos(@PathVariable Long id) {
        return economiaTorneoService.gastos(usuarioActual(), id);
    }

    @PostMapping("/gastos")
    public ResponseEntity<GastoDetalle> agregarGasto(@PathVariable Long id, @Valid @RequestBody GastoRequest request) {
        GastoDetalle creado = economiaTorneoService.agregarGasto(usuarioActual(), id, request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{gastoId}")
                .buildAndExpand(creado.gastoId())
                .toUri();
        return ResponseEntity.created(ubicacion).body(creado);
    }

    @PutMapping("/gastos/{gastoId}")
    public GastoDetalle modificarGasto(@PathVariable Long id, @PathVariable int gastoId,
            @Valid @RequestBody GastoRequest request) {
        return economiaTorneoService.modificarGasto(usuarioActual(), id, gastoId, request);
    }

    @DeleteMapping("/gastos/{gastoId}")
    public ResponseEntity<Void> eliminarGasto(@PathVariable Long id, @PathVariable int gastoId) {
        economiaTorneoService.eliminarGasto(usuarioActual(), id, gastoId);
        return ResponseEntity.noContent().build();
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
