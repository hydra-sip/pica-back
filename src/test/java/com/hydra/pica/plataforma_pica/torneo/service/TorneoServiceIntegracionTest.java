package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.OpcionesTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.MapAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pasos 1 y 2 del asistente de crear torneo (HU-201, PICA-199) contra Postgres, con V18: un torneo en BORRADOR se
 * guarda incompleto y fuera de BORRADOR la base exige lo que antes era NOT NULL. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class TorneoServiceIntegracionTest {

    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS);
    private static final Instant EN_UN_MES = Instant.now().plus(30, ChronoUnit.DAYS);

    @Autowired private TorneoService torneoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long otro;

    @BeforeEach
    void usuarios() {
        organizador = usuario("org_torneo").getId();
        otro = usuario("otro_torneo").getId();
    }

    // --- alta y modificación ------------------------------------------------

    @Test
    void creaUnBorradorSoloConNombreYDisciplina() {
        TorneoInformacion torneo = torneoService.crear(organizador, minimo());

        assertThat(torneo.id()).isNotNull();
        assertThat(torneo.estado()).isEqualTo(EstadoTorneo.BORRADOR);
        assertThat(torneo.pasoPendiente()).isEqualTo(1);
        assertThat(torneo.nombre()).isEqualTo("Apertura F5");
        assertThat(torneo.disciplina()).isEqualTo("Fútbol 5");
        assertThat(torneo.formato()).isEqualTo(Formato.ELIMINACION_DIRECTA);
        assertThat(torneo.modalidad()).isNull();
        assertThat(torneo.fechaInicio()).isNull();
        assertThat(torneo.cupoMaximo()).isNull();
        assertThat(torneo.canchas()).isEmpty();
        assertThat(torneo.creadoEn()).isNotNull();
    }

    @Test
    void guardaLosPasosUnoYDosYLosDevuelveAlVerlo() {
        TorneoInformacion creado = torneoService.crear(organizador, completo());

        TorneoInformacion visto = torneoService.detalle(organizador, creado.id());
        assertThat(visto.pasoPendiente()).isEqualTo(3);
        assertThat(visto.modalidad()).isEqualTo(Modalidad.PRESENCIAL);
        assertThat(visto.tipoParticipante()).isEqualTo(TipoParticipante.EQUIPO);
        assertThat(visto.sede()).isEqualTo("Club Mercedes");
        assertThat(visto.canchas()).containsExactly("Cancha 1", "Cancha 2");
        assertThat(visto.fechaInicio().truncatedTo(ChronoUnit.MICROS)).isEqualTo(EN_DOS_SEMANAS.truncatedTo(ChronoUnit.MICROS));
        assertThat(visto.cierreInscripcionEn().truncatedTo(ChronoUnit.MICROS)).isEqualTo(EN_UNA_SEMANA.truncatedTo(ChronoUnit.MICROS));
        assertThat(visto.reglamentoTexto()).isEqualTo("Dos tiempos de 20 minutos");
        assertThat(visto.cupoMaximo()).isEqualTo(16);
        assertThat(visto.cupoMinimo()).isEqualTo(12);
        assertThat(visto.tamanioEquipo()).isEqualTo(5);
        assertThat(visto.nominaMaxima()).isEqualTo(10);
    }

    @Test
    void enBorradorUnTorneoPorEquiposPuedeNoTenerNominaTodavia() {
        TorneoInformacion torneo = torneoService.crear(organizador, new InformacionTorneoRequest("Apertura F5",
                "Fútbol 5", null, null, null, TipoParticipante.EQUIPO, null, null, null, null, null, null, null, null,
                5, null));

        assertThat(torneo.tamanioEquipo()).isEqualTo(5);
        assertThat(torneo.nominaMaxima()).isNull();
    }

    @Test
    void actualizarReemplazaLosDatosYLasCanchas() {
        TorneoInformacion creado = torneoService.crear(organizador, completo());

        TorneoInformacion actualizado = torneoService.actualizarInformacion(organizador, creado.id(),
                conCanchas(List.of("Cancha 3")));

        assertThat(actualizado.canchas()).containsExactly("Cancha 3");
        assertThat(actualizado.modificadoEn()).isNotNull();
    }

    // --- validaciones de la HU-201 ------------------------------------------

    @Test
    void fechaDeInicioAnteriorAHoyEsInvalida() {
        Instant ayer = Instant.now().minus(1, ChronoUnit.DAYS);
        assertThatValidacion(() -> torneoService.crear(organizador, conFechas(ayer, null, null)))
                .containsEntry("fechaInicio", ErrorCampo.Codigo.FECHAS_INVALIDAS);
    }

    @Test
    void fechaDeFinAnteriorAlInicioEsInvalida() {
        assertThatValidacion(() -> torneoService.crear(organizador, conFechas(EN_DOS_SEMANAS, EN_UNA_SEMANA, null)))
                .containsEntry("fechaFin", ErrorCampo.Codigo.FECHAS_INVALIDAS);
    }

    @Test
    void laInscripcionTieneQueCerrarAntesDelInicio() {
        assertThatValidacion(() -> torneoService.crear(organizador, conFechas(EN_UNA_SEMANA, EN_UN_MES, EN_DOS_SEMANAS)))
                .containsEntry("cierreInscripcionEn", ErrorCampo.Codigo.FECHAS_INVALIDAS);
    }

    @Test
    void presencialSinSedeLaPide() {
        assertThatValidacion(() -> torneoService.crear(organizador, new InformacionTorneoRequest("Apertura F5",
                "Fútbol 5", null, null, Modalidad.PRESENCIAL, null, " ", null, null, null, null, null, null, null,
                null, null)))
                .containsEntry("sede", ErrorCampo.Codigo.REQUERIDO);
    }

    @Test
    void unTorneoOnlineNoTieneCanchas() {
        assertThatValidacion(() -> torneoService.crear(organizador, new InformacionTorneoRequest("Copa FIFA",
                "eSports", null, null, Modalidad.ONLINE, null, null, List.of("Cancha 1"), null, null, null, null, null,
                null, null, null)))
                .containsEntry("canchas", ErrorCampo.Codigo.VALOR_INVALIDO);
    }

    @Test
    void cupoMenorADosOMayorAlDeLaPlataformaEsInvalido() {
        assertThatValidacion(() -> torneoService.crear(organizador, conCupos(1, null)))
                .containsEntry("cupoMaximo", ErrorCampo.Codigo.RANGO_INVALIDO);
        assertThatValidacion(() -> torneoService.crear(organizador, conCupos(65, null)))
                .containsEntry("cupoMaximo", ErrorCampo.Codigo.RANGO_INVALIDO);
        assertThat(torneoService.crear(organizador, conCupos(64, 2)).cupoMaximo()).isEqualTo(64);
    }

    @Test
    void elMinimoParaIniciarNoPuedeSuperarAlCupo() {
        assertThatValidacion(() -> torneoService.crear(organizador, conCupos(8, 10)))
                .containsEntry("cupoMinimo", ErrorCampo.Codigo.RANGO_INVALIDO);
    }

    @Test
    void laNominaNoPuedeSerMenorQueElTamanioDeEquipo() {
        assertThatValidacion(() -> torneoService.crear(organizador, new InformacionTorneoRequest("Apertura F5",
                "Fútbol 5", null, null, null, TipoParticipante.EQUIPO, null, null, null, null, null, null, null, null,
                5, 4)))
                .containsEntry("nominaMaxima", ErrorCampo.Codigo.RANGO_INVALIDO);
    }

    @Test
    void unTorneoIndividualNoTieneTamanioDeEquipo() {
        assertThatValidacion(() -> torneoService.crear(organizador, new InformacionTorneoRequest("Abierto",
                "Ajedrez", null, null, null, TipoParticipante.INDIVIDUAL, null, null, null, null, null, null, null,
                null, 5, null)))
                .containsEntry("tamanioEquipo", ErrorCampo.Codigo.VALOR_INVALIDO);
    }

    // --- canchas y opciones -------------------------------------------------

    @Test
    void lasCanchasSonDeLaSedeYSeReusan() {
        torneoService.crear(organizador, completo());
        TorneoInformacion segundo = torneoService.crear(otro, new InformacionTorneoRequest("Clausura F5", "Fútbol 5",
                null, null, Modalidad.PRESENCIAL, null, "club mercedes", List.of("cancha 1", "Cancha 4", "CANCHA 4"),
                null, null, null, null, null, null, null, null));

        assertThat(segundo.canchas()).containsExactly("Cancha 1", "Cancha 4");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cancha WHERE lower(sede) = 'club mercedes'", Integer.class))
                .isEqualTo(3);
        assertThat(torneoService.opciones("Club Mercedes").canchas()).containsExactly("Cancha 1", "Cancha 2", "Cancha 4");
    }

    @Test
    void opcionesTraeLasDisciplinasSugeridasYElCupoDeLaPlataforma() {
        OpcionesTorneo opciones = torneoService.opciones(null);

        assertThat(opciones.disciplinas()).extracting(OpcionesTorneo.Disciplina::nombre).contains("Fútbol 5", "Ajedrez");
        assertThat(opciones.formatos()).filteredOn(OpcionesTorneo.FormatoOpcion::disponible)
                .extracting(OpcionesTorneo.FormatoOpcion::codigo).containsExactly("ELIMINACION_DIRECTA");
        assertThat(opciones.modalidades()).containsExactly(Modalidad.PRESENCIAL, Modalidad.ONLINE);
        assertThat(opciones.cupoMaximo()).isEqualTo(64);
        assertThat(opciones.comisionMaxima()).isEqualByComparingTo("10");
        assertThat(opciones.canchas()).isEmpty();
    }

    // --- dueño y estado -----------------------------------------------------

    @Test
    void elTorneoDeOtroOrganizadorNoExiste() {
        Long id = torneoService.crear(organizador, minimo()).id();

        assertThatThrownBy(() -> torneoService.detalle(otro, id))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
        assertThatThrownBy(() -> torneoService.actualizarInformacion(otro, id, minimo()))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void fueraDeBorradorNoSeModifica() {
        Long id = torneoService.crear(organizador, completo()).id();
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 1000, pct_pozo_competidores = 70, pct_premio_rifa = 20,
                                  estado = 'INSCRIPCION_ABIERTA'
                WHERE id = ?""", id);
        // el UPDATE no pasa por JPA: sin esto el servicio ve el torneo en BORRADOR que tiene en memoria
        entityManager.clear();

        assertThatThrownBy(() -> torneoService.actualizarInformacion(organizador, id, completo()))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
    }

    @Test
    void laBaseExigeElTorneoCompletoFueraDeBorrador() {
        Long id = torneoService.crear(organizador, minimo()).id();

        assertThatThrownBy(() -> jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_ABIERTA' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_torneo_completo_fuera_de_borrador");
    }

    @Test
    void organizanTorneosElOrganizadorElAdministradorYElSuperUsuario() {
        assertThat(jdbc.queryForList("""
                SELECT r.nombre FROM rol r
                JOIN rol_permiso rp ON rp.rol_id = r.id
                JOIN permiso p ON p.id = rp.permiso_id
                WHERE p.codigo = 'TORNEO_GESTIONAR' ORDER BY r.nombre""", String.class))
                .containsExactly("ADMINISTRADOR", "ORGANIZADOR", "SUPER_USUARIO");
    }

    // --- helpers --------------------------------------------------------------

    private static InformacionTorneoRequest minimo() {
        return new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static InformacionTorneoRequest completo() {
        return conCanchas(List.of("Cancha 1", "Cancha 2"));
    }

    private static InformacionTorneoRequest conCanchas(List<String> canchas) {
        return new InformacionTorneoRequest(" Apertura F5 ", "Fútbol 5", null, Formato.ELIMINACION_DIRECTA,
                Modalidad.PRESENCIAL, TipoParticipante.EQUIPO, "Club Mercedes", canchas, EN_DOS_SEMANAS, EN_UN_MES,
                EN_UNA_SEMANA, "Dos tiempos de 20 minutos", 16, 12, 5, 10);
    }

    private static InformacionTorneoRequest conFechas(Instant inicio, Instant fin, Instant cierre) {
        return new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null, null, null, null, null, inicio,
                fin, cierre, null, null, null, null, null);
    }

    private static InformacionTorneoRequest conCupos(Integer maximo, Integer minimo) {
        return new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null, null, null, null, null, null, null,
                null, null, maximo, minimo, null, null);
    }

    /** Los errores del 400 como campo → código. */
    @SuppressWarnings("unchecked")
    private static MapAssert<String, ErrorCampo.Codigo> assertThatValidacion(ThrowingCallable llamada) {
        Throwable error = catchThrowable(llamada);
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) error;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.getCodigo()).isEqualTo(CodigoError.VALIDACION);
        List<ErrorCampo> errores = (List<ErrorCampo>) api.getPropiedades().get("errores");
        Map<String, ErrorCampo.Codigo> porCampo = new LinkedHashMap<>();
        errores.forEach(e -> porCampo.putIfAbsent(e.campo(), e.codigo()));
        return assertThat(porCampo);
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
