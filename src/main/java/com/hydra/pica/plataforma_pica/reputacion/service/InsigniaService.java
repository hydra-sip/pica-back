package com.hydra.pica.plataforma_pica.reputacion.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.reputacion.domain.CodigoInsignia;
import com.hydra.pica.plataforma_pica.reputacion.domain.Insignia;
import com.hydra.pica.plataforma_pica.reputacion.dto.InsigniaObtenida;
import com.hydra.pica.plataforma_pica.reputacion.dto.LogrosJugador;
import com.hydra.pica.plataforma_pica.reputacion.dto.RifaGanada;
import com.hydra.pica.plataforma_pica.reputacion.repository.InsigniaRepository;
import com.hydra.pica.plataforma_pica.reputacion.repository.RifasGanadasRepository;
import com.hydra.pica.plataforma_pica.reputacion.repository.UsuarioInsigniaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rifas ganadas y otorgamiento automático e idempotente de insignias por hitos de participación, podios, victorias,
 * rifas y check-ins (HU-804, T804-1).
 */
@Service
@RequiredArgsConstructor
public class InsigniaService {

    static final int RACHA_CHECKINS_REQUERIDA = 10;
    private static final Set<EstadoPartido> PARTIDOS_CERRADOS =
            EnumSet.of(EstadoPartido.CONFIRMADO, EstadoPartido.CORREGIDO, EstadoPartido.WALKOVER);

    private final InsigniaRepository insigniaRepository;
    private final UsuarioInsigniaRepository usuarioInsigniaRepository;
    private final RifasGanadasRepository rifasGanadasRepository;
    private final JuegaRepository juegaRepository;
    private final InscripcionRepository inscripcionRepository;
    private final NominaRepository nominaRepository;
    private final TorneoRepository torneoRepository;
    private final UsuarioRepository usuarioRepository;

    /** Las insignias obtenidas y las rifas ganadas del jugador. 404 USUARIO_NO_ENCONTRADO si no existe. */
    @Transactional(readOnly = true)
    public LogrosJugador ver(Long usuarioId) {
        if (!usuarioRepository.existsById(usuarioId)) {
            throw new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario " + usuarioId);
        }
        return new LogrosJugador(usuarioId, insigniasDe(usuarioId), rifasGanadas(usuarioId));
    }

    @Transactional(readOnly = true)
    public List<RifaGanada> rifasGanadas(Long usuarioId) {
        return rifasGanadasRepository.delUsuario(usuarioId).stream()
                .map(f -> new RifaGanada(f.getRifaId(), f.getRifaTitulo(), f.getTorneoId(), f.getTorneoNombre(),
                        f.getTorneoSlug(), f.getPuesto(), f.getNumeroGanador(), f.getFecha()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InsigniaObtenida> insigniasDe(Long usuarioId) {
        return usuarioInsigniaRepository.delUsuario(usuarioId).stream()
                .map(InsigniaObtenida::desde)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Insignia> catalogo() {
        return insigniaRepository.findAllByOrderByCodigoAsc();
    }

    /**
     * Otorga una insignia al usuario si todavía no la tiene.
     *
     * @return {@code true} si se otorgó ahora; {@code false} si ya la tenía
     */
    @Transactional
    public boolean otorgar(Long usuarioId, CodigoInsignia codigo) {
        if (usuarioId == null || codigo == null) {
            return false;
        }
        return usuarioInsigniaRepository.otorgarSiNoTiene(usuarioId, codigo.name(), Instant.now()) > 0;
    }

    @Transactional
    public void evaluarResultadoConfirmado(Long torneoId, Long partidoId, boolean torneoFinalizado) {
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        if (lados.size() == 2 && PARTIDOS_CERRADOS.contains(lados.getFirst().getPartido().getEstado())) {
            for (Juega lado : lados) {
                otorgarATodos(integrantes(lado.getInscripcion()), CodigoInsignia.PRIMER_TORNEO_JUGADO);
            }
        }
        if (torneoFinalizado) {
            evaluarTorneoFinalizado(torneoId);
        }
    }

    @Transactional
    public void evaluarTorneoFinalizado(Long torneoId) {
        boolean finalizado = torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                .filter(t -> t.getEstado() == EstadoTorneo.FINALIZADO)
                .isPresent();
        if (!finalizado) {
            return;
        }
        List<Inscripcion> confirmadas = inscripcionRepository.findByTorneoIdAndEstadoInOrderByIdAsc(
                torneoId, List.of(EstadoInscripcion.CONFIRMADA));
        for (Inscripcion inscripcion : confirmadas) {
            Integer posicion = inscripcion.getPosicion();
            if (posicion == null) {
                continue;
            }
            Set<Long> usuarios = integrantes(inscripcion);
            if (posicion == 1) {
                otorgarATodos(usuarios, CodigoInsignia.PRIMER_TORNEO_GANADO);
                otorgarATodos(usuarios, CodigoInsignia.PRIMER_PODIO);
            } else if (posicion >= 1 && posicion <= 3) {
                otorgarATodos(usuarios, CodigoInsignia.PRIMER_PODIO);
            }
        }
        Map<Long, List<Juega>> porPartido = juegaRepository.delTorneo(torneoId).stream()
                .collect(Collectors.groupingBy(j -> j.getId().getPartidoId()));
        for (List<Juega> lados : porPartido.values()) {
            if (lados.size() == 2 && PARTIDOS_CERRADOS.contains(lados.getFirst().getPartido().getEstado())) {
                for (Juega lado : lados) {
                    otorgarATodos(integrantes(lado.getInscripcion()), CodigoInsignia.PRIMER_TORNEO_JUGADO);
                }
            }
        }
    }

    @Transactional
    public void evaluarRifaSorteada(Long rifaId) {
        for (Long ganadorId : rifasGanadasRepository.ganadoresDeRifa(rifaId)) {
            otorgar(ganadorId, CodigoInsignia.PRIMERA_RIFA_GANADA);
        }
    }

    @Transactional
    public void evaluarCheckin(Long inscripcionId) {
        inscripcionRepository.findById(inscripcionId).ifPresent(inscripcion -> {
            for (Long usuarioId : integrantes(inscripcion)) {
                if (alcanzoRachaCheckins(usuarioId)) {
                    otorgar(usuarioId, CodigoInsignia.DIEZ_CHECKINS_SEGUIDOS);
                }
            }
        });
    }

    private boolean alcanzoRachaCheckins(Long usuarioId) {
        int seguidos = 0;
        for (String tipo : usuarioInsigniaRepository.rachaCheckinsRecientes(usuarioId)) {
            if ("CHECKIN".equals(tipo)) {
                seguidos++;
                if (seguidos >= RACHA_CHECKINS_REQUERIDA) {
                    return true;
                }
            } else {
                break;
            }
        }
        return false;
    }

    private void otorgarATodos(Set<Long> usuarioIds, CodigoInsignia codigo) {
        for (Long usuarioId : usuarioIds) {
            otorgar(usuarioId, codigo);
        }
    }

    private Set<Long> integrantes(Inscripcion inscripcion) {
        Set<Long> ids = new LinkedHashSet<>();
        if (inscripcion.getUsuario() != null) {
            ids.add(inscripcion.getUsuario().getId());
        }
        if (inscripcion.esDeEquipo()) {
            nominaRepository.jugadoresDe(inscripcion.getId()).forEach(u -> ids.add(u.getId()));
            if (inscripcion.titular() != null) {
                ids.add(inscripcion.titular().getId());
            }
        }
        return ids;
    }
}
