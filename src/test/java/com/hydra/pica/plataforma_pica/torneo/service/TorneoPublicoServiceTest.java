package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.ConteoInscripciones;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.Premio;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.repository.PremioRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class TorneoPublicoServiceTest {

    @Mock
    private TorneoRepository torneoRepository;

    @Mock
    private InscripcionRepository inscripcionRepository;

    @Mock
    private RifaRepository rifaRepository;

    @Mock
    private PremioRepository premioRepository;

    @InjectMocks
    private TorneoPublicoService service;

    private Torneo torneo;
    private Usuario organizador;

    @BeforeEach
    void setup() {
        Persona persona = new Persona();
        persona.setNombres("Carlos");
        persona.setApellidos("Perez");

        organizador = new Usuario();
        organizador.setUsername("carlosp");
        organizador.setPersona(persona);

        torneo = new Torneo();
        torneo.setNombre("Copa Verano");
        torneo.setSlug("copa-verano");
        torneo.setDisciplina("Fútbol 5");
        torneo.setFormato(Formato.ELIMINACION_DIRECTA);
        torneo.setModalidad(Modalidad.PRESENCIAL);
        torneo.setTipoParticipante(TipoParticipante.EQUIPO);
        torneo.setEstado(EstadoTorneo.INSCRIPCION_ABIERTA);
        torneo.setCupoMaximo(8);
        torneo.setCupoMinimo(2);
        torneo.setValorInscripcion(new BigDecimal("5000.00"));
        torneo.setPctPozoCompetidores(new BigDecimal("70.00"));
        torneo.setPctPremioRifa(new BigDecimal("20.00"));
        torneo.setCierreInscripcionEn(Instant.parse("2026-11-01T12:00:00Z"));
        torneo.setFechaInicio(Instant.parse("2026-11-05T10:00:00Z"));
        torneo.setReglamentoUrl("https://pica.local/reglamento.pdf");
        torneo.setReglamentoHash("hash12345");
        torneo.setOrganizador(organizador);
    }

    @Test
    @DisplayName("listarPublicos: mapea resumenes con cupo disponible y pozo estimado de competidores y rifa")
    void listarPublicosConRifaYConteo() {
        Pageable pageable = PageRequest.of(0, 10);
        when(torneoRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(torneo), pageable, 1));

        ConteoInscripciones conteo = new ConteoInscripciones() {
            @Override
            public Long getTorneoId() {
                return torneo.getId();
            }

            @Override
            public Long getCantidad() {
                return 3L;
            }
        };
        when(inscripcionRepository.contarPorTorneoIdInYEstado(any(), eq(EstadoInscripcion.CONFIRMADA)))
                .thenReturn(List.of(conteo));

        Rifa rifa = new Rifa();
        rifa.setTorneo(torneo);
        rifa.setNumeroDesde(1);
        rifa.setNumeroHasta(100);
        rifa.setPrecioNumero(new BigDecimal("1000.00"));
        rifa.setEstado(EstadoRifa.EN_VENTA);

        when(rifaRepository.findByTorneoIdInAndEliminadoEnIsNull(any()))
                .thenReturn(List.of(rifa));

        Page<TorneoResumen> resultado = service.listarPublicos("Fútbol 5", EstadoTorneo.INSCRIPCION_ABIERTA, pageable);

        assertThat(resultado.getContent()).hasSize(1);
        TorneoResumen resumen = resultado.getContent().get(0);
        assertThat(resumen.slug()).isEqualTo("copa-verano");
        assertThat(resumen.cupoMaximo()).isEqualTo(8);
        assertThat(resumen.cupoDisponible()).isEqualTo(5); // 8 - 3
        // Pozo: 8 * 5000 * 0.70 = 28000. Rifa: 100 * 1000 * 0.20 = 20000. Total = 48000.
        assertThat(resumen.pozoEstimado()).isEqualByComparingTo("48000.00");
    }

    @Test
    @DisplayName("listarPublicos: devuelve página vacía si no hay resultados")
    void listarPublicosVacio() {
        Pageable pageable = PageRequest.of(0, 10);
        when(torneoRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(Page.empty(pageable));

        Page<TorneoResumen> resultado = service.listarPublicos("Tenis", null, pageable);

        assertThat(resultado.getContent()).isEmpty();
    }

    @Test
    @DisplayName("detallePublico: devuelve todos los datos con premios y organizador")
    void detallePublicoExitoso() {
        when(torneoRepository.findBySlugAndEliminadoEnIsNull("copa-verano"))
                .thenReturn(Optional.of(torneo));
        when(inscripcionRepository.countByTorneoIdAndEstado(torneo.getId(), EstadoInscripcion.CONFIRMADA))
                .thenReturn(2L);
        when(rifaRepository.findByTorneoIdAndEliminadoEnIsNull(torneo.getId()))
                .thenReturn(Optional.empty());

        Premio p1 = new Premio(torneo, 1);
        p1.setPct(new BigDecimal("70.00"));
        Premio p2 = new Premio(torneo, 2);
        p2.setPct(new BigDecimal("30.00"));
        when(premioRepository.findByTorneoIdOrderByIdPuestoAsc(torneo.getId()))
                .thenReturn(List.of(p1, p2));

        TorneoDetalle detalle = service.detallePublico("copa-verano");

        assertThat(detalle.slug()).isEqualTo("copa-verano");
        assertThat(detalle.cupoDisponible()).isEqualTo(6); // 8 - 2
        assertThat(detalle.pozoEstimado()).isEqualByComparingTo("28000.00"); // 8 * 5000 * 0.70
        assertThat(detalle.premios()).hasSize(2);
        assertThat(detalle.premios().get(0).puesto()).isEqualTo(1);
        assertThat(detalle.organizador().nombreCompleto()).isEqualTo("Carlos Perez");
        assertThat(detalle.organizador().username()).isEqualTo("carlosp");
    }

    @Test
    @DisplayName("detallePublico: arroja 404 TORNEO_NO_ENCONTRADO si el torneo está en BORRADOR")
    void detalleBorradorDa404() {
        torneo.setEstado(EstadoTorneo.BORRADOR);
        when(torneoRepository.findBySlugAndEliminadoEnIsNull("copa-verano"))
                .thenReturn(Optional.of(torneo));

        assertThatThrownBy(() -> service.detallePublico("copa-verano"))
                .isInstanceOf(NoEncontradoException.class)
                .satisfies(e -> assertThat(((NoEncontradoException) e).getCodigo()).isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO));
    }

    @Test
    @DisplayName("detallePublico: arroja 404 TORNEO_NO_ENCONTRADO si no existe")
    void detalleNoExisteDa404() {
        when(torneoRepository.findBySlugAndEliminadoEnIsNull("no-existe"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detallePublico("no-existe"))
                .isInstanceOf(NoEncontradoException.class)
                .satisfies(e -> assertThat(((NoEncontradoException) e).getCodigo()).isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO));
    }
}
