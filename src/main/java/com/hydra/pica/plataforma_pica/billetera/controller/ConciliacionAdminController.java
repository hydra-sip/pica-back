package com.hydra.pica.plataforma_pica.billetera.controller;

import com.hydra.pica.plataforma_pica.billetera.dto.ConciliacionCargas;
import com.hydra.pica.plataforma_pica.billetera.service.ConciliacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** El admin concilia las cargas con cripto contra los eventos del contrato (HU-608, PICA-343). */
@RestController
@RequestMapping("/api/v1/admin/conciliacion")
@PreAuthorize("hasAuthority('TORNEO_ADMINISTRAR')")
@RequiredArgsConstructor
public class ConciliacionAdminController {

    private final ConciliacionService conciliacionService;

    @GetMapping
    public ConciliacionCargas conciliar() {
        return conciliacionService.conciliar();
    }
}
