package com.hydra.pica.plataforma_pica.control.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Comparator;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.dto.RegistroAuditoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * El admin consulta el libro de auditoría (HU-704, PICA-345) contra Postgres: las pruebas de aceptación de la
 * historia. Los registros del escenario van en enero de 2020 para que no se mezclen con los que escribe el sistema
 * al armar el torneo. Cada test hace rollback.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
class AuditoriaConsultaIntegracionTest {

    private static final Instant ENERO = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant FEBRERO = Instant.parse("2020-02-01T00:00:00Z");

    @Autowired private AuditoriaService auditoriaService;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JdbcTemplate jdbc;

    private Long ana;
    private Long beto;
    private Long torneo;

    @BeforeEach
    void escenario() {
        ana = escenario.usuario("ana_audit", "ORGANIZADOR").getId();
        beto = escenario.usuario("beto_audit", "ARBITRO").getId();
        torneo = escenario.individualEnCurso(ana, beto, "Copa Auditoría", "audit", 2);
    }

    @Test
    @DisplayName("Sin filtros lista todo, del más nuevo al más viejo y paginado")
    void sinFiltros() {
        registro(ana, AccionAuditoria.LOGIN, "USUARIO", ana, "2020-01-05T10:00:00Z");
        long total = jdbc.queryForObject("SELECT count(*) FROM auditoria", Long.class);

        Page<RegistroAuditoria> pagina = auditoriaService.listar(null, null, null, null, null, 0, 100);

        assertThat(pagina.getTotalElements()).isEqualTo(total);
        assertThat(pagina.getContent()).extracting(RegistroAuditoria::fecha)
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    @DisplayName("Pagina de a size, empezando por el más nuevo")
    void paginado() {
        Long viejo = registro(ana, AccionAuditoria.LOGIN, "USUARIO", ana, "2020-01-01T10:00:00Z");
        Long medio = registro(ana, AccionAuditoria.LOGOUT, "USUARIO", ana, "2020-01-02T10:00:00Z");
        Long nuevo = registro(ana, AccionAuditoria.LOGIN, "USUARIO", ana, "2020-01-03T10:00:00Z");

        Page<RegistroAuditoria> primera = auditoriaService.listar(null, ana, null, ENERO, FEBRERO, 0, 2);
        Page<RegistroAuditoria> segunda = auditoriaService.listar(null, ana, null, ENERO, FEBRERO, 1, 2);

        assertThat(primera.getTotalElements()).isEqualTo(3);
        assertThat(primera.getTotalPages()).isEqualTo(2);
        assertThat(primera.getContent()).extracting(RegistroAuditoria::id).containsExactly(nuevo, medio);
        assertThat(segunda.getContent()).extracting(RegistroAuditoria::id).containsExactly(viejo);
    }

    @Test
    @DisplayName("Cada fila trae fecha, usuario, acción, entidad, id, IP y detalle")
    void fila() {
        Long id = registro(ana, AccionAuditoria.LOGIN, "USUARIO", ana, "2020-01-05T10:00:00Z");

        RegistroAuditoria fila = auditoriaService.listar(null, ana, null, ENERO, FEBRERO, 0, 20).getContent().get(0);

        assertThat(fila.id()).isEqualTo(id);
        assertThat(fila.fecha()).isEqualTo(Instant.parse("2020-01-05T10:00:00Z"));
        assertThat(fila.usuarioId()).isEqualTo(ana);
        assertThat(fila.username()).isEqualTo("ana_audit");
        assertThat(fila.accion()).isEqualTo(AccionAuditoria.LOGIN);
        assertThat(fila.entidad()).isEqualTo("USUARIO");
        assertThat(fila.entidadId()).isEqualTo(String.valueOf(ana));
        assertThat(fila.ip()).isEqualTo("10.0.0.1");
        assertThat(fila.detalle()).containsEntry("origen", "test");
    }

    @Test
    @DisplayName("Un torneo, usuario o acción sin registros devuelve la lista vacía")
    void filtroSinResultados() {
        registro(ana, AccionAuditoria.LOGIN, "USUARIO", ana, "2020-01-05T10:00:00Z");

        assertThat(auditoriaService.listar(Long.MAX_VALUE, null, null, null, null, 0, 20)).isEmpty();
        assertThat(auditoriaService.listar(null, Long.MAX_VALUE, null, null, null, 0, 20)).isEmpty();
        assertThat(auditoriaService.listar(null, ana, AccionAuditoria.REINTEGRO, null, null, 0, 20)).isEmpty();
    }

    @Test
    @DisplayName("Con un rango de fechas solo trae lo de ese rango: desde incluido, hasta excluido")
    void rangoDeFechas() {
        registro(ana, AccionAuditoria.LOGIN, "USUARIO", ana, "2020-01-01T23:59:59Z");
        Long delDia = registro(ana, AccionAuditoria.LOGIN, "USUARIO", ana, "2020-01-02T00:00:00Z");
        Long tarde = registro(ana, AccionAuditoria.LOGOUT, "USUARIO", ana, "2020-01-02T23:59:59Z");
        registro(ana, AccionAuditoria.LOGIN, "USUARIO", ana, "2020-01-03T00:00:00Z");

        Page<RegistroAuditoria> pagina = auditoriaService.listar(null, ana, null,
                Instant.parse("2020-01-02T00:00:00Z"), Instant.parse("2020-01-03T00:00:00Z"), 0, 20);

        assertThat(pagina.getContent()).extracting(RegistroAuditoria::id).containsExactly(tarde, delDia);
    }

    @Test
    @DisplayName("El filtro por torneo trae lo del torneo, sus partidos, su rifa y sus custodias")
    void delTorneo() {
        Long partido = escenario.partido(torneo, 1, 1);
        Long rifa = rifaDelTorneo();
        Long custodiaTorneo = jdbc.queryForObject(
                "INSERT INTO custodia (torneo_id) VALUES (?) RETURNING id", Long.class, torneo);
        Long custodiaRifa = jdbc.queryForObject(
                "INSERT INTO custodia (rifa_id) VALUES (?) RETURNING id", Long.class, rifa);

        Long delTorneo = registro(ana, AccionAuditoria.CIERRE_INSCRIPCION, "TORNEO", torneo, "2020-01-01T10:00:00Z");
        Long delPartido = registro(beto, AccionAuditoria.LAUDO, "PARTIDO", partido, "2020-01-02T10:00:00Z");
        Long deLaRifa = registro(null, AccionAuditoria.SORTEO, "RIFA", rifa, "2020-01-03T10:00:00Z");
        Long pagoTorneo = registro(ana, AccionAuditoria.PAGO, "CUSTODIA", custodiaTorneo, "2020-01-04T10:00:00Z");
        Long pagoRifa = registro(beto, AccionAuditoria.PAGO, "CUSTODIA", custodiaRifa, "2020-01-05T10:00:00Z");
        // el mismo número en otra entidad, y otro torneo: no son del torneo
        registro(ana, AccionAuditoria.LOGIN, "USUARIO", torneo, "2020-01-06T10:00:00Z");
        registro(ana, AccionAuditoria.CIERRE_INSCRIPCION, "TORNEO", Long.MAX_VALUE, "2020-01-07T10:00:00Z");
        registro(ana, AccionAuditoria.PAGO, "CUSTODIA", Long.MAX_VALUE, "2020-01-08T10:00:00Z");

        Page<RegistroAuditoria> pagina = auditoriaService.listar(torneo, null, null, ENERO, FEBRERO, 0, 20);

        assertThat(pagina.getContent()).extracting(RegistroAuditoria::id)
                .containsExactly(pagoRifa, pagoTorneo, deLaRifa, delPartido, delTorneo);
    }

    @Test
    @DisplayName("Combinando torneo, usuario y fechas, cada fila cumple todas las condiciones")
    void filtrosCombinados() {
        Long partido = escenario.partido(torneo, 1, 1);
        Long buscado = registro(beto, AccionAuditoria.LAUDO, "PARTIDO", partido, "2020-01-10T10:00:00Z");
        registro(beto, AccionAuditoria.LAUDO, "PARTIDO", partido, "2020-01-20T10:00:00Z");       // fuera de rango
        registro(ana, AccionAuditoria.MODIFICACION, "TORNEO", torneo, "2020-01-10T11:00:00Z");  // otro usuario
        registro(beto, AccionAuditoria.LOGIN, "USUARIO", beto, "2020-01-10T12:00:00Z");         // otro torneo

        Page<RegistroAuditoria> pagina = auditoriaService.listar(torneo, beto, null,
                Instant.parse("2020-01-10T00:00:00Z"), Instant.parse("2020-01-11T00:00:00Z"), 0, 20);
        Page<RegistroAuditoria> conAccion = auditoriaService.listar(torneo, beto, AccionAuditoria.LAUDO,
                ENERO, FEBRERO, 0, 20);

        assertThat(pagina.getContent()).extracting(RegistroAuditoria::id).containsExactly(buscado);
        assertThat(conAccion.getContent()).hasSize(2)
                .allSatisfy(fila -> assertThat(fila.accion()).isEqualTo(AccionAuditoria.LAUDO));
    }

    private Long registro(Long usuario, AccionAuditoria accion, String entidad, Long entidadId, String fecha) {
        return jdbc.queryForObject("""
                INSERT INTO auditoria (usuario_id, accion, entidad, entidad_id, ip, detalle, creado_en)
                VALUES (?, ?, ?, ?, '10.0.0.1', '{"origen": "test"}'::jsonb, ?::timestamptz)
                RETURNING id""", Long.class, usuario, accion.name(), entidad, String.valueOf(entidadId), fecha);
    }

    private Long rifaDelTorneo() {
        return jdbc.queryForObject("""
                INSERT INTO rifa (organizador_id, torneo_id, titulo, numero_desde, numero_hasta, precio_numero,
                                  venta_desde, venta_hasta, terminos_aceptados_en, pct_gastos_administrativos)
                VALUES (?, ?, 'Rifa', 1, 100, 500, now() - interval '1 hour', now() + interval '7 days', now(), 10)
                RETURNING id""", Long.class, ana, torneo);
    }
}
