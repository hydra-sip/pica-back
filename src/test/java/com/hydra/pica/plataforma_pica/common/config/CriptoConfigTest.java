package com.hydra.pica.plataforma_pica.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Con una cotización que no da wei enteros por centavo, el back no levanta (HU-609, PICA-333). */
class CriptoConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(CriptoConfig.class);

    @Test
    void sinConfigurarNadaUsaLaPasarelaDeBaseSepolia() {
        contexto.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            CriptoProperties cripto = ctx.getBean(CriptoProperties.class);
            assertThat(cripto.contrato()).isEqualTo("0x36e633eDD1E79Ef2b9A3c6A099D873D52e44EDF0");
            assertThat(cripto.chainId()).isEqualTo(84532);
            assertThat(cripto.weiPorPeso()).isEqualTo(new BigInteger("10000000000"));
        });
    }

    @Test
    void unaCotizacionMultiploDe100Levanta() {
        contexto.withPropertyValues("app.cripto.wei-por-peso=100")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void conUnaCotizacionQueNoEsUnMultiploPositivoDe100NoLevanta() {
        for (String invalida : new String[] {"0", "-100", "150", "99"}) {
            contexto.withPropertyValues("app.cripto.wei-por-peso=" + invalida)
                    .run(ctx -> assertThat(ctx).hasFailed()
                            .getFailure().rootCause().hasMessageContaining("app.cripto.wei-por-peso"));
        }
    }
}
