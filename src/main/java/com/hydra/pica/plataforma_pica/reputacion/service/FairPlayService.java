package com.hydra.pica.plataforma_pica.reputacion.service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.reputacion.domain.EventoFairPlay;
import com.hydra.pica.plataforma_pica.reputacion.domain.FairPlay;
import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import com.hydra.pica.plataforma_pica.reputacion.domain.TipoEventoFairPlay;
import com.hydra.pica.plataforma_pica.reputacion.dto.FairPlayDetalle;
import com.hydra.pica.plataforma_pica.reputacion.repository.EventoFairPlayRepository;
import com.hydra.pica.plataforma_pica.reputacion.repository.FairPlayRepository;
import com.hydra.pica.plataforma_pica.reputacion.repository.SancionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El índice de fair play (HU-802, T802-1). Fórmula, con los parámetros de la plataforma (V57, editables en HU-702):
 * <ul>
 *   <li>sin movimientos, {@code FAIR_PLAY_INICIAL} (100);</li>
 *   <li>un check-in cumplido suma {@code FAIR_PLAY_CHECKIN} (1) sin pasar de {@code FAIR_PLAY_MAXIMO} (100): el índice
 *       se mantiene o mejora;</li>
 *   <li>una ausencia sin aviso (no hizo check-in y el partido quedó en walkover o sin presentes) resta
 *       {@code FAIR_PLAY_AUSENCIA} (10);</li>
 *   <li>una sanción (HU-803) resta {@code FAIR_PLAY_SANCION_LEVE}, {@code _MEDIA} o {@code _GRAVE} (5, 15, 30);</li>
 *   <li>nunca baja de 0.</li>
 * </ul>
 * Se calcula sobre el valor anterior, evento por evento: si el admin cambia un parámetro, rige desde ahí y no
 * reescribe lo que ya pasó. Lo mueven solos los eventos ({@link FairPlayAutomatico}); nadie lo edita a mano.
 */
@Service
@RequiredArgsConstructor
public class FairPlayService {

    private final FairPlayRepository fairPlayRepository;
    private final EventoFairPlayRepository eventoRepository;
    private final ParametrosPlataforma parametros;
    private final TorneoRepository torneoRepository;
    private final PartidoRepository partidoRepository;
    private final SancionRepository sancionRepository;
    private final UsuarioRepository usuarioRepository;
    private final InscripcionRepository inscripcionRepository;

    /** El índice y su historial. 404 USUARIO_NO_ENCONTRADO si no existe. */
    @Transactional(readOnly = true)
    public FairPlayDetalle ver(Long usuarioId) {
        if (!usuarioRepository.existsById(usuarioId)) {
            throw new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario " + usuarioId);
        }
        FairPlay actual = fairPlayRepository.findById(usuarioId).orElse(null);
        return new FairPlayDetalle(usuarioId,
                actual == null ? parametros.puntos(ClaveParametro.FAIR_PLAY_INICIAL) : actual.getValor(),
                actual == null ? null : actual.getActualizadoEn(),
                eventoRepository.delUsuario(usuarioId).stream().map(FairPlayDetalle.Movimiento::de).toList());
    }

    /** El índice de cada uno, para la lista de inscriptos; el que nunca se movió tiene el inicial. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> indices(Collection<Long> usuarioIds) {
        int inicial = parametros.puntos(ClaveParametro.FAIR_PLAY_INICIAL);
        Map<Long, Integer> indices = new HashMap<>();
        usuarioIds.forEach(id -> indices.put(id, inicial));
        if (!usuarioIds.isEmpty()) {
            fairPlayRepository.findByUsuarioIdIn(usuarioIds).forEach(f -> indices.put(f.getUsuarioId(), f.getValor()));
        }
        return indices;
    }

    /** Un check-in o una ausencia: mueve el índice de quien responde por la inscripción. */
    @Transactional
    public void registrarDeInscripcion(Long inscripcionId, TipoEventoFairPlay tipo, Long torneoId, Long partidoId) {
        Long titular = inscripcionRepository.findById(inscripcionId).orElseThrow().titular().getId();
        registrar(titular, tipo, torneoId, partidoId, null, null);
    }

    /** Una sanción: una sola vez, aunque el evento llegue dos veces. */
    @Transactional
    public void registrarSancion(Long usuarioId, Long torneoId, Long sancionId, Gravedad gravedad) {
        if (!eventoRepository.existsBySancionId(sancionId)) {
            registrar(usuarioId, TipoEventoFairPlay.SANCION, torneoId, null, sancionId, gravedad);
        }
    }

    /**
     * Mueve el índice del usuario por un evento y lo deja en el historial. Bloquea su fila: dos eventos a la vez no se
     * pisan. {@code partidoId} va con el check-in y la ausencia; {@code sancionId} y {@code gravedad}, con la sanción.
     */
    @Transactional
    public EventoFairPlay registrar(Long usuarioId, TipoEventoFairPlay tipo, Long torneoId, Long partidoId,
                                    Long sancionId, Gravedad gravedad) {
        fairPlayRepository.crearSiFalta(usuarioId, parametros.puntos(ClaveParametro.FAIR_PLAY_INICIAL));
        FairPlay indice = fairPlayRepository.bloquear(usuarioId).orElseThrow();
        int anterior = indice.getValor();
        int nuevo = switch (tipo) {
            case CHECKIN -> Math.max(anterior, Math.min(parametros.puntos(ClaveParametro.FAIR_PLAY_MAXIMO),
                    anterior + parametros.puntos(ClaveParametro.FAIR_PLAY_CHECKIN)));
            case AUSENCIA -> Math.max(0, anterior - parametros.puntos(ClaveParametro.FAIR_PLAY_AUSENCIA));
            case SANCION -> Math.max(0, anterior - parametros.puntos(resta(gravedad)));
        };
        Instant ahora = Instant.now();
        indice.setValor(nuevo);
        indice.setActualizadoEn(ahora);

        EventoFairPlay evento = new EventoFairPlay();
        evento.setUsuarioId(usuarioId);
        evento.setTipo(tipo);
        evento.setDelta(nuevo - anterior);
        evento.setValorResultante(nuevo);
        evento.setTorneo(torneoRepository.getReferenceById(torneoId));
        evento.setPartido(partidoId == null ? null : partidoRepository.getReferenceById(partidoId));
        evento.setSancion(sancionId == null ? null : sancionRepository.getReferenceById(sancionId));
        evento.setCreadoEn(ahora);
        return eventoRepository.save(evento);
    }

    private static ClaveParametro resta(Gravedad gravedad) {
        return switch (gravedad) {
            case LEVE -> ClaveParametro.FAIR_PLAY_SANCION_LEVE;
            case MEDIA -> ClaveParametro.FAIR_PLAY_SANCION_MEDIA;
            case GRAVE -> ClaveParametro.FAIR_PLAY_SANCION_GRAVE;
        };
    }
}
