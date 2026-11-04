package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.partido.domain.Eleccion;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.Pronostico;
import com.hydra.pica.plataforma_pica.partido.domain.PronosticoId;
import com.hydra.pica.plataforma_pica.partido.dto.EstadisticaPronosticosUsuario;
import com.hydra.pica.plataforma_pica.partido.dto.MiPosicionRanking;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoPronostico;
import com.hydra.pica.plataforma_pica.partido.dto.PosicionRanking;
import com.hydra.pica.plataforma_pica.partido.dto.PronosticoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.PronosticoRequest;
import com.hydra.pica.plataforma_pica.partido.dto.RankingResponse;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PronosticoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PronosticoServiceTest {

    @Mock
    private Partidos partidos;

    @Mock
    private PartidoRepository partidoRepository;

    @Mock
    private JuegaRepository juegaRepository;

    @Mock
    private PronosticoRepository pronosticoRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private TorneoRepository torneoRepository;

    @InjectMocks
    private PronosticoService pronosticoService;


    private Torneo torneo;
    private Partido partido;
    private Usuario usuario;
    private List<Juega> lados;

    @BeforeEach
    void setUp() {
        torneo = new Torneo();
        torneo.setNombre("Torneo Test");
        torneo.setDisciplina("FUTBOL_5");
        torneo.setFormato(Formato.ELIMINACION_DIRECTA);
        torneo.setModalidad(Modalidad.PRESENCIAL);
        torneo.setTipoParticipante(TipoParticipante.INDIVIDUAL);
        torneo.setCupoMaximo(8);
        torneo.setCupoMinimo(4);
        torneo.setEstado(EstadoTorneo.EN_CURSO);
        ReflectionTestUtils.setField(torneo, "id", 1L);

        partido = new Partido();
        partido.setTorneo(torneo);
        partido.setRonda(1);
        partido.setPosicion(1);
        partido.setEstado(EstadoPartido.PROGRAMADO);
        partido.setFechaProgramada(Instant.now().plus(2, ChronoUnit.HOURS));
        ReflectionTestUtils.setField(partido, "id", 10L);

        usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", 55L);

        Inscripcion inscripcionLocal = new Inscripcion();
        inscripcionLocal.setTorneo(torneo);
        inscripcionLocal.setUsuario(usuario);
        ReflectionTestUtils.setField(inscripcionLocal, "id", 101L);

        Usuario usuarioVisitante = new Usuario();
        ReflectionTestUtils.setField(usuarioVisitante, "id", 56L);
        Inscripcion inscripcionVisitante = new Inscripcion();
        inscripcionVisitante.setTorneo(torneo);
        inscripcionVisitante.setUsuario(usuarioVisitante);
        ReflectionTestUtils.setField(inscripcionVisitante, "id", 102L);

        Juega local = new Juega(partido, inscripcionLocal, Lado.LOCAL);
        Juega visitante = new Juega(partido, inscripcionVisitante, Lado.VISITANTE);
        lados = List.of(local, visitante);
    }

    @Test
    @DisplayName("Criterio 1: Error al intentar pronosticar cruce ya finalizado")
    void pronosticarPartidoFinalizadoLanzaConflicto() {
        partido.setEstado(EstadoPartido.CONFIRMADO);
        when(partidos.buscarBloqueado(10L)).thenReturn(partido);
        when(juegaRepository.delPartido(10L)).thenReturn(lados);

        assertThatThrownBy(() -> pronosticoService.pronosticar(55L, 10L, new PronosticoRequest("1")))
                .isInstanceOf(ConflictoException.class)
                .hasMessageContaining("la ventana de pronósticos ya cerró");
    }

    @Test
    @DisplayName("Criterio 1: Error al intentar pronosticar cruce fuera de la ventana permitida (fecha pasada)")
    void pronosticarFechaPasadaLanzaConflicto() {
        partido.setFechaProgramada(Instant.now().minus(10, ChronoUnit.MINUTES));
        when(partidos.buscarBloqueado(10L)).thenReturn(partido);
        when(juegaRepository.delPartido(10L)).thenReturn(lados);

        assertThatThrownBy(() -> pronosticoService.pronosticar(55L, 10L, new PronosticoRequest("1")))
                .isInstanceOf(ConflictoException.class)
                .hasMessageContaining("La ventana de pronósticos cerró el");
    }

    @Test
    @DisplayName("Criterio 1: Error al intentar pronosticar cruce sin ambos rivales definidos")
    void pronosticarSinRivalesLanzaConflicto() {
        when(partidos.buscarBloqueado(10L)).thenReturn(partido);
        when(juegaRepository.delPartido(10L)).thenReturn(List.of(lados.get(0)));

        assertThatThrownBy(() -> pronosticoService.pronosticar(55L, 10L, new PronosticoRequest("1")))
                .isInstanceOf(ConflictoException.class)
                .hasMessageContaining("aún no tiene ambos rivales definidos");
    }

    @Test
    @DisplayName("Criterio 2: Error al intentar pronosticar sin indicar resultado válido (formato incorrecto)")
    void pronosticarFormatoInvalidoLanzaValidacion() {
        assertThatThrownBy(() -> pronosticoService.pronosticar(55L, 10L, new PronosticoRequest("3")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Hay campos inválidos");

        assertThatThrownBy(() -> pronosticoService.pronosticar(55L, 10L, new PronosticoRequest("INVALIDO")))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> pronosticoService.pronosticar(55L, 10L, new PronosticoRequest("   ")))
                .isInstanceOf(ApiException.class);

        verify(partidos, never()).buscarBloqueado(any());
    }

    @Test
    @DisplayName("Criterio 3: Cargar un pronóstico válido antes del cierre y comprobar que queda registrado")
    void pronosticarValidoExitoso() {
        when(partidos.buscarBloqueado(10L)).thenReturn(partido);
        when(juegaRepository.delPartido(10L)).thenReturn(lados);
        when(pronosticoRepository.findById(new PronosticoId(55L, 10L))).thenReturn(Optional.empty());
        when(usuarioRepository.getReferenceById(55L)).thenReturn(usuario);
        when(pronosticoRepository.save(any(Pronostico.class))).thenAnswer(inv -> inv.getArgument(0));

        PronosticoDetalle detalle = pronosticoService.pronosticar(55L, 10L, new PronosticoRequest("1"));

        assertThat(detalle.partidoId()).isEqualTo(10L);
        assertThat(detalle.usuarioId()).isEqualTo(55L);
        assertThat(detalle.eleccion()).isEqualTo("1");
        assertThat(detalle.puntos()).isNull();
        verify(pronosticoRepository).save(any(Pronostico.class));
    }

    @Test
    @DisplayName("Criterio 4: Confirmar resultado real y comprobar cálculo de puntaje según esquema (Gana Local = 3 pts a '1', 0 pts a '2')")
    void calcularPuntosResultadoLocal() {
        lados.get(0).setPuntaje(3);
        lados.get(1).setPuntaje(1);
        lados.get(0).setEsGanador(true);
        lados.get(1).setEsGanador(false);

        Pronostico p1 = new Pronostico(usuario, partido, Eleccion.LOCAL);
        Usuario u2 = new Usuario();
        ReflectionTestUtils.setField(u2, "id", 77L);
        Pronostico p2 = new Pronostico(u2, partido, Eleccion.VISITANTE);

        when(pronosticoRepository.delPartido(10L)).thenReturn(List.of(p1, p2));
        when(juegaRepository.delPartido(10L)).thenReturn(lados);

        pronosticoService.calcularPuntos(10L, lados.get(0).getId().getInscripcionId());

        assertThat(p1.getPuntos()).isEqualTo(PronosticoService.PUNTOS_ACIERTO); // 3 puntos
        assertThat(p2.getPuntos()).isEqualTo(PronosticoService.PUNTOS_DESACIERTO); // 0 puntos
        verify(pronosticoRepository).saveAll(List.of(p1, p2));
    }

    @Test
    @DisplayName("Criterio 4: Confirmar resultado real de empate (penales) y calcular puntaje para 'X'")
    void calcularPuntosResultadoEmpate() {
        lados.get(0).setPuntaje(2);
        lados.get(1).setPuntaje(2);
        lados.get(0).setPenales(4);
        lados.get(1).setPenales(3);
        lados.get(0).setEsGanador(true);

        Pronostico pEmpate = new Pronostico(usuario, partido, Eleccion.EMPATE);
        Pronostico pLocal = new Pronostico(new Usuario(), partido, Eleccion.LOCAL);

        when(pronosticoRepository.delPartido(10L)).thenReturn(List.of(pEmpate, pLocal));
        when(juegaRepository.delPartido(10L)).thenReturn(lados);

        pronosticoService.calcularPuntos(10L, lados.get(0).getId().getInscripcionId());

        assertThat(pEmpate.getPuntos()).isEqualTo(3);
        assertThat(pLocal.getPuntos()).isEqualTo(0);
    }

    @Test
    @DisplayName("Criterio 5: Comprobar que no se puede modificar el pronóstico una vez cerrada la ventana")
    void noSePuedeModificarUnaVezCerradaLaVentana() {
        partido.setFechaProgramada(Instant.now().minus(5, ChronoUnit.MINUTES));
        when(partidos.buscarBloqueado(10L)).thenReturn(partido);
        when(juegaRepository.delPartido(10L)).thenReturn(lados);

        assertThatThrownBy(() -> pronosticoService.pronosticar(55L, 10L, new PronosticoRequest("X")))
                .isInstanceOf(ConflictoException.class)
                .hasMessageContaining("La ventana de pronósticos cerró");

        verify(pronosticoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Listar partidos para pronósticos incluye si está abierto y el pronóstico del usuario")
    void listarPartidosParaPronosticos() {
        when(partidoRepository.paraPronosticos(1L)).thenReturn(List.of(partido));
        when(juegaRepository.delPartido(10L)).thenReturn(lados);
        when(partidoRepository.rondasDelTorneo(1L)).thenReturn(1);

        Pronostico miPronostico = new Pronostico(usuario, partido, Eleccion.LOCAL);
        when(pronosticoRepository.delUsuario(55L)).thenReturn(List.of(miPronostico));

        List<PartidoPronostico> lista = pronosticoService.listarPartidos(55L, 1L, null);

        assertThat(lista).hasSize(1);
        PartidoPronostico item = lista.get(0);
        assertThat(item.partidoId()).isEqualTo(10L);
        assertThat(item.abierto()).isTrue();
        assertThat(item.miPronostico()).isNotNull();
        assertThat(item.miPronostico().eleccion()).isEqualTo("1");
    }

    @Test
    @DisplayName("HU-307 Criterio 1: Consultar el ranking sin haber realizado ningún pronóstico indica posición vacía y mensaje acorde")
    void rankingUsuarioSinPronosticosIndicaMensajeYPosicionNula() {
        when(pronosticoRepository.rankingEstadisticas(null)).thenReturn(List.of());

        RankingResponse ranking = pronosticoService.obtenerRanking(55L, null);

        assertThat(ranking.posiciones()).isEmpty();
        assertThat(ranking.miPosicion()).isNotNull();
        assertThat(ranking.miPosicion().posicion()).isNull();
        assertThat(ranking.miPosicion().puntos()).isNull();
        assertThat(ranking.miPosicion().aciertos()).isNull();
        assertThat(ranking.miPosicion().mensaje()).isEqualTo("Aún no has realizado pronósticos");
    }

    @Test
    @DisplayName("HU-307 Criterio 2: Consultar ranking luego de resolverse pronósticos refleja el puntaje acumulado")
    void rankingReflejaPuntajeAcumulado() {
        Usuario u1 = crearUsuario(1L, "user1", "Juan", "Pérez");
        Usuario u2 = crearUsuario(2L, "user2", "Carlos", "Gómez");
        Usuario u3 = crearUsuario(3L, "user3", "Lucas", "López");

        Instant t0 = Instant.parse("2026-10-28T10:00:00Z");
        EstadisticaPronosticosUsuario s1 = new EstadisticaPronosticosUsuario(1L, 6L, 2L, 2L, t0);
        EstadisticaPronosticosUsuario s2 = new EstadisticaPronosticosUsuario(2L, 3L, 1L, 2L, t0.plusSeconds(60));
        EstadisticaPronosticosUsuario s3 = new EstadisticaPronosticosUsuario(3L, 0L, 0L, 1L, t0.plusSeconds(120));

        when(pronosticoRepository.rankingEstadisticas(null)).thenReturn(List.of(s2, s1, s3)); // desordenado a propósito
        when(usuarioRepository.findConPersonaByIdIn(List.of(1L, 2L, 3L))).thenReturn(List.of(u1, u2, u3));

        RankingResponse response = pronosticoService.obtenerRanking(2L, null);

        assertThat(response.posiciones()).hasSize(3);
        assertThat(response.posiciones().get(0).usuarioId()).isEqualTo(1L);
        assertThat(response.posiciones().get(0).posicion()).isEqualTo(1);
        assertThat(response.posiciones().get(0).puntos()).isEqualTo(6);
        assertThat(response.posiciones().get(0).aciertos()).isEqualTo(2);
        assertThat(response.posiciones().get(0).nombre()).isEqualTo("Juan Pérez");

        assertThat(response.posiciones().get(1).usuarioId()).isEqualTo(2L);
        assertThat(response.posiciones().get(1).posicion()).isEqualTo(2);
        assertThat(response.posiciones().get(1).puntos()).isEqualTo(3);

        assertThat(response.posiciones().get(2).usuarioId()).isEqualTo(3L);
        assertThat(response.posiciones().get(2).posicion()).isEqualTo(3);
        assertThat(response.posiciones().get(2).puntos()).isEqualTo(0);

        // Mi posición para user 2
        assertThat(response.miPosicion()).isNotNull();
        assertThat(response.miPosicion().posicion()).isEqualTo(2);
        assertThat(response.miPosicion().puntos()).isEqualTo(3);
        assertThat(response.miPosicion().aciertos()).isEqualTo(1);
        assertThat(response.miPosicion().mensaje()).isNull();
    }

    @Test
    @DisplayName("HU-307 Criterio 3: Ante empate de puntaje se aplica criterio de desempate por orden de carga (primerPronosticoEn)")
    void rankingEmpateDesempataPorOrdenDeCarga() {
        Usuario u1 = crearUsuario(10L, "primero", "Primero", "Carga");
        Usuario u2 = crearUsuario(20L, "segundo", "Segundo", "Carga");

        Instant tPrimero = Instant.parse("2026-10-28T09:00:00Z");
        Instant tSegundo = Instant.parse("2026-10-28T10:00:00Z");

        // Ambos con 3 puntos y 1 acierto, pero u1 cargó antes
        EstadisticaPronosticosUsuario s1 = new EstadisticaPronosticosUsuario(10L, 3L, 1L, 1L, tPrimero);
        EstadisticaPronosticosUsuario s2 = new EstadisticaPronosticosUsuario(20L, 3L, 1L, 1L, tSegundo);

        when(pronosticoRepository.rankingEstadisticas(null)).thenReturn(List.of(s2, s1)); // s2 primero para probar ordenamiento
        when(usuarioRepository.findConPersonaByIdIn(List.of(10L, 20L))).thenReturn(List.of(u1, u2));

        RankingResponse response = pronosticoService.obtenerRanking(null, null);

        assertThat(response.posiciones()).hasSize(2);
        assertThat(response.posiciones().get(0).usuarioId()).isEqualTo(10L);
        assertThat(response.posiciones().get(0).posicion()).isEqualTo(1);
        assertThat(response.posiciones().get(1).usuarioId()).isEqualTo(20L);
        assertThat(response.posiciones().get(1).posicion()).isEqualTo(2);
    }

    @Test
    @DisplayName("HU-307 Criterio 4: El ranking se actualiza automáticamente a medida que se confirman nuevos resultados")
    void rankingSeActualizaAlConfirmarResultados() {
        // Al confirmarse un partido vía evento ResultadoConfirmado, se calculan puntos y se persisten
        lados.get(0).setPuntaje(2);
        lados.get(1).setPuntaje(1);
        Pronostico pronostico = new Pronostico(usuario, partido, Eleccion.LOCAL);

        when(pronosticoRepository.delPartido(10L)).thenReturn(List.of(pronostico));
        when(juegaRepository.delPartido(10L)).thenReturn(lados);

        // Disparamos evento de resultado confirmado
        pronosticoService.onResultadoConfirmado(new com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado(1L, 10L, 101L, false));


        // El pronóstico fue premiado con 3 puntos y guardado
        assertThat(pronostico.getPuntos()).isEqualTo(3);
        verify(pronosticoRepository).saveAll(List.of(pronostico));

        // Al consultar ranking posterior, la base devuelve los nuevos puntos calculados
        Usuario u = crearUsuario(55L, "juan", "Juan", "Tester");
        when(pronosticoRepository.rankingEstadisticas(1L)).thenReturn(List.of(
                new EstadisticaPronosticosUsuario(55L, 3L, 1L, 1L, Instant.now())
        ));
        when(usuarioRepository.findConPersonaByIdIn(List.of(55L))).thenReturn(List.of(u));
        when(torneoRepository.findByIdAndEliminadoEnIsNull(1L)).thenReturn(Optional.of(torneo));

        RankingResponse rankingActualizado = pronosticoService.obtenerRanking(55L, 1L);

        assertThat(rankingActualizado.posiciones()).hasSize(1);
        assertThat(rankingActualizado.posiciones().get(0).puntos()).isEqualTo(3);
        assertThat(rankingActualizado.miPosicion().posicion()).isEqualTo(1);
        assertThat(rankingActualizado.miPosicion().puntos()).isEqualTo(3);
    }

    @Test
    @DisplayName("Consultar ranking con torneo inexistente lanza 404 TORNEO_NO_ENCONTRADO")
    void rankingTorneoInexistenteLanza404() {
        when(torneoRepository.findByIdAndEliminadoEnIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pronosticoService.obtenerRanking(null, 999L))
                .isInstanceOf(NoEncontradoException.class)
                .hasMessageContaining("Torneo no encontrado");
    }

    @Test
    @DisplayName("Consultar ranking con torneo en borrador lanza 404 TORNEO_NO_ENCONTRADO")
    void rankingTorneoEnBorradorLanza404() {
        torneo.setEstado(EstadoTorneo.BORRADOR);
        when(torneoRepository.findByIdAndEliminadoEnIsNull(1L)).thenReturn(Optional.of(torneo));

        assertThatThrownBy(() -> pronosticoService.obtenerRanking(null, 1L))
                .isInstanceOf(NoEncontradoException.class)
                .hasMessageContaining("Torneo no encontrado");
    }

    private Usuario crearUsuario(Long id, String username, String nombres, String apellidos) {
        Usuario u = new Usuario();
        ReflectionTestUtils.setField(u, "id", id);
        u.setUsername(username);
        Persona p = new Persona();
        p.setNombres(nombres);
        p.setApellidos(apellidos);
        u.setPersona(p);
        return u;
    }
}

