package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.GruposTorneo;
import com.hydra.pica.plataforma_pica.partido.dto.LaudoRequest;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionRequest;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** La tabla de cada grupo (CR-02-5, PICA-399) con resultados cargados como en un torneo real. */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class TablaGruposIntegracionTest extends TorneoConGruposBase {

    private static final byte[] JPG = Arrays.copyOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0},
            2048);
    private static final String URL = "http://localhost/api/v1/partidos/1/evidencias";

    @Autowired private TablaGruposService tablaGruposService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private LaudoService laudoService;
    @Autowired private EvidenciaService evidenciaService;
    @Autowired private PartidoRepository partidoRepository;
    @Autowired private JuegaRepository juegaRepository;
    @MockitoBean private EmailService emailService;

    @BeforeEach
    void sorteado() {
        inscribir(8);
        cerrar();
        bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void antesDeJugarTodosEstanEnCeroYCompartenElPuesto() {
        GruposTorneo grupos = tablaGruposService.ver(torneo);

        assertThat(grupos.reglas().puntosVictoria()).isEqualTo(3);
        assertThat(grupos.desempate()).isEqualTo("puntos,entre-si,ganados,partido");
        assertThat(grupos.grupos()).extracting(GruposTorneo.Grupo::letra).containsExactly("A", "B");
        GruposTorneo.Grupo a = grupos.grupos().getFirst();
        assertThat(a.terminado()).isFalse();
        assertThat(a.partidos()).hasSize(6);
        assertThat(a.tabla()).hasSize(4).allSatisfy(f -> {
            assertThat(f.posicion()).isEqualTo(1);
            assertThat(f.puntos()).isZero();
            assertThat(f.nombre()).startsWith("jugador_grupos_");
        });
        assertThat(a.desempatesPendientes()).isEmpty();
    }

    @Test
    void conElGrupoJugadoLaTablaOrdenaYMarcaLosQuePasan() {
        // el local de cada partido gana 1-0, salvo uno que termina empatado
        List<Partido> grupoA = deGrupo("A");
        jugar(grupoA.getFirst(), 1, 1);
        grupoA.subList(1, grupoA.size()).forEach(p -> jugar(p, 1, 0));

        GruposTorneo.Grupo a = tablaGruposService.ver(torneo).grupos().getFirst();

        assertThat(a.terminado()).isTrue();
        assertThat(a.tabla()).extracting(GruposTorneo.Fila::jugados).containsOnly(3);
        assertThat(a.tabla().stream().mapToInt(GruposTorneo.Fila::puntos).sum()).isEqualTo(5 * 3 + 2);
        assertThat(a.tabla().stream().filter(GruposTorneo.Fila::clasifica)).hasSizeLessThanOrEqualTo(2);
        assertThat(a.tabla()).isSortedAccordingTo((x, y) -> Integer.compare(y.puntos(), x.puntos()));
        if (a.desempatesPendientes().isEmpty()) {
            assertThat(a.tabla().subList(0, 2)).extracting(GruposTorneo.Fila::clasifica).containsOnly(true);
        }
    }

    @Test
    void unaObjecionQueCambiaElResultadoCambiaLaTabla() {
        Partido partido = deGrupo("A").getFirst();
        Long local = jugador(partido, Lado.LOCAL);
        Long visitante = jugador(partido, Lado.VISITANTE);
        resultadoService.cargar(local, partido.getId(), new ResultadoRequest(2, 0, null, null));
        Long evidencia = evidenciaService.subir(visitante, partido.getId(), JPG, URL).id();
        resultadoService.objetar(visitante, partido.getId(), new ObjecionRequest("Terminó empatado", 1, 1, null, null));
        entityManager.flush();

        laudoService.laudar(organizador, partido.getId(),
                new LaudoRequest(1, 1, null, null, "El video muestra el empate", evidencia));
        entityManager.flush();
        entityManager.clear();

        GruposTorneo.Grupo a = tablaGruposService.ver(torneo).grupos().getFirst();
        assertThat(fila(a, inscripcion(partido, Lado.LOCAL)).puntos()).isEqualTo(1);
        assertThat(fila(a, inscripcion(partido, Lado.VISITANTE)).puntos()).isEqualTo(1);
        assertThat(fila(a, inscripcion(partido, Lado.LOCAL)).empatados()).isEqualTo(1);
    }

    @Test
    void unResultadoSinConfirmarNoCuenta() {
        Partido partido = deGrupo("A").getFirst();
        resultadoService.cargar(jugador(partido, Lado.LOCAL), partido.getId(), new ResultadoRequest(3, 0, null, null));
        entityManager.flush();
        entityManager.clear();

        assertThat(tablaGruposService.ver(torneo).grupos().getFirst().tabla())
                .extracting(GruposTorneo.Fila::puntos).containsOnly(0);
    }

    @Test
    void unTorneoSinGruposNoTieneTabla() {
        jdbc.update("UPDATE torneo SET formato = 'ELIMINACION_DIRECTA', tamanio_grupo = NULL, clasifican_por_grupo = NULL,"
                + " puntos_victoria = NULL, puntos_empate = NULL WHERE id = ?", torneo);
        entityManager.clear();

        assertThatThrownBy(() -> tablaGruposService.ver(torneo))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- helpers --------------------------------------------------------------

    private List<Partido> deGrupo(String letra) {
        return partidoRepository.deLosGrupos(torneo).stream()
                .filter(p -> p.getGrupo().getLetra().equals(letra))
                .toList();
    }

    /** Carga el resultado y el rival lo acepta. */
    private void jugar(Partido partido, int local, int visitante) {
        resultadoService.cargar(jugador(partido, Lado.LOCAL), partido.getId(),
                new ResultadoRequest(local, visitante, null, null));
        resultadoService.aceptar(jugador(partido, Lado.VISITANTE), partido.getId());
        entityManager.flush();
        entityManager.clear();
    }

    private Long jugador(Partido partido, Lado lado) {
        return juegaRepository.delPartido(partido.getId()).stream()
                .filter(j -> j.getLado() == lado)
                .findFirst().orElseThrow()
                .getInscripcion().getUsuario().getId();
    }

    private Long inscripcion(Partido partido, Lado lado) {
        return juegaRepository.delPartido(partido.getId()).stream()
                .filter(j -> j.getLado() == lado)
                .findFirst().orElseThrow()
                .getId().getInscripcionId();
    }

    private static GruposTorneo.Fila fila(GruposTorneo.Grupo grupo, Long inscripcion) {
        return grupo.tabla().stream().filter(f -> f.inscripcionId().equals(inscripcion)).findFirst().orElseThrow();
    }
}
