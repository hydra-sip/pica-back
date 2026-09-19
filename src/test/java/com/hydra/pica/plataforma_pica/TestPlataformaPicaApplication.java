package com.hydra.pica.plataforma_pica;

import org.springframework.boot.SpringApplication;

public class TestPlataformaPicaApplication {

	public static void main(String[] args) {
		SpringApplication.from(PlataformaPicaApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
