package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Casos de {@link PersonaService#buscarOCrear(DatosPersona)} según PICA-109, con el repositorio
 * mockeado. Que la búsqueda nativa realmente incluya eliminadas se prueba aparte contra Postgres
 * en {@code PersonaRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
class PersonaServiceTest {

    private static final DatosPersona JUAN = new DatosPersona(
            TipoDoc.DNI, "30123456", "Juan", "Pérez", LocalDate.of(1990, 5, 20), "Calle 123", "1155551234");

    @Mock
    private PersonaRepository personaRepository;

    @InjectMocks
    private PersonaService personaService;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void capturarLog() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(PersonaService.class)).addAppender(logs);
    }

    @AfterEach
    void soltarLog() {
        ((Logger) LoggerFactory.getLogger(PersonaService.class)).detachAppender(logs);
    }

    @Test
    @DisplayName("No existe persona con ese documento: la crea con los datos recibidos y la devuelve")
    void noExisteLaCrea() {
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456")).thenReturn(Optional.empty());
        when(personaRepository.saveAndFlush(any(Persona.class))).thenAnswer(inv -> inv.getArgument(0));

        Persona resultado = personaService.buscarOCrear(JUAN);

        ArgumentCaptor<Persona> guardada = ArgumentCaptor.forClass(Persona.class);
        verify(personaRepository).saveAndFlush(guardada.capture());
        assertThat(resultado).isSameAs(guardada.getValue());
        assertThat(guardada.getValue().getTipoDoc()).isEqualTo("DNI");
        assertThat(guardada.getValue().getNroDoc()).isEqualTo("30123456");
        assertThat(guardada.getValue().getNombres()).isEqualTo("Juan");
        assertThat(guardada.getValue().getApellidos()).isEqualTo("Pérez");
        assertThat(guardada.getValue().getFechaNacimiento()).isEqualTo(LocalDate.of(1990, 5, 20));
        assertThat(guardada.getValue().getDomicilioPostal()).isEqualTo("Calle 123");
        assertThat(guardada.getValue().getTelefono()).isEqualTo("1155551234");
        assertThat(guardada.getValue().getEstado()).isEqualTo(EstadoGeneral.ACTIVO);
    }

    @Test
    @DisplayName("Existe y está activa: la devuelve tal cual, sin pisar sus datos")
    void existeActivaLaDevuelveSinModificar() {
        Persona existente = personaActiva("Juan", "Pérez");
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456"))
                .thenReturn(Optional.of(existente));

        Persona resultado = personaService.buscarOCrear(JUAN);

        assertThat(resultado).isSameAs(existente);
        verify(personaRepository, never()).saveAndFlush(any());
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    @DisplayName("Existe con datos distintos: la devuelve igual y solo deja un aviso en el log")
    void existeConDatosDistintosSoloLoguea() {
        Persona existente = personaActiva("Juan", "Pérez");
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456"))
                .thenReturn(Optional.of(existente));
        DatosPersona otros = new DatosPersona(
                TipoDoc.DNI, "30123456", "Juan", "Peres", LocalDate.of(1991, 1, 1), null, null);

        Persona resultado = personaService.buscarOCrear(otros);

        assertThat(resultado).isSameAs(existente);
        assertThat(existente.getApellidos()).isEqualTo("Pérez");
        assertThat(existente.getFechaNacimiento()).isEqualTo(LocalDate.of(1990, 5, 20));
        verify(personaRepository, never()).saveAndFlush(any());
        assertThat(logs.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("DNI 30123456", "apellidos", "fechaNacimiento")
                .doesNotContain("nombres");
    }

    @Test
    @DisplayName("Existe pero está inactiva: 409 PERSONA_INACTIVA")
    void existeInactivaRechaza() {
        Persona inactiva = personaActiva("Juan", "Pérez");
        inactiva.setEstado(EstadoGeneral.INACTIVO);
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456"))
                .thenReturn(Optional.of(inactiva));

        assertThatThrownBy(() -> personaService.buscarOCrear(JUAN))
                .isInstanceOf(PersonaInactivaException.class)
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_INACTIVA);
        verify(personaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Existe pero está eliminada (baja lógica): 409 PERSONA_INACTIVA")
    void existeEliminadaRechaza() {
        Persona eliminada = personaActiva("Juan", "Pérez");
        eliminada.setEliminadoEn(Instant.now());
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456"))
                .thenReturn(Optional.of(eliminada));

        assertThatThrownBy(() -> personaService.buscarOCrear(JUAN))
                .isInstanceOf(PersonaInactivaException.class);
        verify(personaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Sin documento (caso Google): siempre crea una persona nueva")
    void sinDocumentoSiempreCrea() {
        when(personaRepository.saveAndFlush(any(Persona.class))).thenAnswer(inv -> inv.getArgument(0));

        Persona resultado = personaService.buscarOCrear(DatosPersona.sinDocumento("Juan", "Pérez"));

        verify(personaRepository, never()).findByDocumentoIncluyendoEliminadas(anyString(), anyString());
        assertThat(resultado.getTipoDoc()).isNull();
        assertThat(resultado.getNroDoc()).isNull();
        assertThat(resultado.getNombres()).isEqualTo("Juan");
        assertThat(resultado.getApellidos()).isEqualTo("Pérez");
        assertThat(resultado.getEstado()).isEqualTo(EstadoGeneral.ACTIVO);
    }

    @Test
    @DisplayName("Carrera con el mismo documento: el choque con el índice único da 409, no un 500")
    void documentoCreadoEnParaleloDa409() {
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456"))
                .thenReturn(Optional.empty());
        when(personaRepository.saveAndFlush(any(Persona.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key",
                        new ConstraintViolationException("dup", new SQLException(),
                                "uq_persona_tipo_doc_nro_doc")));

        assertThatThrownBy(() -> personaService.buscarOCrear(
                new DatosPersona(TipoDoc.DNI, "30123456", "Juan", "Pérez", null, null, null)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.DOCUMENTO_DUPLICADO);
    }

    @Test
    @DisplayName("Otra violación de integridad se propaga tal cual: no es cosa del documento")
    void otraViolacionSePropaga() {
        DataIntegrityViolationException otra = new DataIntegrityViolationException("nombres not null");
        when(personaRepository.saveAndFlush(any(Persona.class))).thenThrow(otra);

        assertThatThrownBy(() -> personaService.buscarOCrear(DatosPersona.sinDocumento("Juan", "Pérez")))
                .isSameAs(otra);
    }

    @Test
    @DisplayName("asignarDocumento: si nadie lo tiene, lo carga y guarda")
    void asignarDocumentoLibre() {
        Persona google = sinDocumento(7L);
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456")).thenReturn(Optional.empty());

        personaService.asignarDocumento(google, TipoDoc.DNI, "30123456");

        assertThat(google.getTipoDoc()).isEqualTo("DNI");
        assertThat(google.getNroDoc()).isEqualTo("30123456");
        verify(personaRepository).saveAndFlush(google);
    }

    @Test
    @DisplayName("asignarDocumento: si es de otra persona (aunque esté eliminada) da 409 DOCUMENTO_DUPLICADO")
    void asignarDocumentoDeOtra() {
        Persona google = sinDocumento(7L);
        Persona otra = personaActiva("Juan", "Pérez");
        otra.setEliminadoEn(Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(otra, "id", 99L);
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456")).thenReturn(Optional.of(otra));

        assertThatThrownBy(() -> personaService.asignarDocumento(google, TipoDoc.DNI, "30123456"))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.DOCUMENTO_DUPLICADO);
        assertThat(google.getTipoDoc()).isNull();
        verify(personaRepository, never()).saveAndFlush(any(Persona.class));
    }

    @Test
    @DisplayName("asignarDocumento: el choque con el índice único por una carga en paralelo da 409")
    void asignarDocumentoEnParalelo() {
        Persona google = sinDocumento(7L);
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456")).thenReturn(Optional.empty());
        when(personaRepository.saveAndFlush(any(Persona.class)))
                .thenThrow(new DataIntegrityViolationException("dup",
                        new ConstraintViolationException("dup", new SQLException(),
                                "uq_persona_tipo_doc_nro_doc")));

        assertThatThrownBy(() -> personaService.asignarDocumento(google, TipoDoc.DNI, "30123456"))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.DOCUMENTO_DUPLICADO);
    }

    private static Persona sinDocumento(Long id) {
        Persona persona = new Persona();
        ReflectionTestUtils.setField(persona, "id", id);
        persona.setNombres("Ana");
        persona.setApellidos("Gómez");
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }

    private static Persona personaActiva(String nombres, String apellidos) {
        Persona persona = new Persona();
        persona.setNombres(nombres);
        persona.setApellidos(apellidos);
        persona.setTipoDoc("DNI");
        persona.setNroDoc("30123456");
        persona.setFechaNacimiento(LocalDate.of(1990, 5, 20));
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }
}
