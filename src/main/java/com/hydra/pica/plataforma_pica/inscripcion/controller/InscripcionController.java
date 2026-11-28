package com.hydra.pica.plataforma_pica.inscripcion.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ConfirmarInscripcionRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionEquipoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionResumen;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.RepartoIntegrante;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ResumenPago;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.inscripcion.service.PagoInscripcionService;
import com.hydra.pica.plataforma_pica.inscripcion.service.ReemplazoInscripcionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Inscripciones del usuario logueado. Crear, editar, confirmar, reabrir, ver el resumen de pago, pagar y cancelar piden
 * INSCRIPCION_GESTIONAR (V14, rol PARTICIPANTE). Ver alcanza con estar logueado, porque también la ve el
 * organizador del torneo: quién la ve lo decide {@link InscripcionService}, y para cualquier otro
 * responde 404.
 */
@RestController
@RequestMapping("/api/v1")
public class InscripcionController {

    private final InscripcionService inscripcionService;
    private final PagoInscripcionService pagoInscripcionService;
    private final ReemplazoInscripcionService reemplazoInscripcionService;

    public InscripcionController(InscripcionService inscripcionService, PagoInscripcionService pagoInscripcionService,
                                 ReemplazoInscripcionService reemplazoInscripcionService) {
        this.inscripcionService = inscripcionService;
        this.pagoInscripcionService = pagoInscripcionService;
        this.reemplazoInscripcionService = reemplazoInscripcionService;
    }

    @PostMapping("/torneos/{torneoId}/inscripciones")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public ResponseEntity<InscripcionDetalle> crear(@PathVariable Long torneoId,
            @Valid @RequestBody(required = false) InscripcionEquipoRequest request,
            @UsuarioActual Long usuarioId) {
        InscripcionDetalle creada = inscripcionService.crear(usuarioId, torneoId, request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/inscripciones/{id}")
                .buildAndExpand(creada.id())
                .toUri();
        return ResponseEntity.created(ubicacion).body(creada);
    }

    @PutMapping("/inscripciones/{id}")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public InscripcionDetalle editar(@PathVariable Long id, @Valid @RequestBody InscripcionEquipoRequest request,
            @UsuarioActual Long usuarioId) {
        return inscripcionService.editar(usuarioId, id, request);
    }

    @GetMapping("/inscripciones/mias")
    public Page<InscripcionResumen> mias(@RequestParam(required = false) Long torneoId,
            @PageableDefault(size = 20) Pageable pageable,
            @UsuarioActual Long usuarioId) {
        return inscripcionService.listarMias(usuarioId, torneoId, pageable);
    }

    @GetMapping("/inscripciones/{id}")
    public InscripcionDetalle ver(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        return inscripcionService.detalle(usuarioId, id);
    }

    @PutMapping("/inscripciones/{id}/confirmar")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public InscripcionDetalle confirmar(@PathVariable Long id, @RequestBody(required = false) ConfirmarInscripcionRequest request,
            @UsuarioActual Long usuarioId) {
        boolean reglamentoAceptado = request != null && Boolean.TRUE.equals(request.reglamentoAceptado());
        return inscripcionService.confirmar(usuarioId, id, reglamentoAceptado);
    }

    @PutMapping("/inscripciones/{id}/reabrir")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public InscripcionDetalle reabrir(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        return inscripcionService.reabrir(usuarioId, id);
    }

    @PostMapping("/inscripciones/{id}/reemplazos")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public InscripcionDetalle reemplazar(@PathVariable Long id, @Valid @RequestBody ReemplazoRequest request,
            @UsuarioActual Long usuarioId) {
        return reemplazoInscripcionService.reemplazar(usuarioId, id, request);
    }

    /** El capitán cambia el reparto del premio de su equipo (CR-05); el cuerpo es la lista de {usuarioId, pct}. */
    @PutMapping("/inscripciones/{id}/reparto")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public InscripcionDetalle repartir(@PathVariable Long id, @RequestBody List<RepartoIntegrante> request,
            @UsuarioActual Long usuarioId) {
        return inscripcionService.cambiarReparto(usuarioId, id, request);
    }

    @GetMapping("/inscripciones/{id}/reemplazos")
    public List<ReemplazoDetalle> reemplazos(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        return reemplazoInscripcionService.reemplazos(usuarioId, id);
    }

    @GetMapping("/inscripciones/{id}/resumen-pago")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public ResumenPago resumenPago(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        return pagoInscripcionService.resumenPago(usuarioId, id);
    }

    @PostMapping("/inscripciones/{id}/pago")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public InscripcionDetalle pagar(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        return pagoInscripcionService.pagar(usuarioId, id);
    }

    @DeleteMapping("/inscripciones/{id}")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public ResponseEntity<Void> cancelar(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        inscripcionService.cancelar(usuarioId, id);
        return ResponseEntity.noContent().build();
    }
}
