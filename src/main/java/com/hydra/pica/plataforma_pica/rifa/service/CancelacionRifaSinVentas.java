package com.hydra.pica.plataforma_pica.rifa.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.event.RifaCancelada;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Una rifa sin ningún número vendido nunca se sella ni se sortea (PICA-445): no hay ganadores posibles. Si la venta
 * termina así, la rifa se cancela sola. La usan el cierre automático ({@link CierreVentaService}) y el sorteo
 * automático ({@link SorteoRifaService}), este para las CERRADA vacías de antes del arreglo; los dos la llaman con la
 * fila bloqueada y dentro de su transacción. No se mueve ningún fondo: sin ventas no hay nada en custodia.
 */
@Component
@RequiredArgsConstructor
class CancelacionRifaSinVentas {

    private final RifaRepository rifaRepository;
    private final NumeroRepository numeroRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final ApplicationEventPublisher eventos;

    boolean sinVentas(Rifa rifa) {
        return numeroRepository.countByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO) == 0;
    }

    static ConflictoException error(Long rifaId) {
        return new ConflictoException(CodigoError.RIFA_SIN_VENTAS,
                "La rifa " + rifaId + " no vendió ningún número");
    }

    /**
     * Pasa la rifa a CANCELADA y borra sus reservas, sin sellar la lista ni publicar el compromiso. Queda en la
     * auditoría como CANCELACION con el motivo SIN_VENTAS y sin actor.
     */
    void cancelar(Rifa rifa) {
        int reservas = numeroRepository.borrarReservas(rifa.getId());
        EstadoRifa anterior = rifa.getEstado();
        rifa.setEstado(EstadoRifa.CANCELADA);
        Rifa cancelada = rifaRepository.saveAndFlush(rifa);
        auditar(cancelada, anterior, reservas);
        eventos.publishEvent(new RifaCancelada(cancelada.getId(),
                cancelada.getTorneo() != null ? cancelada.getTorneo().getId() : null));
    }

    private void auditar(Rifa rifa, EstadoRifa anterior, int reservasBorradas) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("motivo", "SIN_VENTAS");
        detalle.put("estadoAnterior", anterior.name());
        detalle.put("reservasBorradas", reservasBorradas);
        Auditoria auditoria = new Auditoria();
        auditoria.setAccion(AccionAuditoria.CANCELACION);
        auditoria.setEntidad("RIFA");
        auditoria.setEntidadId(String.valueOf(rifa.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }
}
