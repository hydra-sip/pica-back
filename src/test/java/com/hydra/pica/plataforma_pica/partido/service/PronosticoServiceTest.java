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
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.partido.domain.Eleccion;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.Pronostico;
import com.hydra.pica.plataforma_pica.partido.domain.PronosticoId;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoPronostico;
import com.hydra.pica.plataforma_pica.partido.dto.PronosticoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.PronosticoRequest;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PronosticoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
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
}
