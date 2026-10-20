package com.hydra.pica.plataforma_pica.user.validation;

import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;

/**
 * Lo que necesita {@link DocumentoValido}: un request que trae tipo y número de documento.
 * Los records que lo implementan ya tienen los dos accesores con estos nombres.
 */
public interface ConDocumento {

    TipoDoc tipoDoc();

    String nroDoc();
}
