package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.FasePartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.TablaGrupo.Criterio;
import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.partido.dto.GruposTorneo;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.event.LlaveSorteada;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * El desempate y el pase de los clasificados a la llave (CR-02-6, PICA-400). Los resultados salen de una «fuerza»
 * por inscripción: en cada partido gana 1–0 el más fuerte, salvo los cruces que se fuerzan a mano.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class PaseALaLlaveIntegracionTest extends TorneoConGruposBase {

    @Autowired private TablaGruposService tablaGruposService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private PartidoRepository partidoRepository;
    @Autowired private JuegaRepository juegaRepository;
    @Autowired private ApplicationEvents eventos;
    @MockitoBean private EmailService emailService;

    private final Map<Long, Integer> fuerza = new HashMap<>();

    @BeforeEach
    void sorteado() {
        inscribir(8);
        cerrar();
        bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void conLosGruposJugadosLaLlaveSeArmaConCrucesFijos() {
        List<Long> a = integrantes("A");
        List<Long> b = integrantes("B");
        ordenar(a);
        ordenar(b);

        jugarGrupos();

        BracketTorneo llave = bracketService.ver(torneo);
        PartidoBracket semi1 = llave.rondas().getFirst().partidos().get(0);
        PartidoBracket semi2 = llave.rondas().getFirst().partidos().get(1);
        // 1A–2B y 1B–2A
        assertThat(List.of(semi1.local().inscripcionId(), semi1.visitante().inscripcionId()))
                .containsExactly(a.get(0), b.get(1));
        assertThat(List.of(semi2.local().inscripcionId(), semi2.visitante().inscripcionId()))
                .containsExactly(b.get(0), a.get(1));
        assertThat(eventos.stream(LlaveSorteada.class)).contains(new LlaveSorteada(torneo, false));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auditoria WHERE entidad = 'TORNEO' AND entidad_id = ?
                AND detalle ->> 'llave' = 'ARMADA_CON_GRUPOS'""", Integer.class, String.valueOf(torneo))).isOne();
    }

    @Test
    void conUnGrupoSinTerminarLaLlaveSigueVacia() {
        ordenar(integrantes("A"));
        ordenar(integrantes("B"));
        List<Partido> todos = partidoRepository.deLosGrupos(torneo);

        todos.subList(0, todos.size() - 1).forEach(this::jugar);

        assertThat(bracketService.ver(torneo).rondas().getFirst().partidos())
                .allSatisfy(p -> assertThat(p.local()).isNull());
    }

    @Test
    void unTripleEmpateCircularSeDefineConUnPartidoDeDesempateYRecienAhiSeArmaLaLlave() {
        List<Long> a = integrantes("A");
        // en A: 0 le gana a 1, 1 a 2 y 2 a 0; los tres le ganan a 3
        circular(a);
        ordenar(integrantes("B"));

        jugarGrupos();

        GruposTorneo.Grupo grupoA = tablaGruposService.ver(torneo).grupos().getFirst();
        assertThat(grupoA.terminado()).isTrue();
        assertThat(grupoA.tabla().subList(0, 3)).extracting(GruposTorneo.Fila::criterio).containsOnly(Criterio.EMPATE);
        // tres empatados: un partido y el que tuvo BYE espera en la final del mini cuadro
        List<Partido> desempate = partidoRepository.delGrupo(grupoId("A"), FasePartido.DESEMPATE);
        assertThat(desempate).hasSize(3);
        assertThat(bracketService.ver(torneo).rondas().getFirst().partidos())
                .allSatisfy(p -> assertThat(p.local()).isNull());

        // se juega el desempate: gana siempre el de menor id
        desempate.stream()
                .filter(p -> p.getEstado() != EstadoPartido.CONFIRMADO)
                .sorted((x, y) -> Integer.compare(x.getRonda(), y.getRonda()))
                .forEach(p -> jugarDesempate(p.getId()));

        GruposTorneo.Grupo resuelto = tablaGruposService.ver(torneo).grupos().getFirst();
        assertThat(resuelto.tabla().subList(0, 3)).extracting(GruposTorneo.Fila::criterio)
                .containsOnly(Criterio.DESEMPATE);
        assertThat(resuelto.tabla()).extracting(GruposTorneo.Fila::posicion).containsExactly(1, 2, 3, 4);
        assertThat(resuelto.desempate()).hasSize(3);
        List<Long> pasanDeA = resuelto.tabla().subList(0, 2).stream().map(GruposTorneo.Fila::inscripcionId).toList();
        PartidoBracket semi1 = bracketService.ver(torneo).rondas().getFirst().partidos().get(0);
        assertThat(semi1.local().inscripcionId()).isEqualTo(pasanDeA.get(0));
    }

    @Test
    void elTorneoConGruposTerminaConCampeonYLosEliminadosEnGruposSinPuesto() {
        List<Long> a = integrantes("A");
        List<Long> b = integrantes("B");
        ordenar(a);
        ordenar(b);
        jugarGrupos();

        // semifinales y final: gana el de más fuerza
        for (int ronda = 1; ronda <= 2; ronda++) {
            int r = ronda;
            partidoRepository.delTorneo(torneo).stream().filter(p -> p.getRonda() == r)
                    .map(Partido::getId).toList()
                    .forEach(id -> jugar(partidoRepository.findById(id).orElseThrow()));
        }

        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("FINALIZADO");
        // semis: 1A le gana a 2B y 2A a 1B (los de A son más fuertes); final: 1A a 2A
        assertThat(posicion(a.get(0))).isEqualTo(1);
        assertThat(posicion(a.get(1))).isEqualTo(2);
        assertThat(posicion(b.get(0))).isEqualTo(3);
        assertThat(posicion(b.get(1))).isEqualTo(3);
        assertThat(posicion(a.get(2))).isNull();
        assertThat(posicion(b.get(3))).isNull();
    }

    // --- helpers --------------------------------------------------------------

    private List<Long> integrantes(String letra) {
        return jdbc.queryForList("""
                SELECT gi.inscripcion_id FROM grupo_inscripcion gi JOIN grupo g ON g.id = gi.grupo_id
                WHERE g.torneo_id = ? AND g.letra = ? ORDER BY gi.inscripcion_id""", Long.class, torneo, letra);
    }

    private Long grupoId(String letra) {
        return jdbc.queryForObject("SELECT id FROM grupo WHERE torneo_id = ? AND letra = ?", Long.class, torneo, letra);
    }

    /** El primero de la lista es el más fuerte; los de A, un poco más que los de B. */
    private void ordenar(List<Long> grupo) {
        int base = fuerza.isEmpty() ? 100 : 50;
        for (int i = 0; i < grupo.size(); i++) {
            fuerza.put(grupo.get(i), base - i);
        }
    }

    /** Los tres primeros en círculo, con la misma fuerza; el cuarto, el más débil. */
    private void circular(List<Long> grupo) {
        grupo.subList(0, 3).forEach(id -> fuerza.put(id, 100));
        fuerza.put(grupo.get(3), 0);
    }

    private void jugarGrupos() {
        partidoRepository.deLosGrupos(torneo).stream()
                .filter(p -> p.getFase() == FasePartido.GRUPOS)
                .map(Partido::getId).toList()
                .forEach(id -> jugar(partidoRepository.findById(id).orElseThrow()));
    }

    /** Gana 1–0 el de más fuerza; entre los del círculo, 0 le gana a 1, 1 a 2 y 2 a 0. */
    private void jugar(Partido partido) {
        List<Juega> lados = juegaRepository.delPartido(partido.getId());
        Juega local = lados.stream().filter(j -> j.getLado() == Lado.LOCAL).findFirst().orElseThrow();
        Juega visitante = lados.stream().filter(j -> j.getLado() == Lado.VISITANTE).findFirst().orElseThrow();
        long l = local.getId().getInscripcionId();
        long v = visitante.getId().getInscripcionId();
        int fl = fuerza.getOrDefault(l, 0);
        int fv = fuerza.getOrDefault(v, 0);
        boolean ganaLocal = fl != fv ? fl > fv : ganaEnCirculo(l, v);
        cargarYAceptar(partido.getId(), local, visitante, ganaLocal);
    }

    private boolean ganaEnCirculo(long l, long v) {
        List<Long> a = integrantes("A");
        int il = a.indexOf(l);
        int iv = a.indexOf(v);
        return (il + 1) % 3 == iv;
    }

    /** En el desempate gana el de menor id. */
    private void jugarDesempate(Long partidoId) {
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        Juega local = lados.stream().filter(j -> j.getLado() == Lado.LOCAL).findFirst().orElseThrow();
        Juega visitante = lados.stream().filter(j -> j.getLado() == Lado.VISITANTE).findFirst().orElseThrow();
        cargarYAceptar(partidoId, local, visitante,
                local.getId().getInscripcionId() < visitante.getId().getInscripcionId());
    }

    private void cargarYAceptar(Long partidoId, Juega local, Juega visitante, boolean ganaLocal) {
        resultadoService.cargar(local.getInscripcion().getUsuario().getId(), partidoId,
                ganaLocal ? new ResultadoRequest(1, 0, null, null) : new ResultadoRequest(0, 1, null, null));
        resultadoService.aceptar(visitante.getInscripcion().getUsuario().getId(), partidoId);
        entityManager.flush();
        entityManager.clear();
    }

    private Integer posicion(Long inscripcion) {
        return jdbc.queryForObject("SELECT posicion FROM inscripcion WHERE id = ?", Integer.class, inscripcion);
    }
}
