package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.almacenamiento.AlmacenArchivos;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * El PDF del reglamento (PICA-200) contra Postgres y el almacenamiento local: el hash, que cuenta para el paso 1, quién
 * lo ve y que publicado no se toca. Cada test hace rollback; los archivos quedan en la carpeta temporal.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class ReglamentoTorneoServiceIntegracionTest {

    private static final byte[] PDF = "%PDF-1.7\nreglamento de prueba\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final String URL = "http://localhost/api/v1/torneos/1/reglamento";

    @Autowired private ReglamentoTorneoService reglamentoService;
    @Autowired private TorneoService torneoService;
    @Autowired private AlmacenArchivos almacen;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private EconomiaTorneoService economiaService;
    @Autowired private PublicacionTorneoService publicacionService;

    private Long organizador;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_regl").getId();
    }

    @Test
    void subirGuardaElArchivoYSuHash() {
        Long id = borrador(null);

        TorneoInformacion torneo = reglamentoService.subir(organizador, id, PDF, URL);

        assertThat(torneo.reglamentoUrl()).isEqualTo(URL);
        assertThat(torneo.reglamentoHash()).isEqualTo(SelloTorneo.sha256(PDF));
        assertThat(reglamentoService.pdf(Optional.of(organizador), id)).isEqualTo(PDF);
    }

    @Test
    void elPdfAlcanzaComoReglamentoDelPaso1YQuitarloLoVuelveAPedir() {
        Long id = borrador(null);
        assertThat(torneoService.detalle(organizador, id).pasoPendiente()).isEqualTo(1);

        assertThat(reglamentoService.subir(organizador, id, PDF, URL).pasoPendiente()).isEqualTo(3);

        TorneoInformacion sinPdf = reglamentoService.quitar(organizador, id);
        assertThat(sinPdf.reglamentoUrl()).isNull();
        assertThat(sinPdf.reglamentoHash()).isNull();
        assertThat(sinPdf.pasoPendiente()).isEqualTo(1);
        assertThat(almacen.leer("torneos/" + id + "/reglamento.pdf")).isEmpty();
    }

    @Test
    void otroPdfReemplazaAlAnterior() {
        Long id = borrador("Reglas");
        reglamentoService.subir(organizador, id, PDF, URL);
        byte[] otro = "%PDF-1.4\notra versión".getBytes(StandardCharsets.UTF_8);

        TorneoInformacion torneo = reglamentoService.subir(organizador, id, otro, URL);

        assertThat(torneo.reglamentoHash()).isEqualTo(SelloTorneo.sha256(otro));
        assertThat(reglamentoService.pdf(Optional.of(organizador), id)).isEqualTo(otro);
    }

    @Test
    void soloAceptaPdf() {
        Long id = borrador(null);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n'};

        assertThatThrownBy(() -> reglamentoService.subir(organizador, id, png, URL))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(errores(e)).extracting(ErrorCampo::codigo)
                        .containsExactly(ErrorCampo.Codigo.FORMATO_INVALIDO));
        assertThatThrownBy(() -> reglamentoService.subir(organizador, id,
                new byte[ReglamentoTorneoService.TAMANIO_MAXIMO + 1], URL))
                .satisfies(e -> assertThat(errores(e)).extracting(ErrorCampo::codigo)
                        .containsExactly(ErrorCampo.Codigo.LONGITUD));
        assertThat(torneoService.detalle(organizador, id).reglamentoUrl()).isNull();
    }

    @Test
    void enBorradorSoloLoVeSuOrganizador() {
        Long id = borrador(null);
        reglamentoService.subir(organizador, id, PDF, URL);
        Long otro = usuario("otro_regl").getId();

        assertThatThrownBy(() -> reglamentoService.pdf(Optional.empty(), id))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
        assertThatThrownBy(() -> reglamentoService.pdf(Optional.of(otro), id))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
        assertThatThrownBy(() -> reglamentoService.subir(otro, id, PDF, URL))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void alPublicarSeSellaElPdfYDespuesLoVeCualquieraPeroNoSeToca() {
        Long id = borrador("Reglas");
        reglamentoService.subir(organizador, id, PDF, URL);
        economiaService.actualizarEconomia(organizador, id, new EconomiaTorneoRequest(new BigDecimal("20000"),
                List.of(new BigDecimal("100"))));

        TorneoInformacion publicado = publicacionService.publicar(organizador, id);

        assertThat(publicado.estado()).isEqualTo(EstadoTorneo.INSCRIPCION_ABIERTA);
        assertThat(publicado.reglamentoHash()).isEqualTo(SelloTorneo.sha256(PDF));
        assertThat(reglamentoService.pdf(Optional.empty(), id)).isEqualTo(PDF);
        assertThatThrownBy(() -> reglamentoService.quitar(organizador, id))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
        assertThatThrownBy(() -> reglamentoService.subir(organizador, id, PDF, URL))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
    }

    @Test
    void sinPdfResponde404ConSuCodigo() {
        Long id = borrador("Reglas");

        assertThatThrownBy(() -> reglamentoService.pdf(Optional.of(organizador), id))
                .extracting("codigo").isEqualTo(CodigoError.REGLAMENTO_NO_ENCONTRADO);
        // quitar sin PDF no hace nada
        assertThat(reglamentoService.quitar(organizador, id).reglamentoTexto()).isEqualTo("Reglas");
    }

    // --- helpers --------------------------------------------------------------

    /** El paso 1 completo salvo, si {@code reglamento} es null, el reglamento. */
    private Long borrador(String reglamento) {
        Instant inicio = Instant.now().plus(14, ChronoUnit.DAYS);
        Instant cierre = Instant.now().plus(7, ChronoUnit.DAYS);
        Long id = torneoService.crear(organizador, new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null,
                null, null, null, null, null, null, null, null, 16, 2, null, null)).id();
        torneoService.actualizarInformacion(organizador, id, new InformacionTorneoRequest("Apertura F5", "Fútbol 5",
                null, null, Modalidad.PRESENCIAL, TipoParticipante.EQUIPO, "Club Mercedes", List.of(), inicio, null,
                cierre, reglamento, 16, 12, 5, 10));
        return id;
    }

    @SuppressWarnings("unchecked")
    private static List<ErrorCampo> errores(Throwable error) {
        return (List<ErrorCampo>) ((ApiException) error).getPropiedades().get("errores");
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
