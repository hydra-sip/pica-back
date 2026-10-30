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
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.SorteoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.event.RifaSorteada;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.PremioRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El sorteo de una rifa CERRADA (HU-504, PICA-265), lo dispare quien lo dispare: el organizador o la tarea programada
 * cuando llega {@code fechaSorteo} ({@link SorteoAutomaticoRifa}). Obtiene la semilla ({@link SemillaRifaService}, que
 * exige el compromiso publicado), ordena los vendidos con {@link SorteoRifa} y deja el ganador de cada premio; la
 * rifa pasa a SORTEADA y queda en la auditoría con la semilla, el compromiso y los ganadores.
 *
 * <p>El organizador sortea desde {@code fechaSorteo}, que es parte del compromiso; antes, 409 SORTEO_ANTICIPADO. Con
 * {@code app.rifas.sorteo-anticipado=true} (solo para la demo) puede sortear apenas se cierra la venta.
 */
@Service
@RequiredArgsConstructor
public class SorteoRifaService {

    private final RifaRepository rifaRepository;
    private final NumeroRepository numeroRepository;
    private final PremioRifaRepository premioRifaRepository;
    private final SemillaRifaService semillaRifaService;
    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final RifaService rifaService;
    private final ApplicationEventPublisher eventos;

    @Value("${app.rifas.sorteo-anticipado:false}")
    private boolean anticipado;

    @Transactional
    public RifaDetalle sortear(Long organizadorId, Long id) {
        Rifa rifa = rifaRepository.buscarBloqueado(id)
                .filter(r -> r.getOrganizador().getId().equals(organizadorId))
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + id));
        if (rifa.getEstado() != EstadoRifa.CERRADA) {
            throw new ConflictoException(CodigoError.RIFA_NO_EDITABLE,
                    "La rifa " + id + " está " + rifa.getEstado() + ": solo se sortea una CERRADA");
        }
        if (!anticipado && Instant.now().isBefore(rifa.getSorteadaEn())) {
            throw new ConflictoException(CodigoError.SORTEO_ANTICIPADO,
                    "La rifa " + id + " se sortea desde la fecha comprometida: " + rifa.getSorteadaEn());
        }
        sortear(rifa, organizadorId);
        return rifaService.detalle(organizadorId, id);
    }

    /**
     * Sortea si la rifa sigue CERRADA y ya llegó su fecha. Bloquea la fila y vuelve a mirar: el organizador u otra
     * réplica pudieron sortearla mientras tanto.
     *
     * @return si la sorteó
     */
    @Transactional
    public boolean sortearSiLlegoLaFecha(Long id, Instant ahora) {
        Rifa rifa = rifaRepository.buscarBloqueado(id).orElse(null);
        if (rifa == null || rifa.getEstado() != EstadoRifa.CERRADA || rifa.getSorteadaEn().isAfter(ahora)) {
            return false;
        }
        sortear(rifa, null);
        return true;
    }

    /** @param actorId el organizador; null si la sortea la tarea programada */
    private void sortear(Rifa rifa, Long actorId) {
        String semilla = semillaRifaService.obtener(rifa.getId()).getSemilla();
        List<Integer> vendidos = numeroRepository.findByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO).stream()
                .map(n -> n.getId().getNumero())
                .toList();
        List<Integer> orden = SorteoRifa.orden(semilla, vendidos);
        List<PremioRifa> premios = premioRifaRepository.buscarPorRifa(rifa.getId());
        Map<Integer, Integer> ganadores = new LinkedHashMap<>();
        for (int i = 0; i < premios.size() && i < orden.size(); i++) {
            premios.get(i).setNumeroGanador(orden.get(i));
            ganadores.put(premios.get(i).getId().getPuesto(), orden.get(i));
        }
        rifa.setEstado(EstadoRifa.SORTEADA);
        Rifa sorteada = rifaRepository.saveAndFlush(rifa);
        auditar(sorteada, actorId, ganadores);
        eventos.publishEvent(new RifaSorteada(sorteada.getId(),
                sorteada.getTorneo() != null ? sorteada.getTorneo().getId() : null));
    }

    private void auditar(Rifa rifa, Long actorId, Map<Integer, Integer> ganadores) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("semilla", rifa.getSemilla());
        detalle.put("compromisoHash", rifa.getCompromisoHash());
        detalle.put("listaHash", rifa.getListaHash());
        detalle.put("ganadores", ganadores);
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(actorId == null ? null : usuarioRepository.getReferenceById(actorId));
        auditoria.setAccion(AccionAuditoria.SORTEO);
        auditoria.setEntidad("RIFA");
        auditoria.setEntidadId(String.valueOf(rifa.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }
}
