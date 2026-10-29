package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.service.PozoTorneoService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * El reparto de lo vendido entre el pozo del torneo y los premios (PICA-259), con las pruebas de aceptación de
 * HU-405. El torneo no tiene inscripciones pagas, así que su pozo es solo lo que aporta la rifa. Cada test hace
 * rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class RepartoRifaIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant SORTEO = EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS);

    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaService rifaService;
    @Autowired private PozoTorneoService pozoTorneoService;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long comprador;
    private Long otro;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_reparto").getId();
        comprador = usuario("comprador_reparto").getId();
        otro = usuario("otro_reparto").getId();
    }

    // --- pruebas de aceptación de HU-405 --------------------------------------------------------------

    @Test
    void unaVentaDeUnaRifaSinTorneoVaEnteraALosPremios() {
        Long rifa = enVenta(null, 0, "500");

        VentaRifaResultado compra = comprar(comprador, rifa, List.of(1, 2));

        assertThat(compra.pozoTorneo()).isNull();
        assertThat(compra.pozoRifa()).isEqualByComparingTo("1000");
        assertThat(reparto(rifa)).isEqualTo(reparto("1000.00", "0.00", "1000.00"));
    }

    @Test
    void unaVentaDeUnaRifaConTorneoSumaAlPozoDelTorneoSuPorcentaje() {
        Long torneo = torneo();
        Long rifa = enVenta(torneo, 30, "500");
        assertThat(pozoTorneoService.calcularPozo(torneo)).isEqualByComparingTo("0");

        VentaRifaResultado compra = comprar(comprador, rifa, List.of(1, 2));

        assertThat(compra.pozoTorneo()).isEqualByComparingTo("300");
        assertThat(pozoTorneoService.calcularPozo(torneo)).isEqualByComparingTo("300");
        assertThat(reparto(rifa).alPozoTorneo()).isEqualByComparingTo("300");
    }

    @Test
    void unaVentaDeUnaRifaConTorneoSumaAlPozoDeLaRifaElResto() {
        Long rifa = enVenta(torneo(), 30, "500");

        VentaRifaResultado compra = comprar(comprador, rifa, List.of(1, 2));

        assertThat(compra.pozoRifa()).isEqualByComparingTo("700");
        assertThat(reparto(rifa)).isEqualTo(reparto("1000.00", "300.00", "700.00"));
    }

    @Test
    void variasVentasSeguidasAcumulanSinDiferencias() {
        // un precio y un % que no dan redondo, para que se note si se pierde algún centavo
        Long torneo = torneo();
        Long rifa = enVenta(torneo, 33, "333.33");

        comprar(comprador, rifa, List.of(1));
        comprar(otro, rifa, List.of(2, 3));
        VentaRifaResultado ultima = comprar(comprador, rifa, List.of(4, 5, 6));

        RepartoRifa reparto = reparto(rifa);
        assertThat(reparto).isEqualTo(reparto("1999.98", "659.99", "1339.99"));
        assertThat(reparto.alPozoTorneo().add(reparto.aPremios())).isEqualByComparingTo(reparto.recaudado());
        assertThat(ultima.pozoTorneo()).isEqualByComparingTo(reparto.alPozoTorneo());
        assertThat(ultima.pozoRifa()).isEqualByComparingTo(reparto.aPremios());
        assertThat(pozoTorneoService.calcularPozo(torneo)).isEqualByComparingTo(reparto.alPozoTorneo());
    }

    // --- ayudas -------------------------------------------------------------------------------------

    private VentaRifaResultado comprar(Long usuario, Long rifa, List<Integer> numeros) {
        ventaRifaService.reservar(usuario, rifa, numeros);
        VentaRifaResultado compra = ventaRifaService.comprar(usuario, rifa, numeros, "compra-" + usuario + numeros);
        entityManager.flush();
        entityManager.clear();
        return compra;
    }

    private RepartoRifa reparto(Long rifa) {
        return rifaService.detalle(organizador, rifa).reparto();
    }

    private static RepartoRifa reparto(String recaudado, String alPozoTorneo, String aPremios) {
        return new RepartoRifa(new BigDecimal(recaudado), new BigDecimal(alPozoTorneo), new BigDecimal(aPremios));
    }

    /**
     * Una rifa del 1 al 100 que destina {@code pct} al torneo y el resto a un solo premio, publicada y con la venta
     * abierta desde hace una hora.
     */
    private Long enVenta(Long torneo, int pct, String precio) {
        RifaDetalle creada = rifaService.crear(organizador, new RifaRequest("Rifa del reparto", null, 1, 100,
                new BigDecimal(precio), MANIANA, EN_UNA_SEMANA, SORTEO, torneo, BigDecimal.valueOf(pct)));
        rifaService.reemplazarPremios(organizador, creada.id(),
                List.of(new PremioRifaRequest(BigDecimal.valueOf(100 - pct), null)));
        rifaService.publicar(organizador, creada.id());
        // publicar exige una venta futura; para comprar, tiene que haber empezado
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", creada.id());
        entityManager.clear();
        return creada.id();
    }

    /** Un torneo publicado que empieza en un mes: la venta de la rifa entra en sus fechas. */
    private Long torneo() {
        Torneo torneo = new Torneo();
        torneo.setOrganizador(usuarioRepository.getReferenceById(organizador));
        torneo.setNombre("Copa del reparto");
        torneo.setDisciplina("Paddle");
        torneo.setPublicadoEn(Instant.now().minus(10, ChronoUnit.DAYS));
        torneo.setFechaInicio(Instant.now().plus(30, ChronoUnit.DAYS));
        return torneoRepository.saveAndFlush(torneo).getId();
    }

    private Usuario usuario(String nombre) {
        Persona persona = new Persona();
        persona.setNombres(nombre);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(nombre);
        usuario.setEmail(nombre + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        return usuarioRepository.save(usuario);
    }
}
