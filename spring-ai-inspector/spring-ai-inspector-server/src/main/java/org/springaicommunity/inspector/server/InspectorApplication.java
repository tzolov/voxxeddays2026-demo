package org.springaicommunity.inspector.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring AI Inspector: open http://localhost:9001 and run any demo. Demos that depend on the
 * {@code common} module detect the inspector automatically and report to it.
 */
@SpringBootApplication
@EnableConfigurationProperties(InspectorProperties.class)
public class InspectorApplication {

	public static void main(String[] args) {
		SpringApplication.run(InspectorApplication.class, args);
	}

}
