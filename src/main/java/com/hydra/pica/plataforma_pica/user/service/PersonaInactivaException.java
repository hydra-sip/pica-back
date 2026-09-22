package com.hydra.pica.plataforma_pica.user.service;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;

/**
 * 409 PERSONA_INACTIVA: existe una persona con ese documento pero está inactiva o dada de baja.
 * No se puede vincular un usuario a ella ni "revivirla" desde el registro; eso lo hace
 * un administrador desde el ABM de personas.
 */
public class PersonaInactivaException extends ConflictoException {

    public PersonaInactivaException(TipoDoc tipoDoc, String nroDoc) {
        super(CodigoError.PERSONA_INACTIVA,
                "La persona con documento " + tipoDoc + " " + nroDoc + " está inactiva o eliminada");
    }

    public PersonaInactivaException(Persona persona) {
        super(CodigoError.PERSONA_INACTIVA, "La persona " + persona.getId() + " está inactiva o eliminada");
    }
}
