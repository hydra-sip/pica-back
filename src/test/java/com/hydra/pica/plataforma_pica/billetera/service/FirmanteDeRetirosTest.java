package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/**
 * El permiso de retiro que firma el back (HU-610, PICA-455) tiene que ser el mismo que valida PasarelaRetiros. Los
 * valores esperados salen de {@code PermisoDeReferenciaTest} en pica-contracts, calculados por el propio contrato con
 * Foundry: la segunda clave de prueba de anvil (pública), el contrato en 0xCA5A5 y Base Sepolia.
 */
class FirmanteDeRetirosTest {

    private static final String CLAVE_DE_PRUEBA = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d";
    private static final String CONTRATO = "0x00000000000000000000000000000000000ca5a5";
    private static final String REFERENCIA = Numeric.toHexString(Hash.sha3("retiro-vector".getBytes()));
    private static final String DESTINO = "0x00000000000000000000000000000000000000a1";
    private static final BigInteger MONTO = new BigInteger("100000000000000");
    private static final long VENCE = 1_800_000_000L;

    private final FirmanteDeRetiros firmante = new FirmanteDeRetiros(CLAVE_DE_PRUEBA, CONTRATO, 84532);

    @Test
    void laDireccionDelFirmanteEsLaDeLaClave() {
        assertThat(firmante.direccion()).isEqualTo("0x70997970c51812dc3a010c7d01b50e0d17dc79c8");
    }

    @Test
    void elHashDelPermisoEsElQueCalculaElContrato() {
        assertThat(REFERENCIA).isEqualTo("0x5d21dd9f5a6ddcd83ba7be317d447dfd70f0dd0ff1a4c7c050cecac6232588c5");

        assertThat(Numeric.toHexString(firmante.digest(REFERENCIA, DESTINO, MONTO, VENCE)))
                .isEqualTo("0x6b10001fa2c79ffc26d43ce1493718ea4c62e3563038a79a9d531c71b787bed9");
    }

    @Test
    void laFirmaEsLaQueElContratoAceptaEnSuTest() {
        assertThat(firmante.firmar(REFERENCIA, DESTINO, MONTO, VENCE)).isEqualTo("0x5a2ddedbb42999d93f58bf2668909a9cd6097533"
                + "c0ced9306a2d61a260b3e35e416a2b2dc00aeadb9be0d04a2f3ffc0e62b31b544f1587e70fac623da997ef171c");
    }

    @Test
    void laWalletSeComparaSinImportarLasMayusculas() {
        assertThat(firmante.firmar(REFERENCIA, "0x00000000000000000000000000000000000000A1", MONTO, VENCE))
                .isEqualTo(firmante.firmar(REFERENCIA, DESTINO, MONTO, VENCE));
    }

    @Test
    void cambiarCualquierDatoCambiaElHash() {
        byte[] original = firmante.digest(REFERENCIA, DESTINO, MONTO, VENCE);

        assertThat(firmante.digest(REFERENCIA, DESTINO, MONTO.add(BigInteger.ONE), VENCE)).isNotEqualTo(original);
        assertThat(firmante.digest(REFERENCIA, "0x00000000000000000000000000000000000000a2", MONTO, VENCE))
                .isNotEqualTo(original);
        assertThat(firmante.digest(REFERENCIA, DESTINO, MONTO, VENCE + 1)).isNotEqualTo(original);
        assertThat(new FirmanteDeRetiros(CLAVE_DE_PRUEBA, CONTRATO, 8453).digest(REFERENCIA, DESTINO, MONTO, VENCE))
                .isNotEqualTo(original);
    }

    @Test
    void sinClaveOSinContratoNoEstaHabilitado() {
        assertThat(firmante.habilitado()).isTrue();
        assertThat(new FirmanteDeRetiros("", CONTRATO, 84532).habilitado()).isFalse();
        assertThat(new FirmanteDeRetiros(CLAVE_DE_PRUEBA, "", 84532).habilitado()).isFalse();
    }
}
