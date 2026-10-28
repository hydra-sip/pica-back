package com.hydra.pica.plataforma_pica.partido.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.LadoBracket;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lo que comparten el bracket, la vista del partido, la carga del resultado y la evidencia: buscar el partido, saber
 * de qué lado es capitán quien hace la request y los nombres de cada parte.
 */
@Component
@RequiredArgsConstructor
public class Partidos {

    /** Ya no cambian: el ganador pasó a la ronda siguiente (o el torneo terminó). */
    public static final Set<EstadoPartido> CERRADOS =
            EnumSet.of(EstadoPartido.CONFIRMADO, EstadoPartido.CORREGIDO, EstadoPartido.WALKOVER);

    /** Se le puede cargar resultado y hacer check-in; el check-in (HU-308) no es requisito para cargarlo. */
    public static final Set<EstadoPartido> EN_JUEGO =
            EnumSet.of(EstadoPartido.PROGRAMADO, EstadoPartido.CHECK_IN, EstadoPartido.EN_JUEGO);

    private final PartidoRepository partidoRepository;

    /** El de un torneo en borrador o dado de baja no existe: 404. */
    public Partido buscar(Long id) {
        return partidoRepository.findById(id).filter(Partidos::visible).orElseThrow(() -> noEncontrado(id));
    }

    /** Con la fila bloqueada hasta el fin de la transacción. */
    public Partido buscarBloqueado(Long id) {
        return partidoRepository.buscarBloqueado(id).filter(Partidos::visible).orElseThrow(() -> noEncontrado(id));
    }

    /** El lado que responde quien hace la request: el del jugador inscripto o el del capitán del equipo. */
    public static Optional<Juega> deCapitan(List<Juega> lados, Long usuarioId) {
        return lados.stream().filter(j -> j.getInscripcion().titular().getId().equals(usuarioId)).findFirst();
    }

    /** 403 NO_ES_CAPITAN si quien hace la request no responde por ninguno de los dos lados. */
    public static Juega exigirCapitan(List<Juega> lados, Long usuarioId, Long partidoId) {
        return deCapitan(lados, usuarioId).orElseThrow(() -> new ProhibidoException(CodigoError.NO_ES_CAPITAN,
                "Solo el capitán de uno de los dos lados del partido " + partidoId + " puede hacerlo"));
    }

    /**
     * 409 PARTIDO_NO_EN_JUEGO si el torneo no está en curso, falta el rival o el partido ya tiene resultado.
     *
     * @param accion lo que no se puede hacer, para el mensaje: "le carga resultado", "hace check-in"
     */
    public static void exigirEnJuego(Partido partido, List<Juega> lados, String accion) {
        if (partido.getTorneo().getEstado() != EstadoTorneo.EN_CURSO || lados.size() < 2
                || !EN_JUEGO.contains(partido.getEstado())) {
            throw new ConflictoException(CodigoError.PARTIDO_NO_EN_JUEGO,
                    "El partido " + partido.getId() + " está " + partido.getEstado()
                            + (lados.size() < 2 ? " y sin rival definido" : "") + ": no se " + accion);
        }
    }

    public static Optional<Juega> lado(List<Juega> lados, Lado lado) {
        return lados.stream().filter(j -> j.getLado() == lado).findFirst();
    }

    public static LadoBracket ladoBracket(List<Juega> lados, Lado lado) {
        return lado(lados, lado)
                .map(j -> new LadoBracket(j.getId().getInscripcionId(), nombre(j.getInscripcion()), j.getPuntaje(),
                        j.getPenales(), j.getEsGanador()))
                .orElse(null);
    }

    /** El del equipo o, si se inscribe un jugador, su nombre y apellido. */
    public static String nombre(Inscripcion inscripcion) {
        return inscripcion.getEquipo() != null ? inscripcion.getEquipo().getNombre() : nombre(inscripcion.getUsuario());
    }

    public static String nombre(Usuario usuario) {
        Persona persona = usuario.getPersona();
        return persona == null ? usuario.getUsername() : persona.getNombres() + " " + persona.getApellidos();
    }

    private static boolean visible(Partido partido) {
        return partido.getTorneo().getEliminadoEn() == null && partido.getTorneo().getEstado() != EstadoTorneo.BORRADOR;
    }

    private static NoEncontradoException noEncontrado(Long id) {
        return new NoEncontradoException(CodigoError.PARTIDO_NO_ENCONTRADO, "No existe el partido " + id);
    }
}
