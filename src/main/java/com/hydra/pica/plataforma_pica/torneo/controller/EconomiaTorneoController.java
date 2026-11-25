package com.hydra.pica.plataforma_pica.torneo.controller;

import java.net.URI;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.PagoGastoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.PublicarTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.ResumenEconomico;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.service.EconomiaTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.HonorariosService;
import com.hydra.pica.plataforma_pica.torneo.service.PublicacionTorneoService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Paso 3 del asistente: esquema económico y gastos (HU-601, PICA-203), y publicar (PICA-204). Las reglas están en
 * {@link EconomiaTorneoService} y {@link PublicacionTorneoService}.
 */
@RestController
@PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
@RequestMapping("/api/v1/torneos/{id}")
public class EconomiaTorneoController {

    private final EconomiaTorneoService economiaTorneoService;
    private final PublicacionTorneoService publicacionTorneoService;
    private final HonorariosService honorariosService;

    public EconomiaTorneoController(EconomiaTorneoService economiaTorneoService,
            PublicacionTorneoService publicacionTorneoService, HonorariosService honorariosService) {
        this.economiaTorneoService = economiaTorneoService;
        this.publicacionTorneoService = publicacionTorneoService;
        this.honorariosService = honorariosService;
    }

    @PutMapping("/economia")
    public TorneoInformacion actualizarEconomia(@PathVariable Long id,
            @Valid @RequestBody EconomiaTorneoRequest request,
            @UsuarioActual Long usuarioId) {
        return economiaTorneoService.actualizarEconomia(usuarioId, id, request);
    }

    @GetMapping("/resumen-economico")
    public ResumenEconomico resumen(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        return economiaTorneoService.resumen(usuarioId, id);
    }

    @GetMapping("/gastos")
    public List<GastoDetalle> gastos(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        return economiaTorneoService.gastos(usuarioId, id);
    }

    @PostMapping("/gastos")
    public ResponseEntity<GastoDetalle> agregarGasto(@PathVariable Long id, @Valid @RequestBody GastoRequest request,
            @UsuarioActual Long usuarioId) {
        GastoDetalle creado = economiaTorneoService.agregarGasto(usuarioId, id, request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{gastoId}")
                .buildAndExpand(creado.gastoId())
                .toUri();
        return ResponseEntity.created(ubicacion).body(creado);
    }

    @PutMapping("/gastos/{gastoId}")
    public GastoDetalle modificarGasto(@PathVariable Long id, @PathVariable int gastoId,
            @Valid @RequestBody GastoRequest request,
            @UsuarioActual Long usuarioId) {
        return economiaTorneoService.modificarGasto(usuarioId, id, gastoId, request);
    }

    @DeleteMapping("/gastos/{gastoId}")
    public ResponseEntity<Void> eliminarGasto(@PathVariable Long id, @PathVariable int gastoId,
            @UsuarioActual Long usuarioId) {
        economiaTorneoService.eliminarGasto(usuarioId, id, gastoId);
        return ResponseEntity.noContent().build();
    }

    /** HU-604: deposita un honorario para un árbitro designado o registra un gasto común pagado por fuera. */
    @PostMapping("/gastos/{gastoId}/pago")
    public GastoDetalle pagarGasto(@PathVariable Long id, @PathVariable int gastoId,
            @Valid @RequestBody PagoGastoRequest request,
            @UsuarioActual Long usuarioId) {
        return honorariosService.pagar(usuarioId, id, gastoId, request);
    }

    /** HU-604: el honorario depositado vuelve a la billetera del organizador. */
    @DeleteMapping("/gastos/{gastoId}/pago")
    public GastoDetalle retirarHonorario(@PathVariable Long id, @PathVariable int gastoId,
            @UsuarioActual Long usuarioId) {
        return honorariosService.retirar(usuarioId, id, gastoId);
    }

    /** El body solo trae la aceptación de los términos; sin ella responde 400 antes de llegar al servicio. */
    @PostMapping("/publicar")
    public TorneoInformacion publicar(@PathVariable Long id, @Valid @RequestBody PublicarTorneoRequest request,
            @UsuarioActual Long usuarioId) {
        return publicacionTorneoService.publicar(usuarioId, id);
    }
}
