package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Set;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NuevoUsuarioTest {

    private static final DatosPersona PERSONA = new DatosPersona(
            TipoDoc.DNI, "30123456", "Juan", "Pérez", null, null, null);

    @Test
    @DisplayName("autoRegistro: pendiente de verificación, mail sin verificar, sin roles explícitos")
    void autoRegistro() {
        NuevoUsuario nuevo = NuevoUsuario.autoRegistro("jperez", " juan@example.com ", "Pica2026", PERSONA);

        assertThat(nuevo.origen()).isEqualTo(NuevoUsuario.Origen.AUTO_REGISTRO);
        assertThat(nuevo.estadoInicial()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
        assertThat(nuevo.emailVerificado()).isFalse();
        assertThat(nuevo.email()).isEqualTo("juan@example.com");
        assertThat(nuevo.datosPersona()).isEqualTo(PERSONA);
        assertThat(nuevo.personaId()).isNull();
        assertThat(nuevo.rolIds()).isEmpty();
        assertThat(nuevo.llevaRolParticipante()).isTrue();
    }

    @Test
    @DisplayName("porAdmin: activo por defecto, mail verificado, persona por id y roles por id")
    void porAdmin() {
        NuevoUsuario nuevo = NuevoUsuario.porAdmin("jperez", "juan@example.com", "Temporal1", "desc",
                null, 7L, Set.of(2L, 3L));

        assertThat(nuevo.origen()).isEqualTo(NuevoUsuario.Origen.ADMIN);
        assertThat(nuevo.estadoInicial()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(nuevo.emailVerificado()).isTrue();
        assertThat(nuevo.personaId()).isEqualTo(7L);
        assertThat(nuevo.rolIds()).containsExactlyInAnyOrder(2L, 3L);
        assertThat(nuevo.llevaRolParticipante()).isFalse();

        assertThat(NuevoUsuario.porAdmin("j", "j@x.com", "Temporal1", null, EstadoUsuario.BLOQUEADO, 7L, null)
                .estadoInicial()).isEqualTo(EstadoUsuario.BLOQUEADO);
    }

    @Test
    @DisplayName("desdeGoogle: activo y verificado, sin contraseña ni username, persona sin documento")
    void desdeGoogle() {
        NuevoUsuario nuevo = NuevoUsuario.desdeGoogle("juan@gmail.com", "sub-123", "Juan", "Pérez");

        assertThat(nuevo.origen()).isEqualTo(NuevoUsuario.Origen.GOOGLE);
        assertThat(nuevo.username()).isNull();
        assertThat(nuevo.password()).isNull();
        assertThat(nuevo.googleSub()).isEqualTo("sub-123");
        assertThat(nuevo.estadoInicial()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(nuevo.emailVerificado()).isTrue();
        assertThat(nuevo.datosPersona().tieneDocumento()).isFalse();
        assertThat(nuevo.llevaRolParticipante()).isTrue();
    }

    @Test
    @DisplayName("Combinaciones inválidas: sin contraseña, sin googleSub, persona por las dos vías")
    void combinacionesInvalidas() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NuevoUsuario.autoRegistro("jperez", "juan@example.com", " ", PERSONA));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NuevoUsuario.desdeGoogle("juan@gmail.com", null, "Juan", "Pérez"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NuevoUsuario(NuevoUsuario.Origen.ADMIN, "j", "j@x.com", "Temporal1", null, null,
                        EstadoUsuario.ACTIVO, true, PERSONA, 7L, Set.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NuevoUsuario(NuevoUsuario.Origen.ADMIN, "j", "j@x.com", "Temporal1", null, null,
                        EstadoUsuario.ACTIVO, true, null, null, Set.of()));
    }

    @Test
    @DisplayName("Mezclas entre orígenes: el constructor las rechaza todas")
    void mezclasEntreOrigenes() {
        // Google con contraseña y con username propio
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.GOOGLE, null, "juan@gmail.com", "Pica2026", "sub-123", null,
                EstadoUsuario.ACTIVO, true, PERSONA, null, Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.GOOGLE, "jperez", "juan@gmail.com", null, "sub-123", null,
                EstadoUsuario.ACTIVO, true, PERSONA, null, Set.of()));

        // Google contra una persona por id
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.GOOGLE, null, "juan@gmail.com", null, "sub-123", null,
                EstadoUsuario.ACTIVO, true, null, 7L, Set.of()));

        // Alta por admin creando una persona nueva
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.ADMIN, "jperez", "juan@example.com", "Temporal1", null, null,
                EstadoUsuario.ACTIVO, true, PERSONA, null, Set.of()));

        // Alta por admin con googleSub
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.ADMIN, "jperez", "juan@example.com", "Temporal1", "sub-123", null,
                EstadoUsuario.ACTIVO, true, null, 7L, Set.of()));

        // Registro contra una persona por id
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.AUTO_REGISTRO, "jperez", "juan@example.com", "Pica2026", null, null,
                EstadoUsuario.PENDIENTE_VERIFICACION, false, null, 7L, Set.of()));
    }

    @Test
    @DisplayName("Estado y mail verificado tienen que ir con el origen")
    void estadoIncoherenteConElOrigen() {
        // Un registro no puede nacer activo ni con el mail ya verificado
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.AUTO_REGISTRO, "jperez", "juan@example.com", "Pica2026", null, null,
                EstadoUsuario.ACTIVO, false, PERSONA, null, Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.AUTO_REGISTRO, "jperez", "juan@example.com", "Pica2026", null, null,
                EstadoUsuario.PENDIENTE_VERIFICACION, true, PERSONA, null, Set.of()));

        // El admin no deja usuarios pendientes de verificación
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.ADMIN, "jperez", "juan@example.com", "Temporal1", null, null,
                EstadoUsuario.PENDIENTE_VERIFICACION, true, null, 7L, Set.of()));
    }

    @Test
    @DisplayName("Solo el admin elige roles: registro y Google no pueden traer rolIds")
    void soloElAdminEligeRoles() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.AUTO_REGISTRO, "jperez", "juan@example.com", "Pica2026", null, null,
                EstadoUsuario.PENDIENTE_VERIFICACION, false, PERSONA, null, Set.of(1L)));
        assertThatIllegalArgumentException().isThrownBy(() -> new NuevoUsuario(
                NuevoUsuario.Origen.GOOGLE, null, "juan@gmail.com", null, "sub-123", null,
                EstadoUsuario.ACTIVO, true, PERSONA, null, Set.of(1L)));

        // El alta por admin sí, y la lista vacía es válida (el contrato dice que puede ir vacía)
        assertThat(NuevoUsuario.porAdmin("jperez", "juan@example.com", "Temporal1", null, null, 7L, Set.of(1L))
                .rolIds()).containsExactly(1L);
        assertThat(NuevoUsuario.porAdmin("jperez", "juan@example.com", "Temporal1", null, null, 7L, Set.of())
                .rolIds()).isEmpty();
    }
}
