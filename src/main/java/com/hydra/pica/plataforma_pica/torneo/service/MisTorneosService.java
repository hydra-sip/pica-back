package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.ConteoInscripciones;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.MiTorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.dto.MisTorneos;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.BusquedaSinTildes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PagedModel.PageMetadata;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mis torneos del organizador logueado (HU-201, PICA-201): los suyos, borradores incluidos y sin los dados de baja.
 * La búsqueda por nombre no distingue mayúsculas ni tildes y exige todas las palabras; la disciplina se compara
 * entera, sin mayúsculas.
 *
 * Las cantidades por estado son para las pestañas: aplican la búsqueda y la disciplina, no el estado, así cada
 * pestaña dice cuántos hay de lo que se está buscando.
 *
 * El pozo es lo que va a los competidores: valor de inscripción × participantes, porque la inscripción va entera al
 * pozo (PICA-283). En BORRADOR se estima con el cupo completo; publicado, con las inscripciones confirmadas. La rifa no
 * se suma todavía.
 */
@Service
@RequiredArgsConstructor
public class MisTorneosService {


    private final TorneoRepository torneoRepository;
    private final InscripcionRepository inscripcionRepository;
    private final EntityManager entityManager;

    /** El último que se tocó primero; el orden no se elige desde afuera. */
    @Transactional(readOnly = true)
    public MisTorneos listar(Long organizadorId, String q, EstadoTorneo estado, String disciplina, Pageable pageable) {
        Specification<Torneo> filtros = filtros(organizadorId, q, disciplina);
        Specification<Torneo> conEstado = estado == null ? filtros
                : filtros.and((root, query, cb) -> cb.equal(root.get("estado"), estado));
        Pageable orden = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "modificadoEn", "id"));

        Page<Torneo> pagina = torneoRepository.findAll(conEstado, orden);
        Map<Long, Long> confirmadas = confirmadas(pagina.getContent());
        List<MiTorneoResumen> filas = pagina.getContent().stream()
                .map(t -> {
                    long inscriptos = confirmadas.getOrDefault(t.getId(), 0L);
                    return MiTorneoResumen.de(t, inscriptos, pozo(t, inscriptos));
                })
                .toList();
        PageMetadata metadata = new PageMetadata(pagina.getSize(), pagina.getNumber(), pagina.getTotalElements(),
                pagina.getTotalPages());
        return new MisTorneos(filas, metadata, porEstado(filtros),
                torneoRepository.disciplinasDelOrganizador(organizadorId));
    }

    private Map<Long, Long> confirmadas(List<Torneo> torneos) {
        if (torneos.isEmpty()) {
            return Map.of();
        }
        return inscripcionRepository
                .contarPorTorneoIdInYEstado(torneos.stream().map(Torneo::getId).toList(), EstadoInscripcion.CONFIRMADA)
                .stream()
                .collect(Collectors.toMap(ConteoInscripciones::getTorneoId, ConteoInscripciones::getCantidad));
    }

    private Map<EstadoTorneo, Long> porEstado(Specification<Torneo> filtros) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Torneo> torneo = query.from(Torneo.class);
        query.multiselect(torneo.get("estado"), cb.count(torneo))
                .where(filtros.toPredicate(torneo, query, cb))
                .groupBy(torneo.get("estado"));

        Map<EstadoTorneo, Long> cantidades = new EnumMap<>(EstadoTorneo.class);
        for (EstadoTorneo estado : EstadoTorneo.values()) {
            cantidades.put(estado, 0L);
        }
        entityManager.createQuery(query).getResultList()
                .forEach(fila -> cantidades.put(fila.get(0, EstadoTorneo.class), fila.get(1, Long.class)));
        return cantidades;
    }

    private static Specification<Torneo> filtros(Long organizadorId, String q, String disciplina) {
        return (root, query, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            condiciones.add(cb.equal(root.get("organizador").get("id"), organizadorId));
            condiciones.add(cb.isNull(root.get("eliminadoEn")));
            for (String patron : BusquedaSinTildes.patronesPorPalabra(q)) {
                condiciones.add(BusquedaSinTildes.algunaContiene(cb, patron, List.of(root.<String>get("nombre"))));
            }
            if (disciplina != null && !disciplina.isBlank()) {
                condiciones.add(cb.equal(cb.lower(root.get("disciplina")), disciplina.strip().toLowerCase(Locale.ROOT)));
            }
            return cb.and(condiciones.toArray(Predicate[]::new));
        };
    }

    /** Null si falta el valor o (en BORRADOR) el cupo. */
    static BigDecimal pozo(Torneo torneo, long inscriptos) {
        if (torneo.getValorInscripcion() == null) {
            return null;
        }
        Integer participantes = torneo.getEstado() == EstadoTorneo.BORRADOR
                ? torneo.getCupoMaximo()
                : Integer.valueOf((int) inscriptos);
        if (participantes == null) {
            return null;
        }
        return torneo.getValorInscripcion().multiply(BigDecimal.valueOf(participantes));
    }
}
