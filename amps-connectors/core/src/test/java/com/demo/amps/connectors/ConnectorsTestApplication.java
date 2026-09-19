package com.demo.amps.connectors;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The smallest application that can host the framework, for the {@code @SpringBootTest} that
 * exercises the whole flow.
 *
 * <p>Exactly what a real connector application is: a main class and nothing else.
 * {@link ConnectorsAutoConfiguration} contributes every bean through
 * {@code AutoConfiguration.imports}, and the connectors come from configuration -- so a test
 * that boots this class is testing the same wiring a deployment gets.
 */
@SpringBootApplication
public class ConnectorsTestApplication {

    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(ConnectorsTestApplication.class, args);
    }
}
