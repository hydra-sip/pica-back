package com.hydra.pica.plataforma_pica.control.service;

import java.util.List;

import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.service.fraude.ReglaCancelacionesReiteradas;
import com.hydra.pica.plataforma_pica.control.service.fraude.ReglaCarruselBilletera;
import com.hydra.pica.plataforma_pica.control.service.fraude.ReglaCompraMasivaRifa;
import com.hydra.pica.plataforma_pica.control.service.fraude.ReglaCuentasCompartidas;
import com.hydra.pica.plataforma_pica.control.service.fraude.ReglaDeFraude;
import com.hydra.pica.plataforma_pica.control.service.fraude.ReglaObjecionesAbusivas;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Detección de conductas fraudulentas (HU-705, PICA-416): la entrada de {@link DeteccionFraudeAutomatica}. Cada
 * regla vive en su clase de {@code control.service.fraude} y genera alertas para soporte sin duplicar casos abiertos
 * ({@link EstadoAlertaFraude#PENDIENTE}).
 */
@Service
@RequiredArgsConstructor
public class DeteccionFraudeService {

    private final ReglaCompraMasivaRifa compraMasivaRifa;
    private final ReglaCarruselBilletera carruselBilletera;
    private final ReglaCancelacionesReiteradas cancelacionesReiteradas;
    private final ReglaObjecionesAbusivas objecionesAbusivas;
    private final ReglaCuentasCompartidas cuentasCompartidas;
    /** Todas las reglas, en el orden de su {@code @Order}. */
    private final List<ReglaDeFraude> reglas;

    public boolean evaluarCompraRifa(Long rifaId, Long usuarioId) {
        return compraMasivaRifa.evaluar(rifaId, usuarioId);
    }

    public boolean evaluarCarruselBilletera(Long usuarioId) {
        return carruselBilletera.evaluar(usuarioId);
    }

    public boolean evaluarCancelacionesOrganizador(Long torneoId) {
        return cancelacionesReiteradas.evaluarPorTorneo(torneoId);
    }

    public boolean evaluarCancelacionesPorOrganizador(Long organizadorId) {
        return cancelacionesReiteradas.evaluar(organizadorId);
    }

    public boolean evaluarObjecionesUsuario(Long torneoId, Long partidoId) {
        return objecionesAbusivas.evaluarPorPartido(torneoId, partidoId);
    }

    public boolean evaluarObjecionesPorUsuario(Long usuarioId, Long torneoId, Long partidoId) {
        return objecionesAbusivas.evaluar(usuarioId, torneoId, partidoId);
    }

    public boolean evaluarCuentaCompartida(Long usuarioId, String valor) {
        return cuentasCompartidas.evaluar(usuarioId, valor);
    }

    /**
     * Pasada completa de todas las reglas sobre la base (la diaria de {@link DeteccionFraudeAutomatica}): devuelve
     * cuántas alertas nuevas se crearon. Idempotente: no duplica alertas PENDIENTE.
     */
    @Transactional
    public int evaluarTodo() {
        return reglas.stream().mapToInt(ReglaDeFraude::evaluarTodo).sum();
    }
}
