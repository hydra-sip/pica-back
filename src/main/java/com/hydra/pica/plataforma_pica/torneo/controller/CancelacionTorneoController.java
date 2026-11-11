package com.hydra.pica.plataforma_pica.torneo.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionRealizada;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDeclarado;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.torneo.service.CancelacionTorneoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** El organizador cancela su torneo (HU-605, PICA-347). Las reglas están en {@link CancelacionTorneoService}. */
@RestController
@PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
@RequestMapping("/api/v1/torneos/{id}/cancelacion")
public class CancelacionTorneoController {

    private final CancelacionTorneoService cancelacionTorneoService;
    private final CurrentUserProvider currentUserProvider;

    public CancelacionTorneoController(CancelacionTorneoService cancelacionTorneoService,
            CurrentUserProvider currentUserProvider) {
        this.cancelacionTorneoService = cancelacionTorneoService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Multipart: {@code motivo} y, por cada gasto, {@code gastos[i].monto}, {@code gastos[i].concepto} y el archivo
     * {@code gastos[i].comprobante}. Un monto que no es un número lo rechaza el binding (400 VALIDACION en ese campo);
     * el resto lo valida el servicio.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CancelacionRealizada cancelar(@PathVariable Long id, @ModelAttribute FormularioCancelacion formulario) {
        return cancelacionTorneoService.cancelar(usuarioActual(), id, formulario.solicitud());
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }

    /** Lo que llega en el multipart; mutable porque así lo arma el binding de Spring con los índices. */
    public static class FormularioCancelacion {

        private String motivo;
        private List<FormularioGasto> gastos = new ArrayList<>();

        public String getMotivo() {
            return motivo;
        }

        public void setMotivo(String motivo) {
            this.motivo = motivo;
        }

        public List<FormularioGasto> getGastos() {
            return gastos;
        }

        public void setGastos(List<FormularioGasto> gastos) {
            this.gastos = gastos;
        }

        SolicitudCancelacion solicitud() {
            return new SolicitudCancelacion(motivo, gastos.stream().map(FormularioGasto::declarado).toList());
        }
    }

    public static class FormularioGasto {

        private BigDecimal monto;
        private String concepto;
        private MultipartFile comprobante;

        public BigDecimal getMonto() {
            return monto;
        }

        public void setMonto(BigDecimal monto) {
            this.monto = monto;
        }

        public String getConcepto() {
            return concepto;
        }

        public void setConcepto(String concepto) {
            this.concepto = concepto;
        }

        public MultipartFile getComprobante() {
            return comprobante;
        }

        public void setComprobante(MultipartFile comprobante) {
            this.comprobante = comprobante;
        }

        GastoDeclarado declarado() {
            try {
                byte[] contenido = comprobante == null || comprobante.isEmpty() ? null : comprobante.getBytes();
                return new GastoDeclarado(monto, concepto, contenido);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
