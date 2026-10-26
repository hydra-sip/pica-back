package com.hydra.pica.plataforma_pica.partido.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket.Cruce;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket.Cuadro;
import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.partido.dto.LadoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.RondaBracket;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.ArbitroDesignadoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generar y regenerar el bracket de eliminación directa (HU-207, PICA-215). El sorteo está en {@link SorteoBracket};
 * acá van las reglas y los partidos.
 *
 * <ul>
 * <li>Se genera con la inscripción cerrada y al menos dos inscripciones confirmadas, una sola vez. El torneo pasa a
 * EN_CURSO.</li>
 * <li>Se crean los partidos de todas las rondas; los de las siguientes, sin lados hasta que avance la llave. Un BYE es
 * un partido de primera ronda con un solo lado, ya CONFIRMADO, y quien lo recibe ya está en la segunda ronda.</li>
 * <li>Cada partido necesita un árbitro designado (FK de V9): se reparten en rueda entre los que aceptaron. Si ninguno
 * aceptó todavía (aceptar es la HU-807), entre los pendientes: igual solo arbitra el que aceptó
 * ({@link com.hydra.pica.plataforma_pica.torneo.service.Arbitraje}).</li>
 * <li>Regenerar es otro sorteo con otra semilla, mientras no haya ningún resultado cargado. Cada sorteo queda en la
 * auditoría, con la semilla anterior si la hubo: rearmar el cuadro hasta que guste queda a la vista.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BracketService {

    private static final Set<EstadoTorneo> CON_BRACKET =
            EnumSet.of(EstadoTorneo.INSCRIPCION_CERRADA, EstadoTorneo.EN_CURSO);

    private final TorneoRepository torneoRepository;
    private final InscripcionRepository inscripcionRepository;
    private final ArbitroDesignadoRepository arbitroDesignadoRepository;
    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional
    public BracketTorneo generar(Long organizadorId, Long torneoId) {
        Torneo torneo = propio(organizadorId, torneoId);
        if (torneo.getSemillaBracket() != null) {
            throw new ConflictoException(CodigoError.BRACKET_YA_GENERADO,
                    "El torneo " + torneoId + " ya tiene bracket: se puede regenerar mientras no haya resultados");
        }
        if (torneo.getEstado() != EstadoTorneo.INSCRIPCION_CERRADA) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_CERRADA,
                    "El torneo " + torneoId + " está " + torneo.getEstado() + ": el bracket se genera con la "
                            + "inscripción cerrada");
        }
        sortear(torneo, organizadorId, null);
        return armar(torneo);
    }

    /** Sin bracket todavía, es lo mismo que generarlo. */
    @Transactional
    public BracketTorneo regenerar(Long organizadorId, Long torneoId) {
        Torneo torneo = propio(organizadorId, torneoId);
        if (torneo.getSemillaBracket() == null) {
            return generar(organizadorId, torneoId);
        }
        if (!CON_BRACKET.contains(torneo.getEstado())) {
            throw new ConflictoException(CodigoError.TORNEO_NO_EDITABLE,
                    "El torneo " + torneoId + " está " + torneo.getEstado() + ": el bracket ya no se regenera");
        }
        if (juegaRepository.existsByPartidoTorneoIdAndPuntajeIsNotNull(torneoId)) {
            throw new ConflictoException(CodigoError.BRACKET_CON_RESULTADOS,
                    "El torneo " + torneoId + " ya tiene resultados cargados: el bracket no se regenera");
        }
        String anterior = torneo.getSemillaBracket();
        juegaRepository.borrarDelTorneo(torneoId);
        partidoRepository.borrarDelTorneo(torneoId);
        sortear(torneo, organizadorId, anterior);
        return armar(torneo);
    }

    // --- sorteo -----------------------------------------------------------------

    private void sortear(Torneo torneo, Long organizadorId, String semillaAnterior) {
        List<Long> inscriptos =
                inscripcionRepository.idsPorTorneoYEstado(torneo.getId(), EstadoInscripcion.CONFIRMADA);
        if (inscriptos.size() < 2) {
            throw new ConflictoException(CodigoError.PARTICIPANTES_INSUFICIENTES,
                    "El torneo " + torneo.getId() + " tiene " + inscriptos.size()
                            + " inscripciones confirmadas: hacen falta al menos 2");
        }
        List<Usuario> arbitros = arbitros(torneo.getId());
        if (arbitros.isEmpty()) {
            throw new ConflictoException(CodigoError.SIN_ARBITROS,
                    "El torneo " + torneo.getId() + " no tiene árbitros: cada partido necesita uno");
        }

        String semilla = SorteoBracket.nuevaSemilla();
        Cuadro cuadro = SorteoBracket.sortear(semilla, inscriptos);
        Map<Integer, List<Partido>> porRonda = crearPartidos(torneo, cuadro, arbitros);

        List<Juega> lados = new ArrayList<>();
        for (Cruce cruce : cuadro.cruces()) {
            Partido partido = porRonda.get(1).get(cruce.posicion() - 1);
            Juega local = new Juega(partido, inscripcionRepository.getReferenceById(cruce.local()), Lado.LOCAL);
            lados.add(local);
            if (cruce.bye()) {
                local.setEsGanador(true);
                partido.setEstado(EstadoPartido.CONFIRMADO);
                Partido siguiente = porRonda.get(2).get(SorteoBracket.posicionSiguiente(cruce.posicion()) - 1);
                lados.add(new Juega(siguiente, inscripcionRepository.getReferenceById(cruce.local()),
                        SorteoBracket.ladoSiguiente(cruce.posicion())));
            } else {
                lados.add(new Juega(partido, inscripcionRepository.getReferenceById(cruce.visitante()),
                        Lado.VISITANTE));
            }
        }
        juegaRepository.saveAll(lados);

        torneo.setSemillaBracket(semilla);
        torneo.setEstado(EstadoTorneo.EN_CURSO);
        auditar(torneo, organizadorId, semilla, semillaAnterior, inscriptos.size());
    }

    private List<Usuario> arbitros(Long torneoId) {
        List<ArbitroDesignado> designados = arbitroDesignadoRepository.delTorneo(torneoId);
        List<Usuario> aceptaron = conEstado(designados, EstadoDesignacion.ACEPTADO);
        return aceptaron.isEmpty() ? conEstado(designados, EstadoDesignacion.PENDIENTE) : aceptaron;
    }

    private static List<Usuario> conEstado(List<ArbitroDesignado> designados, EstadoDesignacion estado) {
        return designados.stream().filter(a -> a.getEstado() == estado).map(ArbitroDesignado::getUsuario).toList();
    }

    /** Todos, con el árbitro en rueda; IDENTITY: cada uno tiene id al guardarlo, que es lo que pide {@link Juega}. */
    private Map<Integer, List<Partido>> crearPartidos(Torneo torneo, Cuadro cuadro, List<Usuario> arbitros) {
        Map<Integer, List<Partido>> porRonda = new HashMap<>();
        int asignados = 0;
        for (int ronda = 1; ronda <= cuadro.rondas(); ronda++) {
            List<Partido> partidos = new ArrayList<>();
            for (int posicion = 1; posicion <= cuadro.partidosDeLaRonda(ronda); posicion++) {
                Partido partido = new Partido();
                partido.setTorneo(torneo);
                partido.setRonda(ronda);
                partido.setPosicion(posicion);
                partido.setArbitro(arbitros.get(asignados++ % arbitros.size()));
                partidos.add(partidoRepository.save(partido));
            }
            porRonda.put(ronda, partidos);
        }
        return porRonda;
    }

    private void auditar(Torneo torneo, Long organizadorId, String semilla, String anterior, int inscriptos) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("bracket", anterior == null ? "GENERADO" : "REGENERADO");
        detalle.put("semilla", semilla);
        detalle.put("semillaAnterior", anterior);
        detalle.put("inscriptos", inscriptos);
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(usuarioRepository.getReferenceById(organizadorId));
        auditoria.setAccion(AccionAuditoria.SORTEO);
        auditoria.setEntidad("TORNEO");
        auditoria.setEntidadId(String.valueOf(torneo.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }

    // --- vista ------------------------------------------------------------------

    private BracketTorneo armar(Torneo torneo) {
        List<Partido> partidos = partidoRepository.delTorneo(torneo.getId());
        Map<Long, List<Juega>> lados = juegaRepository.delTorneo(torneo.getId()).stream()
                .collect(Collectors.groupingBy(j -> j.getId().getPartidoId()));
        int rondas = partidos.stream().mapToInt(Partido::getRonda).max().orElse(0);

        Map<Integer, List<PartidoBracket>> porRonda = new TreeMap<>();
        for (Partido partido : partidos) {
            porRonda.computeIfAbsent(partido.getRonda(), r -> new ArrayList<>())
                    .add(partidoBracket(partido, lados.getOrDefault(partido.getId(), List.of())));
        }
        List<RondaBracket> lista = porRonda.entrySet().stream()
                .map(e -> new RondaBracket(e.getKey(), RondaBracket.nombre(e.getKey(), rondas), e.getValue()))
                .toList();
        return new BracketTorneo(torneo.getId(), torneo.getSemillaBracket(), torneo.getListaHash(), lista);
    }

    private static PartidoBracket partidoBracket(Partido partido, List<Juega> lados) {
        LadoBracket local = lado(lados, Lado.LOCAL);
        LadoBracket visitante = lado(lados, Lado.VISITANTE);
        boolean bye = partido.getRonda() == 1 && local != null && visitante == null;
        return new PartidoBracket(partido.getId(), partido.getRonda(), partido.getPosicion(), partido.getEstado(), bye,
                local, visitante, partido.getCancha() == null ? null : partido.getCancha().getNombre(),
                partido.getFechaProgramada(), nombre(partido.getArbitro()));
    }

    private static LadoBracket lado(List<Juega> lados, Lado lado) {
        return lados.stream()
                .filter(j -> j.getLado() == lado)
                .findFirst()
                .map(j -> new LadoBracket(j.getId().getInscripcionId(), nombre(j.getInscripcion()), j.getPuntaje(),
                        j.getPenales(), j.getEsGanador()))
                .orElse(null);
    }

    private static String nombre(Inscripcion inscripcion) {
        return inscripcion.getEquipo() != null ? inscripcion.getEquipo().getNombre() : nombre(inscripcion.getUsuario());
    }

    private static String nombre(Usuario usuario) {
        Persona persona = usuario.getPersona();
        return persona == null ? usuario.getUsername() : persona.getNombres() + " " + persona.getApellidos();
    }

    // --- dueño ------------------------------------------------------------------

    /** El de otro organizador responde 404, como en el resto del asistente. Con la fila bloqueada. */
    private Torneo propio(Long organizadorId, Long torneoId) {
        return torneoRepository.buscarBloqueado(torneoId)
                .filter(t -> t.getEliminadoEn() == null && t.getOrganizador().getId().equals(organizadorId))
                .orElseThrow(() -> noEncontrado(torneoId));
    }

    private static NoEncontradoException noEncontrado(Long id) {
        return new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + id);
    }
}
