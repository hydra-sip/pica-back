package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.user.domain.CodigoCanjeOAuth;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.CodigoCanjeOAuthRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OAuthCodeStoreTest {

    @Mock
    private CodigoCanjeOAuthRepository codigoCanjeOAuthRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    /** La hora fija de los tests: el vencimiento se prueba moviendo el reloj, no tocando la entidad. */
    private static final Instant AHORA = Instant.parse("2026-11-28T12:00:00Z");
    private static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);

    private OAuthCodeStore store;
    private final Map<String, CodigoCanjeOAuth> db = new HashMap<>();

    @BeforeEach
    void setUp() {
        db.clear();
        store = new OAuthCodeStore(codigoCanjeOAuthRepository, usuarioRepository, RELOJ);
    }

    /** El mismo almacén, consultado {@code segundos} después de {@link #AHORA}. */
    private OAuthCodeStore despuesDe(long segundos) {
        return new OAuthCodeStore(codigoCanjeOAuthRepository, usuarioRepository,
                Clock.offset(RELOJ, Duration.ofSeconds(segundos)));
    }

    private void configurarMocks() {
        when(usuarioRepository.getReferenceById(any())).thenAnswer(invocation -> {
            Usuario u = new Usuario();
            org.springframework.test.util.ReflectionTestUtils.setField(u, "id", (Long) invocation.getArgument(0));
            return u;
        });
        when(codigoCanjeOAuthRepository.save(any(CodigoCanjeOAuth.class))).thenAnswer(invocation -> {
            CodigoCanjeOAuth c = invocation.getArgument(0);
            db.put(c.getCodigoHash(), c);
            return c;
        });
        when(codigoCanjeOAuthRepository.findByCodigoHash(any())).thenAnswer(invocation -> {
            String hash = invocation.getArgument(0);
            return Optional.ofNullable(db.get(hash));
        });
    }

    @Test
    @DisplayName("Genera código y lo consume exitosamente")
    void generarYConsumirCodigo() {
        configurarMocks();
        String code = store.generarCodigo(42L);

        assertThat(code).isNotBlank();
        Long usuarioId = store.consumirCodigo(code);

        assertThat(usuarioId).isEqualTo(42L);
    }

    @Test
    @DisplayName("El código es de un solo uso (el segundo intento falla)")
    void codigoEsDeUnSoloUso() {
        configurarMocks();
        String code = store.generarCodigo(42L);

        assertThat(store.consumirCodigo(code)).isEqualTo(42L);
        assertThat(store.consumirCodigo(code)).isNull();
    }

    @Test
    @DisplayName("El código vence a los 60 segundos")
    void codigoExpiradoDevuelveNull() {
        configurarMocks();
        String vencido = store.generarCodigo(42L);
        String vigente = store.generarCodigo(43L);

        assertThat(despuesDe(61).consumirCodigo(vencido)).isNull();
        assertThat(despuesDe(59).consumirCodigo(vigente)).isEqualTo(43L);
    }

    @Test
    @DisplayName("Al generar un código se borran los usados o vencidos")
    void generarBorraLosUsadosOVencidos() {
        store.generarCodigo(42L);

        verify(codigoCanjeOAuthRepository).borrarUsadosOVencidos(AHORA);
    }

    @Test
    @DisplayName("Código inexistente o nulo devuelve null")
    void codigoInexistenteODevuelveNull() {
        assertThat(store.consumirCodigo(null)).isNull();
        assertThat(store.consumirCodigo("   ")).isNull();
        assertThat(store.consumirCodigo("codigo-fantasma")).isNull();
    }
}
