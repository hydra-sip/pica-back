package com.hydra.pica.plataforma_pica.torneo.controller;

import java.net.URI;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.OpcionesTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Asistente de crear torneo del organizador (HU-201, PICA-199). Las reglas están en {@link TorneoService}. */
@RestController
@PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
@RequestMapping("/api/v1/torneos")
public class TorneoController {

    private final TorneoService torneoService;
    private final CurrentUserProvider currentUserProvider;

    public TorneoController(TorneoService torneoService, CurrentUserProvider currentUserProvider) {
        this.torneoService = torneoService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/opciones")
    public OpcionesTorneo opciones(@RequestParam(required = false) String sede) {
        return torneoService.opciones(sede);
    }

    @GetMapping("/{id}")
    public TorneoInformacion ver(@PathVariable Long id) {
        return torneoService.detalle(usuarioActual(), id);
    }

    @PostMapping
    public ResponseEntity<TorneoInformacion> crear(@Valid @RequestBody InformacionTorneoRequest request) {
        TorneoInformacion creado = torneoService.crear(usuarioActual(), request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(creado.id())
                .toUri();
        return ResponseEntity.created(ubicacion).body(creado);
    }

    @PutMapping("/{id}/informacion")
    public TorneoInformacion actualizarInformacion(@PathVariable Long id,
            @Valid @RequestBody InformacionTorneoRequest request) {
        return torneoService.actualizarInformacion(usuarioActual(), id, request);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
