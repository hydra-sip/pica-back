package com.hydra.pica.plataforma_pica.user.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resuelve la persona a la que se va a vincular un usuario nuevo (PICA-109).
 *
 * La regla es simple: si viene documento se busca por él; si ya hay una persona activa se
 * reutiliza y si no existe se crea. Si existe pero está inactiva o eliminada se corta con
 * 409 PERSONA_INACTIVA, porque la baja la tiene que revertir un administrador. Sin documento
 * (Google) no hay con qué buscar y siempre se crea.
 *
 * Nunca pisa datos de una persona existente: si lo que llega no coincide con lo guardado
 * solo queda un WARN en el log para que se revise a mano.
 */
@Service
@RequiredArgsConstructor
public class PersonaService {

    private static final Logger log = LoggerFactory.getLogger(PersonaService.class);

    private final PersonaRepository personaRepository;

    @Transactional
    public Persona buscarOCrear(DatosPersona datos) {
        if (!datos.tieneDocumento()) {
            return personaRepository.save(nueva(datos));
        }

        return personaRepository
                .findByDocumentoIncluyendoEliminadas(datos.tipoDoc().name(), datos.nroDoc())
                .map(existente -> {
                    if (existente.getEliminadoEn() != null || existente.getEstado() != EstadoGeneral.ACTIVO) {
                        throw new PersonaInactivaException(datos.tipoDoc(), datos.nroDoc());
                    }
                    avisarSiDifieren(existente, datos);
                    return existente;
                })
                .orElseGet(() -> personaRepository.save(nueva(datos)));
    }

    private Persona nueva(DatosPersona datos) {
        Persona persona = new Persona();
        persona.setNombres(datos.nombres());
        persona.setApellidos(datos.apellidos());
        persona.setTipoDoc(datos.tipoDoc() != null ? datos.tipoDoc().name() : null);
        persona.setNroDoc(datos.nroDoc());
        persona.setFechaNacimiento(datos.fechaNacimiento());
        persona.setDomicilioPostal(datos.domicilioPostal());
        persona.setTelefono(datos.telefono());
        // la columna tiene default en la BD pero Hibernate manda el null si no se setea
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }

    private void avisarSiDifieren(Persona existente, DatosPersona datos) {
        List<String> distintos = new ArrayList<>();
        if (!mismoTexto(existente.getNombres(), datos.nombres())) {
            distintos.add("nombres");
        }
        if (!mismoTexto(existente.getApellidos(), datos.apellidos())) {
            distintos.add("apellidos");
        }
        if (datos.fechaNacimiento() != null
                && !Objects.equals(existente.getFechaNacimiento(), datos.fechaNacimiento())) {
            distintos.add("fechaNacimiento");
        }
        if (!distintos.isEmpty()) {
            log.warn("Persona {} {} (id {}) ya existía y los datos recibidos difieren en {}; se mantienen los guardados",
                    existente.getTipoDoc(), existente.getNroDoc(), existente.getId(), distintos);
        }
    }

    private static boolean mismoTexto(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.strip().equalsIgnoreCase(b.strip());
    }
}
