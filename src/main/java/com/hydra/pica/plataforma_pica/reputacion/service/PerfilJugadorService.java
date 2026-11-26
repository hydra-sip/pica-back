package com.hydra.pica.plataforma_pica.reputacion.service;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.reputacion.dto.FairPlayDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilJugador;
import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilJugador.IndiceFairPlay;
import com.hydra.pica.plataforma_pica.torneo.dto.OrganizadorResumen;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** El perfil público del jugador (HU-801, T801-2). No calcula nada propio: junta lo de los otros servicios. */
@Service
@RequiredArgsConstructor
public class PerfilJugadorService {

    private final UsuarioRepository usuarioRepository;
    private final HistorialCompetitivoService historialService;
    private final FairPlayService fairPlayService;
    private final InsigniaService insigniaService;

    /** 404 USUARIO_NO_ENCONTRADO si el usuario no existe o está dado de baja; el bloqueado se muestra igual. */
    @Transactional(readOnly = true)
    public PerfilJugador ver(Long usuarioId) {
        // findById puede devolver al dado de baja (ya cargado en la sesión): se lo filtra a mano
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .filter(u -> u.getEliminadoEn() == null)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO,
                        "No existe el usuario " + usuarioId));
        OrganizadorResumen identidad = OrganizadorResumen.de(usuario);
        FairPlayDetalle fairPlay = fairPlayService.ver(usuarioId);
        return new PerfilJugador(usuario.getId(), identidad.username(), identidad.nombreCompleto(), null,
                historialService.de(usuarioId),
                new IndiceFairPlay(fairPlay.valor(), fairPlay.actualizadoEn()),
                historialService.ultimosTorneos(usuarioId),
                insigniaService.rifasGanadas(usuarioId),
                insigniaService.insigniasDe(usuarioId));
    }
}
