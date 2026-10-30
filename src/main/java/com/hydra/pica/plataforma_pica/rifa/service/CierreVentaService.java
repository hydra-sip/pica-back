package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.MotivoCierre;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.SelloRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.event.VentaRifaCerrada;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El cierre de la venta de una rifa (HU-407, PICA-261), lo cierre quien lo cierre: el organizador a mano o la tarea
 * programada cuando llega {@code ventaHasta} ({@link CierreAutomaticoRifa}), como el cierre de la inscripción de un
 * torneo. La rifa pasa a CERRADA, se borran las reservas que no se pagaron y se sella la lista de vendidos con
 * {@link SelloRifa}; desde ahí la lista no se modifica (409 LISTA_SELLADA). Queda en la auditoría con el motivo.
 */
@Service
@RequiredArgsConstructor
public class CierreVentaService {

    private final RifaRepository rifaRepository;
    private final NumeroRepository numeroRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final RifaService rifaService;
    private final ApplicationEventPublisher eventos;

    /** El organizador cierra la venta antes de {@code ventaHasta}; el cierre es definitivo. */
    @Transactional
    public RifaDetalle cerrar(Long organizadorId, Long id) {
        Rifa rifa = rifaRepository.buscarBloqueado(id)
                .filter(r -> r.getOrganizador().getId().equals(organizadorId))
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + id));
        if (rifa.getListaSelladaEn() != null) {
            throw listaSellada(id);
        }
        if (rifa.getEstado() != EstadoRifa.EN_VENTA) {
            throw new ConflictoException(CodigoError.RIFA_NO_EDITABLE,
                    "La rifa " + id + " está " + rifa.getEstado() + ": solo se cierra la venta de una EN_VENTA");
        }
        sellar(rifa, MotivoCierre.MANUAL, organizadorId);
        return rifaService.detalle(organizadorId, id);
    }

    /**
     * Cierra con el motivo FECHA si la rifa sigue EN_VENTA y la venta ya terminó. Bloquea la fila y vuelve a mirar:
     * otra réplica o el organizador pudieron cerrarla mientras tanto.
     *
     * @return si la cerró
     */
    @Transactional
    public boolean cerrarSiVencio(Long id, Instant ahora) {
        Rifa rifa = rifaRepository.buscarBloqueado(id).orElse(null);
        if (rifa == null || rifa.getEstado() != EstadoRifa.EN_VENTA || rifa.getVentaHasta().isAfter(ahora)) {
            return false;
        }
        sellar(rifa, MotivoCierre.FECHA, null);
        return true;
    }

    static ConflictoException listaSellada(Long rifaId) {
        return new ConflictoException(CodigoError.LISTA_SELLADA,
                "La venta de la rifa " + rifaId + " se cerró y su lista de números vendidos está sellada");
    }

    /** @param actorId el organizador que cierra a mano; null si la cierra la tarea programada */
    private void sellar(Rifa rifa, MotivoCierre motivo, Long actorId) {
        Long torneoId = rifa.getTorneo() != null ? rifa.getTorneo().getId() : null;
        int reservas = numeroRepository.borrarReservas(rifa.getId());
        List<Numero> vendidos = numeroRepository.findByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO);
        rifa.setListaHash(SelloRifa.lista(vendidos));
        rifa.setListaSelladaEn(Instant.now());
        rifa.setEstado(EstadoRifa.CERRADA);
        Rifa cerrada = rifaRepository.saveAndFlush(rifa);
        auditar(cerrada, motivo, actorId, vendidos.size(), reservas);
        eventos.publishEvent(new VentaRifaCerrada(cerrada.getId(), torneoId));
    }

    private void auditar(Rifa rifa, MotivoCierre motivo, Long actorId, int vendidos, int reservasBorradas) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("motivo", motivo.name());
        detalle.put("vendidos", vendidos);
        detalle.put("reservasBorradas", reservasBorradas);
        detalle.put("listaHash", rifa.getListaHash());
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(actorId == null ? null : usuarioRepository.getReferenceById(actorId));
        auditoria.setAccion(AccionAuditoria.CIERRE_VENTA);
        auditoria.setEntidad("RIFA");
        auditoria.setEntidadId(String.valueOf(rifa.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }
}
