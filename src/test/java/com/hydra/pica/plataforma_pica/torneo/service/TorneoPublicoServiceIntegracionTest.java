package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
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
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class TorneoPublicoServiceIntegracionTest {

    @Autowired
    private TorneoPublicoService torneoPublicoService;

    @Autowired
    private TorneoRepository torneoRepository;

    @Autowired
    private InscripcionRepository inscripcionRepository;

    @Autowired
    private RifaRepository rifaRepository;

    @Autowired
    private PremioRepository premioRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PersonaRepository personaRepository;

    private Usuario organizador;
    private Usuario jugador1;
    private Usuario jugador2;

    @BeforeEach
    void setup() {
        organizador = crearUsuario("org_torneo_test");
        jugador1 = crearUsuario("jugador1_torneo_test");
        jugador2 = crearUsuario("jugador2_torneo_test");
    }

    @Test
    @DisplayName("listarPublicos: no muestra borradores ni eliminados")
    void noMuestraBorradoresNiEliminados() {
        crearTorneo("t-borrador", "Torneo Borrador", "Fútbol 5", EstadoTorneo.BORRADOR, null);
        Torneo publicado = crearTorneo("t-publicado", "Torneo Publicado", "Fútbol 5", EstadoTorneo.INSCRIPCION_ABIERTA, null);
        Torneo eliminado = crearTorneo("t-eliminado", "Torneo Eliminado", "Fútbol 5", EstadoTorneo.INSCRIPCION_ABIERTA, Instant.now());

        Page<TorneoResumen> pagina = torneoPublicoService.listarPublicos(null, null, PageRequest.of(0, 10));

        assertThat(pagina.getContent())
                .extracting(TorneoResumen::slug)
                .contains(publicado.getSlug())
                .doesNotContain("t-borrador", eliminado.getSlug());
    }

    @Test
    @DisplayName("listarPublicos: filtra por disciplina y estado")
    void filtraPorDisciplinaYEstado() {
        crearTorneo("t-futbol", "Torneo Fútbol", "Fútbol 5", EstadoTorneo.INSCRIPCION_ABIERTA, null);
        crearTorneo("t-ajedrez-abierto", "Torneo Ajedrez Abierto", "Ajedrez", EstadoTorneo.INSCRIPCION_ABIERTA, null);
        crearTorneo("t-ajedrez-cerrado", "Torneo Ajedrez Cerrado", "Ajedrez", EstadoTorneo.INSCRIPCION_CERRADA, null);

        Page<TorneoResumen> soloAjedrez = torneoPublicoService.listarPublicos("ajedrez", null, PageRequest.of(0, 10));
        assertThat(soloAjedrez.getContent())
                .extracting(TorneoResumen::slug)
                .containsExactlyInAnyOrder("t-ajedrez-abierto", "t-ajedrez-cerrado");

        Page<TorneoResumen> ajedrezCerrado = torneoPublicoService.listarPublicos("Ajedrez", EstadoTorneo.INSCRIPCION_CERRADA, PageRequest.of(0, 10));
        assertThat(ajedrezCerrado.getContent())
                .extracting(TorneoResumen::slug)
                .containsExactly("t-ajedrez-cerrado");

        // Filtrar por BORRADOR devuelve vacío porque nunca se muestran borradores
        Page<TorneoResumen> borrador = torneoPublicoService.listarPublicos(null, EstadoTorneo.BORRADOR, PageRequest.of(0, 10));
        assertThat(borrador.getContent()).isEmpty();
    }

    @Test
    @DisplayName("listarPublicos: calcula correctamente cupo disponible y pozo estimado (con rifa y sin rifa)")
    void calculoCupoYPozoEstimado() {
        // Torneo con cupoMaximo=8, valor=5000, pctPozo=70% -> pozo competidores = 8 * 5000 * 0.70 = 28000.
        // Rifa con 100 números a 1000, pctPremioRifa=20% -> pozo rifa = 100000 * 0.20 = 20000.
        // Pozo estimado total = 48000.
        Torneo torneo = crearTorneo("t-con-rifa", "Torneo con Rifa", "Paddle", EstadoTorneo.INSCRIPCION_ABIERTA, null);
        torneo.setCupoMaximo(8);
        torneo.setValorInscripcion(new BigDecimal("5000.00"));
        torneo.setPctPozoCompetidores(new BigDecimal("70.00"));
        torneo.setPctPremioRifa(new BigDecimal("20.00"));
        torneo = torneoRepository.save(torneo);

        Rifa rifa = new Rifa();
        rifa.setOrganizador(organizador);
        rifa.setTorneo(torneo);
        rifa.setTitulo("Rifa Torneo");
        rifa.setNumeroDesde(1);
        rifa.setNumeroHasta(100);
        rifa.setPrecioNumero(new BigDecimal("1000.00"));
        rifa.setVentaDesde(Instant.now().minus(1, ChronoUnit.DAYS));
        rifa.setVentaHasta(Instant.now().plus(7, ChronoUnit.DAYS));
        rifa.setTerminosAceptadosEn(Instant.now().minus(1, ChronoUnit.DAYS));
        // desde V17, fuera de BORRADOR también son obligatorias la declaración de la lotería y la fecha del sorteo
        rifa.setLoteriaDeclaradaEn(Instant.now().minus(1, ChronoUnit.DAYS));
        rifa.setSorteadaEn(Instant.now().plus(8, ChronoUnit.DAYS));
        rifa.setEstado(EstadoRifa.EN_VENTA);
        rifaRepository.save(rifa);

        // 2 inscripciones confirmadas
        inscribir(torneo, jugador1, EstadoInscripcion.CONFIRMADA);
        inscribir(torneo, jugador2, EstadoInscripcion.CONFIRMADA);

        Page<TorneoResumen> pagina = torneoPublicoService.listarPublicos("Paddle", null, PageRequest.of(0, 10));

        assertThat(pagina.getContent()).hasSize(1);
        TorneoResumen resumen = pagina.getContent().get(0);
        assertThat(resumen.cupoMaximo()).isEqualTo(8);
        assertThat(resumen.cupoDisponible()).isEqualTo(6); // 8 - 2
        assertThat(resumen.pozoEstimado()).isEqualByComparingTo("48000.00");
    }

    @Test
    @DisplayName("detallePublico: devuelve todos los datos, organizador, premios y reglamento")
    void detallePublicoExitoso() {
        Torneo torneo = crearTorneo("t-detalle", "Torneo Detalle", "Tenis", EstadoTorneo.INSCRIPCION_ABIERTA, null);
        torneo.setDescripcion("Descripción del torneo");
        torneo.setReglamentoUrl("https://pica.local/reglamento.pdf");
        torneo.setReglamentoHash("hash1234");
        torneo.setCupoMaximo(16);
        torneo.setCupoMinimo(4);
        torneo.setValorInscripcion(new BigDecimal("3000.00"));
        torneo.setPctPozoCompetidores(new BigDecimal("80.00"));
        torneo.setPctPremioRifa(BigDecimal.ZERO);
        torneo = torneoRepository.save(torneo);

        Premio p1 = new Premio(torneo, 1);
        p1.setPct(new BigDecimal("70.00"));
        premioRepository.save(p1);

        Premio p2 = new Premio(torneo, 2);
        p2.setPct(new BigDecimal("30.00"));
        premioRepository.save(p2);

        inscribir(torneo, jugador1, EstadoInscripcion.CONFIRMADA);

        TorneoDetalle detalle = torneoPublicoService.detallePublico("t-detalle");

        assertThat(detalle.slug()).isEqualTo("t-detalle");
        assertThat(detalle.nombre()).isEqualTo("Torneo Detalle");
        assertThat(detalle.descripcion()).isEqualTo("Descripción del torneo");
        assertThat(detalle.reglamentoUrl()).isEqualTo("https://pica.local/reglamento.pdf");
        assertThat(detalle.reglamentoHash()).isEqualTo("hash1234");
        assertThat(detalle.cupoMaximo()).isEqualTo(16);
        assertThat(detalle.cupoMinimo()).isEqualTo(4);
        assertThat(detalle.cupoDisponible()).isEqualTo(15);
        assertThat(detalle.valorInscripcion()).isEqualByComparingTo("3000.00");
        assertThat(detalle.pozoEstimado()).isEqualByComparingTo("38400.00"); // 16 * 3000 * 0.80
        assertThat(detalle.premios()).hasSize(2);
        assertThat(detalle.premios().get(0).puesto()).isEqualTo(1);
        assertThat(detalle.premios().get(0).pct()).isEqualByComparingTo("70.00");
        assertThat(detalle.organizador().username()).isEqualTo(organizador.getUsername());
    }

    @Test
    @DisplayName("detallePublico: arroja 404 TORNEO_NO_ENCONTRADO si está en BORRADOR o no existe")
    void detalleBorradorONoExistenteDa404() {
        crearTorneo("t-borrador-slug", "Borrador", "Tenis", EstadoTorneo.BORRADOR, null);

        assertThatThrownBy(() -> torneoPublicoService.detallePublico("t-borrador-slug"))
                .isInstanceOf(NoEncontradoException.class)
                .satisfies(e -> assertThat(((NoEncontradoException) e).getCodigo()).isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO));

        assertThatThrownBy(() -> torneoPublicoService.detallePublico("slug-inexistente"))
                .isInstanceOf(NoEncontradoException.class)
                .satisfies(e -> assertThat(((NoEncontradoException) e).getCodigo()).isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO));
    }

    private Torneo crearTorneo(String slug, String nombre, String disciplina, EstadoTorneo estado, Instant eliminadoEn) {
        Torneo t = new Torneo();
        t.setOrganizador(organizador);
        t.setSlug(slug);
        t.setNombre(nombre);
        t.setDescripcion("Torneo " + nombre);
        t.setReglamentoUrl("https://pica.local/reglamento/" + slug + ".pdf");
        t.setDisciplina(disciplina);
        t.setFormato(Formato.ELIMINACION_DIRECTA);
        t.setModalidad(Modalidad.PRESENCIAL);
        t.setTipoParticipante(TipoParticipante.INDIVIDUAL);
        t.setCupoMaximo(8);
        t.setCupoMinimo(2);
        t.setValorInscripcion(new BigDecimal("1000.00"));
        t.setPctPozoCompetidores(new BigDecimal("70.00"));
        t.setPctPremioRifa(BigDecimal.ZERO);
        t.setCierreInscripcionEn(Instant.now().plus(5, ChronoUnit.DAYS));
        t.setFechaInicio(Instant.now().plus(7, ChronoUnit.DAYS));
        t.setEstado(estado);
        t.setPublicadoEn(estado != EstadoTorneo.BORRADOR ? Instant.now() : null);
        t.setEliminadoEn(eliminadoEn);
        return torneoRepository.save(t);
    }

    private void inscribir(Torneo torneo, Usuario usuario, EstadoInscripcion estado) {
        Inscripcion i = new Inscripcion();
        i.setTorneo(torneo);
        i.setUsuario(usuario);
        i.setEstado(estado);
        i.setReglamentoAceptadoEn(Instant.now());
        inscripcionRepository.save(i);
    }

    private Usuario crearUsuario(String username) {
        Persona p = new Persona();
        p.setNombres("Nombre " + username);
        p.setApellidos("Apellido " + username);
        p.setEstado(EstadoGeneral.ACTIVO);
        p = personaRepository.save(p);

        Usuario u = new Usuario();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setEstado(EstadoUsuario.ACTIVO);
        u.setEmailVerificado(true);
        u.setPersona(p);
        return usuarioRepository.save(u);
    }
}
