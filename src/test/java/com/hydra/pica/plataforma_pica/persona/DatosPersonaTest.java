package com.hydra.pica.plataforma_pica.persona;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatosPersonaTest {

    @Test
    @DisplayName("Con tipo y número tiene documento, y el número se guarda sin espacios")
    void conDocumento() {
        var datos = new DatosPersona(TipoDoc.DNI, " 30123456 ", "Juan", "Pérez", null, null, null);

        assertTrue(datos.tieneDocumento());
        assertEquals("30123456", datos.nroDoc());
    }

    @Test
    @DisplayName("sinDocumento() es el caso Google: nombres y apellidos nada más")
    void sinDocumento() {
        var datos = DatosPersona.sinDocumento("Juan", "Pérez");

        assertFalse(datos.tieneDocumento());
        assertEquals("Juan", datos.nombres());
    }

    @Test
    @DisplayName("Documento a medias (solo tipo o solo número) no se acepta")
    void documentoAMediasFalla() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatosPersona(TipoDoc.DNI, null, "Juan", "Pérez", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DatosPersona(null, "30123456", "Juan", "Pérez", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DatosPersona(TipoDoc.DNI, "   ", "Juan", "Pérez", null, null, null));
    }
}
