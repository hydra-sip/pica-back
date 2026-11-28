package com.hydra.pica.plataforma_pica.reputacion.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.reputacion.domain.EventoIndiceOrganizador;
import com.hydra.pica.plataforma_pica.reputacion.domain.IndiceOrganizador;
import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilOrganizador;
import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilOrganizador.MovimientoIndice;
import com.hydra.pica.plataforma_pica.reputacion.repository.EventoIndiceOrganizadorRepository;
import com.hydra.pica.plataforma_pica.reputacion.repository.IndiceOrganizadorRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoCancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.OrganizadorResumen;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.repository.CancelacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoCancelacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoPublicoService;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Perfil público del organizador y cálculo de su índice de confiabilidad (HU-806, T806-1).
 *
 * <ul>
 *   <li>Contadores de torneos organizados (publicados, excluye borradores y eliminados), finalizados y cancelados;</li>
 *   <li>Índice inicial configurable ({@code INDICE_ORGANIZADOR_INICIAL}, 100 por defecto) que disminuye por cada
 *       cancelación resuelta en {@code INDICE_ORGANIZADOR_CANCELACION} (sin gastos) o
 *       {@code INDICE_ORGANIZADOR_CANCELACION_CON_GASTOS} (con gastos), sin bajar de 0;</li>
 *   <li>Registro idempotente por torneo cancelado ({@link IndiceOrganizadorAutomatico}).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PerfilOrganizadorService {

    private final UsuarioRepository usuarioRepository;
    private final TorneoRepository torneoRepository;
    private final CancelacionRepository cancelacionRepository;
    private final GastoCancelacionRepository gastoCancelacionRepository;
    private final IndiceOrganizadorRepository indiceOrganizadorRepository;
    private final EventoIndiceOrganizadorRepository eventoRepository;
    private final ParametrosPlataforma parametros;
    private final TorneoPublicoService torneoPublicoService;
    private final Clock clock;

    /** Perfil público del organizador. 404 USUARIO_NO_ENCONTRADO si el usuario no existe. */
    @Transactional(readOnly = true)
    public PerfilOrganizador ver(Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO,
                        "No existe el usuario " + usuarioId));
        OrganizadorResumen identidad = OrganizadorResumen.de(usuario);
        int organizados = (int) torneoRepository.contarPublicadosDelOrganizador(usuarioId);
        int finalizados = (int) torneoRepository.contarDelOrganizadorConEstado(usuarioId, EstadoTorneo.FINALIZADO);
        int cancelados = (int) torneoRepository.contarDelOrganizadorConEstado(usuarioId, EstadoTorneo.CANCELADO);

        IndiceOrganizador actual = indiceOrganizadorRepository.findById(usuarioId).orElse(null);
        int indice = actual == null ? parametros.puntos(ClaveParametro.INDICE_ORGANIZADOR_INICIAL) : actual.getValor();
        Instant actualizadoEn = actual == null ? null : actual.getActualizadoEn();
        List<MovimientoIndice> historial = eventoRepository.delUsuario(usuarioId).stream()
                .map(MovimientoIndice::de)
                .toList();
        List<TorneoResumen> proximos = torneoPublicoService.proximosDelOrganizador(usuarioId);

        return new PerfilOrganizador(
                usuario.getId(),
                identidad.username(),
                identidad.nombreCompleto(),
                null,
                organizados,
                finalizados,
                cancelados,
                indice,
                actualizadoEn,
                historial,
                proximos);
    }

    /**
     * Registra la penalización por la cancelación de un torneo sobre el índice del organizador. Es idempotente:
     * aunque se dispare más de un evento para el mismo torneo cancelado, descuenta una sola vez.
     */
    @Transactional
    public EventoIndiceOrganizador registrarCancelacion(Long torneoId) {
        if (eventoRepository.existsByTorneoId(torneoId)) {
            return null;
        }
        Torneo torneo = torneoRepository.findById(torneoId).orElse(null);
        if (torneo == null || torneo.getEstado() != EstadoTorneo.CANCELADO) {
            return null;
        }
        Long organizadorId = torneo.getOrganizador().getId();
        Cancelacion cancelacion = cancelacionRepository.findByTorneoId(torneoId).orElse(null);
        boolean conGastos = cancelacion != null && (cancelacion.getEstado() == EstadoCancelacion.APROBADA
                || gastoCancelacionRepository.totalDeLaCancelacion(cancelacion.getId()).signum() > 0);

        indiceOrganizadorRepository.crearSiFalta(
                organizadorId, parametros.puntos(ClaveParametro.INDICE_ORGANIZADOR_INICIAL));
        IndiceOrganizador indice = indiceOrganizadorRepository.bloquear(organizadorId).orElseThrow();
        int resta = parametros.puntos(conGastos
                ? ClaveParametro.INDICE_ORGANIZADOR_CANCELACION_CON_GASTOS
                : ClaveParametro.INDICE_ORGANIZADOR_CANCELACION);
        int anterior = indice.getValor();
        int nuevo = Math.max(0, anterior - resta);
        Instant ahora = Instant.now(clock);
        indice.setValor(nuevo);
        indice.setActualizadoEn(ahora);

        EventoIndiceOrganizador evento = new EventoIndiceOrganizador();
        evento.setUsuarioId(organizadorId);
        evento.setTorneo(torneo);
        evento.setConGastos(conGastos);
        evento.setDelta(nuevo - anterior);
        evento.setValorResultante(nuevo);
        evento.setCreadoEn(ahora);
        return eventoRepository.save(evento);
    }
}
