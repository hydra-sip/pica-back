package com.hydra.pica.plataforma_pica.rifa.controller;

import jakarta.validation.Valid;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.dto.ReservaRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Venta de números de rifa (HU-403, PICA-222; empezó en HU-305, T305-1): reservar los elegidos, cancelar la reserva y
 * comprarlos. Cualquier usuario con sesión; las reglas están en {@link VentaRifaService}.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/rifas/{id}")
public class VentaRifaController {

    private final VentaRifaService ventaRifaService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/reservas")
    public ReservaRifa reservar(@PathVariable Long id, @Valid @RequestBody VentaRifaRequest request) {
        return ventaRifaService.reservar(usuarioActual(), id, request.numeros());
    }

    @DeleteMapping("/reservas")
    public ResponseEntity<Void> cancelarReserva(@PathVariable Long id) {
        ventaRifaService.cancelarReserva(usuarioActual(), id);
        return ResponseEntity.noContent().build();
    }

    /** Sin el header Idempotency-Key responde 400; el servicio lo controla para devolverlo como los demás errores. */
    @PostMapping("/comprar")
    public VentaRifaResultado comprar(@PathVariable Long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String claveIdempotencia,
            @Valid @RequestBody VentaRifaRequest request) {
        return ventaRifaService.comprar(usuarioActual(), id, request.numeros(), claveIdempotencia);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
