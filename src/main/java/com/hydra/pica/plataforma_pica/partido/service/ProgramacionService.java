package com.hydra.pica.plataforma_pica.partido.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Programar un partido (HU-308, PICA-250): su organizador le pone fecha y hora, de la que sale la ventana de check-in.
 * Solo PROGRAMADO: con un check-in hecho el partido pasa a CHECK_IN y la fecha queda fija.
 */
@Service
@RequiredArgsConstructor
public class ProgramacionService {

    private static final String CAMPO = "fechaProgramada";

    private final Partidos partidos;
    private final PartidoService partidoService;
    private final ResultadoService resultadoService;

    @Transactional
    public PartidoDetalle programar(Long usuarioId, Long partidoId, Instant fechaProgramada) {
        Partido partido = partidos.buscarBloqueado(partidoId);
        if (!partido.getTorneo().getOrganizador().getId().equals(usuarioId)) {
            // como el resto de la consola: un torneo ajeno no existe
            throw new NoEncontradoException(CodigoError.PARTIDO_NO_ENCONTRADO, "No existe el partido " + partidoId);
        }
        EstadoTorneo torneo = partido.getTorneo().getEstado();
        if (partido.getEstado() != EstadoPartido.PROGRAMADO
                || (torneo != EstadoTorneo.INSCRIPCION_CERRADA && torneo != EstadoTorneo.EN_CURSO)) {
            throw new ConflictoException(CodigoError.PARTIDO_NO_PROGRAMABLE, "El partido " + partidoId + " está "
                    + partido.getEstado() + " en un torneo " + torneo + ": solo se programa uno PROGRAMADO");
        }
        validar(fechaProgramada, partido.getTorneo());
        partido.setFechaProgramada(fechaProgramada);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("resultado", "PROGRAMADO");
        detalle.put("fechaProgramada", fechaProgramada.toString());
        resultadoService.auditar(partido, usuarioId, detalle);
        return partidoService.detalle(partido, usuarioId);
    }

    /** Futura y dentro de las fechas del torneo; sin fecha no se programa (no se desprograma). */
    private static void validar(Instant fecha, Torneo torneo) {
        List<ErrorCampo> errores = new ArrayList<>();
        if (fecha == null) {
            errores.add(new ErrorCampo(CAMPO, ErrorCampo.Codigo.REQUERIDO, "Falta la fecha del partido"));
        } else if (fecha.isBefore(Instant.now())) {
            errores.add(new ErrorCampo(CAMPO, ErrorCampo.Codigo.RANGO_INVALIDO, "No puede ser pasada"));
        } else if (torneo.getFechaInicio() != null && fecha.isBefore(torneo.getFechaInicio())) {
            errores.add(new ErrorCampo(CAMPO, ErrorCampo.Codigo.RANGO_INVALIDO,
                    "No puede ser anterior al inicio del torneo"));
        } else if (torneo.getFechaFin() != null && fecha.isAfter(torneo.getFechaFin())) {
            errores.add(new ErrorCampo(CAMPO, ErrorCampo.Codigo.RANGO_INVALIDO,
                    "No puede ser posterior al fin del torneo"));
        }
        ResultadoService.lanzarSiHay(errores);
    }
}
