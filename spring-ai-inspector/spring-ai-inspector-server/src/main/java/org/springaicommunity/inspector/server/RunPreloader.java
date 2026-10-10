package org.springaicommunity.inspector.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Loads exported runs from {@code spring.ai.inspector.preload-dir} at startup, so recordings made
 * before a talk are available even after the inspector restarts.
 */
@Component
public class RunPreloader implements ApplicationRunner {

	private static final Log logger = LogFactory.getLog(RunPreloader.class);

	private final RunImporter importer;

	private final InspectorProperties properties;

	public RunPreloader(RunImporter importer, InspectorProperties properties) {
		this.importer = importer;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		if (this.properties.preloadDir() == null) {
			return;
		}
		Path dir = Path.of(this.properties.preloadDir());
		if (!Files.isDirectory(dir)) {
			logger.warn("spring.ai.inspector.preload-dir is not a directory: " + dir.toAbsolutePath());
			return;
		}
		JsonMapper json = JsonMapper.builder().build();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
				try {
					List<Map<String, Object>> events = json.readValue(file.toFile(), new TypeReference<>() {
					});
					this.importer.importRun(events, file.getFileName().toString());
					logger.info("Preloaded " + events.size() + " events from " + file.getFileName());
				}
				catch (Exception ex) {
					logger.warn("Could not preload " + file + ": " + ex.getMessage());
				}
			}
		}
	}

}
