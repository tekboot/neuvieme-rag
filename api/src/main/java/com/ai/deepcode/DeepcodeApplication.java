package com.ai.deepcode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class DeepcodeApplication {

	public static void main(String[] args) {
		SpringApplication.run(DeepcodeApplication.class, args);
	}

}
