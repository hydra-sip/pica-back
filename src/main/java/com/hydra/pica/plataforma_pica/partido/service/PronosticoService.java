package com.hydra.pica.plataforma_pica.partido.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.partido.domain.Eleccion;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.Pronostico;
import com.hydra.pica.plataforma_pica.partido.domain.PronosticoId;
import com.hydra.pica.plataforma_pica.partido.dto.LadoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.MiPronostico;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoPronostico;
import com.hydra.pica.plataforma_pica.partido.dto.PronosticoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.PronosticoRequest;
import com.hydra.pica.plataforma_pica.partido.dto.RondaBracket;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PronosticoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio de pronósticos de partidos (HU-306, T306-1).
 * Permite a los usuarios pronosticar cruces (1, X o 2) antes del inicio del partido,
 * y calcula automáticamente el puntaje obtenido (3 puntos acierto, 0 desacierto)
 * al confirmarse el resultado oficial.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PronosticoService {

    public static final int PUNTOS_ACIERTO = 3;
    public static final int PUNTOS_DESACIERTO = 0;

    private final Partidos partidos;
    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final PronosticoRepository pronosticoRepository;
    private final UsuarioRepository usuarioRepository;

    /**
     * Lista los partidos disponibles para pronósticos, con opción de filtro por torneo y estado de ventana.
     */
    @Transactional(readOnly = true)
    public List<PartidoPronostico> listarPartidos(Long usuarioId, Long torneoId, Boolean abiertoFiltro) {
        List<Partido> partidosList = partidoRepository.paraPronosticos(torneoId);
        if (partidosList.isEmpty()) {
            return List.of();
        }

        Map<Long, Pronostico> misPronosticos = usuarioId != null
                ? pronosticoRepository.delUsuario(usuarioId).stream()
                        .collect(Collectors.toMap(p -> p.getId().getPartidoId(), p -> p))
                : Map.of();

        Instant ahora = Instant.now();
        List<PartidoPronostico> resultado = new ArrayList<>();

        Map<Long, Integer> rondasPorTorneo = partidosList.stream()
                .map(p -> p.getTorneo().getId())
                .distinct()
                .collect(Collectors.toMap(id -> id, partidoRepository::rondasDelTorneo));

        for (Partido partido : partidosList) {
            List<Juega> lados = juegaRepository.delPartido(partido.getId());
            boolean abierto = estaAbierto(partido, lados, ahora);
            if (abiertoFiltro != null && abierto != abiertoFiltro) {
                continue;
            }
            int rondas = rondasPorTorneo.getOrDefault(partido.getTorneo().getId(), 1);
            String nombreRonda = RondaBracket.nombre(partido.getRonda(), rondas);
            LadoBracket local = Partidos.ladoBracket(lados, Lado.LOCAL);
            LadoBracket visitante = Partidos.ladoBracket(lados, Lado.VISITANTE);

            Pronostico mio = misPronosticos.get(partido.getId());
            MiPronostico miPronostico = mio == null ? null : new MiPronostico(
                    mio.getEleccion().getCodigo(),
                    mio.getPuntos(),
                    mio.getCreadoEn(),
                    mio.getModificadoEn());

            resultado.add(new PartidoPronostico(
                    partido.getId(),
                    partido.getTorneo().getId(),
                    partido.getTorneo().getNombre(),
                    partido.getRonda(),
                    nombreRonda,
                    partido.getPosicion(),
                    partido.getEstado(),
                    partido.getFechaProgramada(),
                    partido.getFechaProgramada(),
                    abierto,
                    local,
                    visitante,
                    miPronostico));
        }
        return resultado;
    }

    /**
     * Carga o modifica el pronóstico del usuario para un partido, validando que la ventana esté abierta.
     */
    @Transactional
    public PronosticoDetalle pronosticar(Long usuarioId, Long partidoId, PronosticoRequest request) {
        validarRequest(request);
        Eleccion eleccion = Eleccion.parsear(request.eleccion());

        Partido partido = partidos.buscarBloqueado(partidoId);
        List<Juega> lados = juegaRepository.delPartido(partidoId);

        validarVentanaPronostico(partido, lados);

        Pronostico pronostico = pronosticoRepository.findById(new PronosticoId(usuarioId, partidoId))
                .orElseGet(() -> {
                    Usuario usuario = usuarioRepository.getReferenceById(usuarioId);
                    return new Pronostico(usuario, partido, eleccion);
                });

        pronostico.setEleccion(eleccion);
        pronostico = pronosticoRepository.save(pronostico);

        return new PronosticoDetalle(
                partidoId,
                usuarioId,
                pronostico.getEleccion().getCodigo(),
                pronostico.getPuntos(),
                pronostico.getCreadoEn(),
                pronostico.getModificadoEn());
    }

    /**
     * Calcula los puntos de todos los pronósticos del partido al confirmarse el resultado oficial.
     */
    @Transactional
    public void calcularPuntos(Long partidoId, Long ganadorInscripcionId) {
        List<Pronostico> pronosticos = pronosticoRepository.delPartido(partidoId);
        if (pronosticos.isEmpty()) {
            return;
        }
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        Juega local = Partidos.lado(lados, Lado.LOCAL).orElse(null);
        Juega visitante = Partidos.lado(lados, Lado.VISITANTE).orElse(null);
        if (local == null || visitante == null) {
            return;
        }

        Eleccion resultadoReal;
        if (local.getPuntaje() != null && visitante.getPuntaje() != null) {
            if (local.getPuntaje() > visitante.getPuntaje()) {
                resultadoReal = Eleccion.LOCAL;
            } else if (local.getPuntaje() < visitante.getPuntaje()) {
                resultadoReal = Eleccion.VISITANTE;
            } else {
                resultadoReal = Eleccion.EMPATE;
            }
        } else {
            if (Boolean.TRUE.equals(local.getEsGanador())) {
                resultadoReal = Eleccion.LOCAL;
            } else if (Boolean.TRUE.equals(visitante.getEsGanador())) {
                resultadoReal = Eleccion.VISITANTE;
            } else {
                resultadoReal = Eleccion.EMPATE;
            }
        }

        for (Pronostico pronostico : pronosticos) {
            if (pronostico.getEleccion() == resultadoReal) {
                pronostico.setPuntos(PUNTOS_ACIERTO);
            } else {
                pronostico.setPuntos(PUNTOS_DESACIERTO);
            }
        }
        pronosticoRepository.saveAll(pronosticos);
        log.info("Puntos calculados para {} pronósticos del partido {}: resultado real {}",
                pronosticos.size(), partidoId, resultadoReal);
    }

    @EventListener
    public void onResultadoConfirmado(ResultadoConfirmado evento) {
        calcularPuntos(evento.partidoId(), evento.ganadorId());
    }

    /**
     * Valida que la ventana para pronosticar esté abierta; si no, lanza 409 PRONOSTICO_FUERA_DE_VENTANA.
     */
    private static void validarVentanaPronostico(Partido partido, List<Juega> lados) {
        EstadoTorneo torneoEstado = partido.getTorneo().getEstado();
        if (torneoEstado == EstadoTorneo.BORRADOR || torneoEstado == EstadoTorneo.CANCELADO) {
            throw new ConflictoException(CodigoError.PRONOSTICO_FUERA_DE_VENTANA,
                    "No se pueden pronosticar partidos de un torneo en estado " + torneoEstado);
        }
        if (partido.getEstado() != EstadoPartido.PROGRAMADO) {
            throw new ConflictoException(CodigoError.PRONOSTICO_FUERA_DE_VENTANA,
                    "El partido " + partido.getId() + " está " + partido.getEstado()
                            + ": la ventana de pronósticos ya cerró");
        }
        if (lados.size() < 2) {
            throw new ConflictoException(CodigoError.PRONOSTICO_FUERA_DE_VENTANA,
                    "El partido " + partido.getId() + " aún no tiene ambos rivales definidos");
        }
        Instant ahora = Instant.now();
        if (partido.getFechaProgramada() != null && ahora.isAfter(partido.getFechaProgramada())) {
            throw new ConflictoException(CodigoError.PRONOSTICO_FUERA_DE_VENTANA,
                    "La ventana de pronósticos cerró el " + partido.getFechaProgramada());
        }
    }

    private static boolean estaAbierto(Partido partido, List<Juega> lados, Instant ahora) {
        if (partido.getEstado() != EstadoPartido.PROGRAMADO) {
            return false;
        }
        EstadoTorneo torneoEstado = partido.getTorneo().getEstado();
        if (torneoEstado != EstadoTorneo.INSCRIPCION_CERRADA && torneoEstado != EstadoTorneo.EN_CURSO) {
            return false;
        }
        if (lados.size() < 2) {
            return false;
        }
        if (partido.getFechaProgramada() != null && ahora.isAfter(partido.getFechaProgramada())) {
            return false;
        }
        return true;
    }

    private static void validarRequest(PronosticoRequest request) {
        List<ErrorCampo> errores = new ArrayList<>();
        if (request == null || request.eleccion() == null || request.eleccion().isBlank()) {
            errores.add(new ErrorCampo("eleccion", ErrorCampo.Codigo.REQUERIDO, "Falta la elección"));
        } else {
            Eleccion eleccion = Eleccion.parsear(request.eleccion());
            if (eleccion == null) {
                errores.add(new ErrorCampo("eleccion", ErrorCampo.Codigo.VALOR_INVALIDO,
                        "Elección inválida: debe ser 1 (LOCAL), X (EMPATE) o 2 (VISITANTE)"));
            }
        }
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
    }
}
