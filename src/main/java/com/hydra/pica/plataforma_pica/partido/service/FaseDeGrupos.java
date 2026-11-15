package com.hydra.pica.plataforma_pica.partido.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.domain.CrucesLlave;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.FasePartido;
import com.hydra.pica.plataforma_pica.partido.domain.Grupo;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket;
import com.hydra.pica.plataforma_pica.partido.domain.TablaGrupo;
import com.hydra.pica.plataforma_pica.partido.event.LlaveSorteada;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.ArbitroDesignadoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * La fase de grupos en marcha (CR-02), el equivalente de {@link AvanceLlave} para los grupos.
 *
 * <ul>
 * <li>Un partido de grupos no avanza: el resultado suma a la tabla. Puede ser empate (los dos sin ganador).</li>
 * <li>Con un grupo terminado, si un empate que toca un puesto que clasifica no se resuelve con la tabla
 * ({@link TablaGrupo}), se arma el partido de desempate: un mini cuadro de eliminación directa entre los empatados,
 * con BYE si son impares, sorteado con la semilla del torneo. Si hay más de un empate así en el grupo, se juegan de
 * a uno, de arriba hacia abajo.</li>
 * <li>Con todos los grupos terminados y sin desempates por jugar, los que pasan llenan la llave con cruces fijos
 * ({@link CrucesLlave}): 1A–2B, 1B–2A… Desde ahí sigue {@link AvanceLlave}.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class FaseDeGrupos {

    private final ApplicationEventPublisher eventos;
    private final TablaGruposService tablaGruposService;
    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final InscripcionRepository inscripcionRepository;
    private final ArbitroDesignadoRepository arbitroDesignadoRepository;
    private final AuditoriaRepository auditoriaRepository;

    /**
     * Cierra un partido de grupos. Dentro de la transacción de quien lo llama, con la fila del partido ya bloqueada.
     *
     * @param estado CONFIRMADO, CORREGIDO o WALKOVER
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmar(Partido partido, List<Juega> lados, EstadoPartido estado) {
        partido.setEstado(estado);
        Long ganador = lados.stream()
                .filter(j -> Boolean.TRUE.equals(j.getEsGanador()))
                .map(j -> j.getId().getInscripcionId())
                .findFirst()
                .orElse(null);
        eventos.publishEvent(new ResultadoConfirmado(partido.getTorneo().getId(), partido.getId(), ganador, false));
        terminado(partido);
    }

    /**
     * Cierra un partido de desempate: el ganador pasa a la ronda siguiente de su mini cuadro; si era la final del
     * cuadro, el empate quedó resuelto.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmarDesempate(Partido partido, List<Juega> lados, EstadoPartido estado) {
        Juega ganador = lados.stream()
                .filter(j -> Boolean.TRUE.equals(j.getEsGanador()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("El partido " + partido.getId() + " no tiene ganador"));
        partido.setEstado(estado);
        Torneo torneo = partido.getTorneo();
        TablaGruposService.Datos datos = tablaGruposService.datos(torneo);
        TablaGruposService.CuadroDesempate cuadro = TablaGruposService.cuadros(partido.getGrupo(), datos).stream()
                .filter(c -> partido.getRonda() >= c.desde() && partido.getRonda() <= c.ultima())
                .findFirst()
                .orElseThrow();
        if (partido.getRonda() < cuadro.ultima()) {
            Partido siguiente = cuadro.partidos().stream()
                    .filter(p -> p.getRonda() == partido.getRonda() + 1
                            && p.getPosicion() == SorteoBracket.posicionSiguiente(partido.getPosicion()))
                    .findFirst()
                    .orElseThrow();
            juegaRepository.save(new Juega(siguiente, ganador.getInscripcion(),
                    SorteoBracket.ladoSiguiente(partido.getPosicion())));
        }
        eventos.publishEvent(new ResultadoConfirmado(torneo.getId(), partido.getId(),
                ganador.getId().getInscripcionId(), false));
        terminado(partido);
    }

    /**
     * Un partido de grupos o de desempate que ya no se juega: confirmado, por ausencia o sin presentes. Si con eso
     * terminó un grupo, arma lo que siga: el desempate o, con todos listos, la llave.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void terminado(Partido partido) {
        Torneo torneo = partido.getTorneo();
        if (juegaRepository.existsByPartidoTorneoIdAndPartidoFase(torneo.getId(), FasePartido.ELIMINACION)) {
            return; // la llave ya está armada
        }
        TablaGruposService.Datos datos = tablaGruposService.datos(torneo);
        List<List<Long>> clasificados = new ArrayList<>();
        boolean listos = true;
        for (Grupo grupo : datos.grupos()) {
            if (!TablaGruposService.terminado(grupo, datos)) {
                listos = false;
                continue;
            }
            if (TablaGruposService.cuadros(grupo, datos).stream().anyMatch(c -> !c.terminado(datos))) {
                listos = false;
                continue;
            }
            TablaGrupo.Tabla tabla = tablaGruposService.tabla(torneo, grupo, datos);
            if (!tabla.desempatesPendientes().isEmpty()) {
                armarDesempate(torneo, grupo, tabla.desempatesPendientes().getFirst(), datos);
                listos = false;
                continue;
            }
            clasificados.add(tabla.filas().stream()
                    .limit(torneo.getClasificanPorGrupo())
                    .map(TablaGrupo.Fila::inscripcionId)
                    .toList());
        }
        if (listos) {
            armarLlave(torneo, clasificados);
        }
    }

    // --- desempate --------------------------------------------------------------

    private void armarDesempate(Torneo torneo, Grupo grupo, List<Long> empatados, TablaGruposService.Datos datos) {
        int desde = 1 + TablaGruposService.cuadros(grupo, datos).stream()
                .mapToInt(TablaGruposService.CuadroDesempate::rondas).sum();
        String semilla = torneo.getSemillaBracket() + ":desempate:" + grupo.getLetra() + ":" + desde;
        SorteoBracket.Cuadro cuadro = SorteoBracket.sortear(semilla, empatados);
        List<Usuario> arbitros = arbitros(torneo);
        Map<Integer, List<Partido>> porRonda = new LinkedHashMap<>();
        int asignados = 0;
        for (int ronda = 1; ronda <= cuadro.rondas(); ronda++) {
            List<Partido> partidos = new ArrayList<>();
            for (int posicion = 1; posicion <= cuadro.partidosDeLaRonda(ronda); posicion++) {
                Partido partido = new Partido();
                partido.setTorneo(torneo);
                partido.setFase(FasePartido.DESEMPATE);
                partido.setGrupo(grupo);
                partido.setRonda(desde + ronda - 1);
                partido.setPosicion(posicion);
                partido.setArbitro(arbitros.get(asignados++ % arbitros.size()));
                partidos.add(partidoRepository.save(partido));
            }
            porRonda.put(ronda, partidos);
        }
        List<Juega> lados = new ArrayList<>();
        for (SorteoBracket.Cruce cruce : cuadro.cruces()) {
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

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("desempate", "ARMADO");
        detalle.put("grupo", grupo.getLetra());
        detalle.put("empatados", empatados);
        detalle.put("semilla", semilla);
        auditar(torneo, detalle);
    }

    /** Los que aceptaron la designación, en rueda, como en la llave. */
    private List<Usuario> arbitros(Torneo torneo) {
        List<Usuario> arbitros = arbitroDesignadoRepository.delTorneo(torneo.getId()).stream()
                .filter(a -> a.getEstado() == EstadoDesignacion.ACEPTADO)
                .map(ArbitroDesignado::getUsuario)
                .toList();
        if (arbitros.isEmpty()) {
            throw new IllegalStateException("El torneo " + torneo.getId() + " se quedó sin árbitros que aceptaron");
        }
        return arbitros;
    }

    // --- la llave ---------------------------------------------------------------

    private void armarLlave(Torneo torneo, List<List<Long>> clasificados) {
        List<Partido> primeraRonda = partidoRepository.delTorneo(torneo.getId()).stream()
                .filter(p -> p.getRonda() == 1)
                .toList();
        List<CrucesLlave.Cruce> cruces = CrucesLlave.armar(clasificados);
        List<Juega> lados = new ArrayList<>();
        for (CrucesLlave.Cruce cruce : cruces) {
            Partido partido = primeraRonda.get(cruce.posicion() - 1);
            lados.add(new Juega(partido, inscripcionRepository.getReferenceById(cruce.local()), Lado.LOCAL));
            lados.add(new Juega(partido, inscripcionRepository.getReferenceById(cruce.visitante()), Lado.VISITANTE));
        }
        juegaRepository.saveAll(lados);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("llave", "ARMADA_CON_GRUPOS");
        detalle.put("cruces", cruces.stream().map(c -> c.local() + "-" + c.visitante()).toList());
        auditar(torneo, detalle);
        eventos.publishEvent(new LlaveSorteada(torneo.getId(), false));
    }

    /** Lo hace el sistema: queda en la auditoría como SORTEO del torneo, sin usuario. */
    private void auditar(Torneo torneo, Map<String, Object> detalle) {
        Auditoria auditoria = new Auditoria();
        auditoria.setAccion(AccionAuditoria.SORTEO);
        auditoria.setEntidad("TORNEO");
        auditoria.setEntidadId(String.valueOf(torneo.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }
}
