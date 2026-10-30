package com.hydra.pica.plataforma_pica.rifa.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.NumeroOcupado;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaResumen;
import com.hydra.pica.plataforma_pica.rifa.service.RifaPublicaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RifaPublicaController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class RifaPublicaControllerTest {

    private static final Instant VENTA_HASTA = Instant.parse("2026-11-05T23:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RifaPublicaService rifaPublicaService;

    @Test
    @DisplayName("GET /api/v1/rifas/publicas es público y devuelve las rifas en venta")
    void listaSinLogin() throws Exception {
        when(rifaPublicaService.listarEnVenta(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                new RifaPublicaResumen(7L, "Rifa del club", "Copa abierta", 1, 100, new BigDecimal("500.00"), "ARS",
                        null, VENTA_HASTA, null)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/rifas/publicas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].torneoNombre").value("Copa abierta"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/rifas/publicas/{id} es público y trae el torneo y los premios")
    void detalleSinLogin() throws Exception {
        when(rifaPublicaService.detalle(7L)).thenReturn(new RifaPublicaDetalle(7L, "Rifa del club", null,
                EstadoRifa.EN_VENTA, new RifaPublicaDetalle.TorneoDeRifa(3L, "Copa abierta", "copa-abierta"), 20, 1,
                100, new BigDecimal("500.00"), new BigDecimal("10.00"), "ARS", null, VENTA_HASTA, null,
                List.of(new PremioRifaDetalle(1, 80, "Una bici", null)),
                new RepartoRifa(new BigDecimal("1000.00"), new BigDecimal("200.00"), new BigDecimal("800.00")),
                null, null));

        mockMvc.perform(get("/api/v1/rifas/publicas/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.torneo.slug").value("copa-abierta"))
                .andExpect(jsonPath("$.pctDestinadoTorneo").value(20))
                .andExpect(jsonPath("$.premios[0].descripcion").value("Una bici"))
                .andExpect(jsonPath("$.reparto.alPozoTorneo").value(200.00));
    }

    @Test
    @DisplayName("GET /api/v1/rifas/publicas/{id}/numeros es público y devuelve los ocupados")
    void numerosSinLogin() throws Exception {
        when(rifaPublicaService.numerosOcupados(7L)).thenReturn(List.of(
                new NumeroOcupado(13, EstadoNumero.VENDIDO), new NumeroOcupado(42, EstadoNumero.RESERVADO)));

        mockMvc.perform(get("/api/v1/rifas/publicas/7/numeros"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].numero").value(13))
                .andExpect(jsonPath("$[1].estado").value("RESERVADO"));
    }

    @Test
    @DisplayName("GET /api/v1/rifas/publicas/{id} de un borrador responde 404 RIFA_NO_ENCONTRADA")
    void borradorNoEncontrado() throws Exception {
        when(rifaPublicaService.detalle(9L))
                .thenThrow(new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa 9"));

        mockMvc.perform(get("/api/v1/rifas/publicas/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RIFA_NO_ENCONTRADA"));
    }
}
