package com.hydra.pica.plataforma_pica.partido.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.domain.Grupo;
import com.hydra.pica.plataforma_pica.partido.domain.GrupoInscripcion;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoGrupos;
import com.hydra.pica.plataforma_pica.partido.dto.SorteoGruposDetalle;
import com.hydra.pica.plataforma_pica.partido.event.GruposSorteados;
import com.hydra.pica.plataforma_pica.partido.repository.GrupoInscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.repository.GrupoRepository;
import com.hydra.pica.plataforma_pica.rifa.service.Drand;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.ReglasGrupos;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * El sorteo de los grupos (CR-02, PICA-397), al generar el torneo con grupos ({@link BracketService}). El algoritmo
 * está en {@link SorteoGrupos}.
 *
 * <ul>
 * <li>La semilla es el valor de la ronda de drand fijada al sellar la lista: hasta que sale, 409
 * SEMILLA_NO_DISPONIBLE (son unos segundos). Se guarda con su firma.</li>
 * <li>Los grupos son los del cupo máximo ({@code cupoMaximo / tamanioGrupo}); si no se llenó, quedan desparejos, y
 * cada uno necesita al menos uno más que los que pasan (409 PARTICIPANTES_INSUFICIENTES).</li>
 * <li>No se vuelve a sortear: con la misma lista y la misma ronda saldría lo mismo.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SorteoGruposService {

    private final Drand drand;
    private final InscripcionRepository inscripcionRepository;
    private final GrupoRepository grupoRepository;
    private final GrupoInscripcionRepository grupoInscripcionRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final ApplicationEventPublisher eventos;
    private final TorneoRepository torneoRepository;

    /**
     * Dentro de la transacción de quien lo llama, con la fila del torneo bloqueada.
     *
     * @return los grupos, por letra, con sus inscripciones
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Grupo, List<Long>> sortear(Torneo torneo, Long organizadorId) {
        if (grupoRepository.existsByTorneoId(torneo.getId())) {
            throw new ConflictoException(CodigoError.GRUPOS_YA_SORTEADOS,
                    "Los grupos del torneo " + torneo.getId() + " ya se sortearon");
        }
        ReglasGrupos reglas = ReglasGrupos.de(torneo);
        int grupos = reglas.grupos(torneo.getCupoMaximo());
        List<Long> inscriptos =
                inscripcionRepository.idsPorTorneoYEstado(torneo.getId(), EstadoInscripcion.CONFIRMADA);
        int necesarios = grupos * (reglas.clasificanPorGrupo() + 1);
        if (inscriptos.size() < necesarios) {
            throw new ConflictoException(CodigoError.PARTICIPANTES_INSUFICIENTES,
                    "El torneo " + torneo.getId() + " tiene " + inscriptos.size() + " inscripciones confirmadas: con "
                            + grupos + " grupos hacen falta al menos " + necesarios);
        }
        Long ronda = torneo.getSemillaRonda();
        if (ronda == null) {
            throw new IllegalStateException("El torneo " + torneo.getId() + " no fijó la ronda de drand al cerrar");
        }
        if (!drand.salio(ronda)) {
            throw new ConflictoException(CodigoError.SEMILLA_NO_DISPONIBLE,
                    "La ronda " + ronda + " de drand todavía no salió: probá de nuevo en unos segundos");
        }
        Drand.Pulso pulso = drand.pulso(ronda);
        torneo.setSemillaBracket(pulso.randomness());
        torneo.setSemillaFirma(pulso.signature());

        List<List<Long>> reparto = SorteoGrupos.repartir(pulso.randomness(), inscriptos, grupos);
        Map<Grupo, List<Long>> porGrupo = new LinkedHashMap<>();
        List<GrupoInscripcion> integrantes = new ArrayList<>();
        for (int i = 0; i < reparto.size(); i++) {
            Grupo grupo = grupoRepository.save(new Grupo(torneo, SorteoGrupos.letra(i)));
            porGrupo.put(grupo, reparto.get(i));
            reparto.get(i).forEach(id ->
                    integrantes.add(new GrupoInscripcion(grupo, inscripcionRepository.getReferenceById(id))));
        }
        grupoInscripcionRepository.saveAll(integrantes);

        auditar(torneo, organizadorId, inscriptos.size(), grupos);
        eventos.publishEvent(new GruposSorteados(torneo.getId()));
        return porGrupo;
    }

    /** Público: el de un torneo con grupos publicado; sin sortear todavía, con el compromiso y sin semilla. */
    @Transactional(readOnly = true)
    public SorteoGruposDetalle ver(Long torneoId) {
        Torneo torneo = torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                .filter(t -> t.getEstado() != EstadoTorneo.BORRADOR)
                .filter(t -> t.getFormato() == Formato.GRUPOS_Y_ELIMINACION)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo con grupos " + torneoId));
        List<GrupoInscripcion> integrantes = grupoInscripcionRepository.delTorneo(torneoId);
        List<Long> inscripciones = integrantes.isEmpty()
                ? inscripcionRepository.idsPorTorneoYEstado(torneoId, EstadoInscripcion.CONFIRMADA)
                : integrantes.stream().map(gi -> gi.getId().getInscripcionId()).sorted().toList();
        Map<Long, Integer> lugar = new HashMap<>();
        if (torneo.getSemillaBracket() != null) {
            List<Long> orden = SorteoBracket.orden(torneo.getSemillaBracket(), inscripciones);
            for (int i = 0; i < orden.size(); i++) {
                lugar.put(orden.get(i), i);
            }
        }
        Map<String, List<SorteoGruposDetalle.Integrante>> porLetra = new TreeMap<>();
        integrantes.stream()
                .sorted(Comparator.comparing((GrupoInscripcion gi) -> lugar.getOrDefault(gi.getId().getInscripcionId(), 0)))
                .forEach(gi -> porLetra.computeIfAbsent(gi.getGrupo().getLetra(), l -> new ArrayList<>())
                        .add(new SorteoGruposDetalle.Integrante(gi.getId().getInscripcionId(),
                                Partidos.nombre(gi.getInscripcion()))));
        List<SorteoGruposDetalle.GrupoSorteado> grupos = porLetra.entrySet().stream()
                .map(e -> new SorteoGruposDetalle.GrupoSorteado(e.getKey(), e.getValue()))
                .toList();
        return new SorteoGruposDetalle(torneo.getId(), torneo.getNombre(), SorteoGrupos.VERSION, torneo.getListaHash(),
                torneo.getListaSelladaEn(), inscripciones, torneo.getSemillaRonda(), torneo.getCompromisoHash(),
                torneo.getSemillaBracket(), torneo.getSemillaFirma(),
                torneo.getSemillaRonda() == null ? null : drand.url(torneo.getSemillaRonda()),
                ReglasGrupos.de(torneo).grupos(torneo.getCupoMaximo()), grupos);
    }

    private void auditar(Torneo torneo, Long organizadorId, int inscriptos, int grupos) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("grupos", "SORTEADOS");
        detalle.put("cantidad", grupos);
        detalle.put("semilla", torneo.getSemillaBracket());
        detalle.put("semillaRonda", torneo.getSemillaRonda());
        detalle.put("compromisoHash", torneo.getCompromisoHash());
        detalle.put("inscriptos", inscriptos);
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(usuarioRepository.getReferenceById(organizadorId));
        auditoria.setAccion(AccionAuditoria.SORTEO);
        auditoria.setEntidad("TORNEO");
        auditoria.setEntidadId(String.valueOf(torneo.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }
}
