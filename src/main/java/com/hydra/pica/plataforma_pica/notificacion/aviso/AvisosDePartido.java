package com.hydra.pica.plataforma_pica.notificacion.aviso;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.notificacion.domain.TipoNotificacion;
import com.hydra.pica.plataforma_pica.notificacion.service.NotificacionService;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.service.Partidos;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Lo común a los avisos de un partido (HU-808): quiénes forman cada lado y el mismo aviso para los dos. */
@Component
@RequiredArgsConstructor
class AvisosDePartido {

    private static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter DIA_HORA = DateTimeFormatter.ofPattern("dd/MM 'a las' HH:mm");

    private final NominaRepository nominaRepository;
    private final NotificacionService notificacionService;

    /**
     * Para cada lado, a cada integrante: el jugador si se inscribió solo; si es un equipo, los de la nómina y el
     * capitán. {@code titulo} y {@code cuerpo} reciben el nombre del rival de ese lado.
     */
    List<Aviso> aLosDosLados(Partido partido, List<Juega> lados, TipoNotificacion tipo,
                             Function<String, String> titulo, Function<String, String> cuerpo,
                             Map<String, Object> datos) {
        List<Aviso> avisos = new ArrayList<>();
        for (Juega lado : lados) {
            String rival = lados.stream()
                    .filter(otro -> otro != lado)
                    .findFirst()
                    .map(otro -> Partidos.nombre(otro.getInscripcion()))
                    .orElseThrow();
            Map<String, Object> conPartido = new LinkedHashMap<>(datos);
            conPartido.put("torneoId", partido.getTorneo().getId());
            conPartido.put("partidoId", partido.getId());
            for (Long usuarioId : integrantes(lado.getInscripcion())) {
                avisos.add(new Aviso(usuarioId, tipo, titulo.apply(rival), cuerpo.apply(rival),
                        "/partidos/" + partido.getId(), conPartido));
            }
        }
        return avisos;
    }

    Set<Long> integrantes(Inscripcion inscripcion) {
        Set<Long> ids = new LinkedHashSet<>();
        if (inscripcion.getUsuario() != null) {
            ids.add(inscripcion.getUsuario().getId());
        }
        if (inscripcion.getEquipo() != null) {
            nominaRepository.jugadoresDe(inscripcion.getId()).stream().map(Usuario::getId).forEach(ids::add);
        }
        return ids;
    }

    /** En la hora de Argentina: el correo no sabe dónde está quien lo lee. */
    static String diaYHora(Instant instante) {
        return DIA_HORA.format(instante.atZone(ARGENTINA));
    }

    void enviar(List<Aviso> avisos) {
        if (avisos == null) {
            return;
        }
        avisos.forEach(aviso -> notificacionService.notificar(aviso.usuarioId(), aviso.tipo(), aviso.titulo(),
                aviso.cuerpo(), aviso.enlace(), aviso.datos()));
    }
}
