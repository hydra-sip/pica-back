package com.hydra.pica.plataforma_pica.rifa.controller;

import java.net.URI;

import jakarta.validation.Valid;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.dto.PremiosRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.PublicarRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaResumen;
import com.hydra.pica.plataforma_pica.rifa.service.CierreVentaService;
import com.hydra.pica.plataforma_pica.rifa.service.RifaService;
import com.hydra.pica.plataforma_pica.rifa.service.SorteoRifaService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

/**
 * Rifas del organizador logueado (PICA-226). Solo para quien tiene RIFA_GESTIONAR, que V11 le da al rol
 * ORGANIZADOR, a ADMINISTRADOR y a SUPER_USUARIO; sin el permiso responde 403 SIN_PERMISO. Las reglas (solo las propias,
 * solo en BORRADOR) están en {@link RifaService}.
 */
@RestController
@PreAuthorize("hasAuthority('RIFA_GESTIONAR')")
@RequestMapping("/api/v1/rifas")
public class RifaController {

    private final RifaService rifaService;
    private final CierreVentaService cierreVentaService;
    private final SorteoRifaService sorteoRifaService;
    private final CurrentUserProvider currentUserProvider;

    public RifaController(RifaService rifaService, CierreVentaService cierreVentaService,
            SorteoRifaService sorteoRifaService, CurrentUserProvider currentUserProvider) {
        this.rifaService = rifaService;
        this.cierreVentaService = cierreVentaService;
        this.sorteoRifaService = sorteoRifaService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/mis-rifas")
    public Page<RifaResumen> misRifas(@PageableDefault(size = 20) Pageable pageable) {
        return rifaService.listarMias(usuarioActual(), pageable);
    }

    @GetMapping("/{id}")
    public RifaDetalle ver(@PathVariable Long id) {
        return rifaService.detalle(usuarioActual(), id);
    }

    @PostMapping
    public ResponseEntity<RifaDetalle> crear(@Valid @RequestBody RifaRequest request) {
        RifaDetalle creada = rifaService.crear(usuarioActual(), request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(creada.id())
                .toUri();
        return ResponseEntity.created(ubicacion).body(creada);
    }

    @PutMapping("/{id}")
    public RifaDetalle modificar(@PathVariable Long id, @Valid @RequestBody RifaRequest request) {
        return rifaService.modificar(usuarioActual(), id, request);
    }

    @PutMapping("/{id}/premios")
    public RifaDetalle reemplazarPremios(@PathVariable Long id, @Valid @RequestBody PremiosRifaRequest request) {
        return rifaService.reemplazarPremios(usuarioActual(), id, request.premios());
    }

    /** El body exige términos y lotería en true (400 si no); lo demás lo controla el servicio. */
    @PostMapping("/{id}/publicar")
    public RifaDetalle publicar(@PathVariable Long id, @Valid @RequestBody PublicarRifaRequest request) {
        return rifaService.publicar(usuarioActual(), id);
    }

    /** Para modificar una rifa publicada que nadie compró (PICA-287). */
    @PostMapping("/{id}/volver-a-borrador")
    public RifaDetalle volverABorrador(@PathVariable Long id) {
        return rifaService.volverABorrador(usuarioActual(), id);
    }

    /** HU-407: cierra la venta antes de la fecha y sella la lista; el cierre es definitivo. */
    @PostMapping("/{id}/cerrar-venta")
    public RifaDetalle cerrarVenta(@PathVariable Long id) {
        return cierreVentaService.cerrar(usuarioActual(), id);
    }

    /** HU-504: sortea la rifa cerrada desde su fecha de sorteo; los premios quedan con su ganador. */
    @PostMapping("/{id}/sorteo")
    public RifaDetalle sortear(@PathVariable Long id) {
        return sorteoRifaService.sortear(usuarioActual(), id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        rifaService.eliminar(usuarioActual(), id);
        return ResponseEntity.noContent().build();
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
