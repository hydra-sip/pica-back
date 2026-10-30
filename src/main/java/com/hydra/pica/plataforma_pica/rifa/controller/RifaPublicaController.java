package com.hydra.pica.plataforma_pica.rifa.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.dto.LineaListaSellada;
import com.hydra.pica.plataforma_pica.rifa.dto.NumeroOcupado;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaResumen;
import com.hydra.pica.plataforma_pica.rifa.service.RifaPublicaService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rifas para quien compra (HU-403, PICA-221): públicas, sin login, como el catálogo de torneos. Las rifas del
 * organizador están en {@link RifaController}.
 */
@RestController
@RequestMapping("/api/v1/rifas/publicas")
public class RifaPublicaController {

    private final RifaPublicaService rifaPublicaService;

    public RifaPublicaController(RifaPublicaService rifaPublicaService) {
        this.rifaPublicaService = rifaPublicaService;
    }

    @GetMapping
    public Page<RifaPublicaResumen> enVenta(@PageableDefault(size = 20) Pageable pageable) {
        return rifaPublicaService.listarEnVenta(pageable);
    }

    @GetMapping("/{id}")
    public RifaPublicaDetalle ver(@PathVariable Long id) {
        return rifaPublicaService.detalle(id);
    }

    /** La grilla: los números que no se pueden elegir; los demás del rango están libres. */
    @GetMapping("/{id}/numeros")
    public List<NumeroOcupado> numeros(@PathVariable Long id) {
        return rifaPublicaService.numerosOcupados(id);
    }

    /** El desglose de la lista sellada al cerrar la venta: número y código de ticket de cada vendido (HU-407). */
    @GetMapping("/{id}/lista-sellada")
    public List<LineaListaSellada> listaSellada(@PathVariable Long id) {
        return rifaPublicaService.listaSellada(id);
    }
}
