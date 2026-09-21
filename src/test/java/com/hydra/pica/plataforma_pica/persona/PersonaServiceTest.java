package com.hydra.pica.plataforma_pica.persona;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Casos de {@code PersonaService.buscarOCrear(DatosPersona)} según PICA-109.
 *
 * Están deshabilitados hasta que entre PICA-108 (entidad Persona y PersonaRepository), que es lo que
 * el servicio necesita para existir. Cuando eso esté en dev: mergear dev acá, escribir el servicio,
 * y a cada método sacarle el @Disabled y completarlo con Mockito sobre PersonaRepository.
 */
class PersonaServiceTest {

    @Test
    @Disabled("Espera PICA-108")
    @DisplayName("No existe persona con ese documento: la crea con los datos recibidos y la devuelve")
    void noExisteLaCrea() {
        // dado: findByTipoDocAndNroDoc devuelve vacío
        // cuando: buscarOCrear(DNI 30123456, Juan Pérez, ...)
        // entonces: save() recibe una Persona ACTIVO con esos datos, y se devuelve la guardada
    }

    @Test
    @Disabled("Espera PICA-108")
    @DisplayName("Existe y está activa: la devuelve tal cual, sin pisar sus datos")
    void existeActivaLaDevuelveSinModificar() {
        // dado: existe Persona activa "Juan Pérez" con ese documento
        // cuando: buscarOCrear con los mismos datos
        // entonces: devuelve esa persona, save() no se llama
    }

    @Test
    @Disabled("Espera PICA-108")
    @DisplayName("Existe con datos distintos: la devuelve igual y solo deja un aviso en el log")
    void existeConDatosDistintosSoloLoguea() {
        // dado: existe Persona activa "Juan Pérez" con ese documento
        // cuando: buscarOCrear con apellidos "Peres" y otra fecha de nacimiento
        // entonces: devuelve la existente sin cambios; hay un WARN con el documento y los campos que difieren
    }

    @Test
    @Disabled("Espera PICA-108")
    @DisplayName("Existe pero está inactiva o eliminada: 409 PERSONA_INACTIVA")
    void existeInactivaRechaza() {
        // dado: existe Persona con estado INACTIVO (o con eliminadoEn != null) con ese documento
        // cuando: buscarOCrear
        // entonces: lanza PersonaInactivaException; save() no se llama
        // ojo: el repo filtra eliminados por defecto (PICA-108); acá hace falta una búsqueda que los incluya
    }

    @Test
    @Disabled("Espera PICA-108")
    @DisplayName("Sin documento (caso Google): siempre crea una persona nueva")
    void sinDocumentoSiempreCrea() {
        // dado: DatosPersona.sinDocumento("Juan", "Pérez")
        // cuando: buscarOCrear
        // entonces: no busca por documento; save() recibe una Persona sin tipoDoc/nroDoc, ACTIVO
    }
}
