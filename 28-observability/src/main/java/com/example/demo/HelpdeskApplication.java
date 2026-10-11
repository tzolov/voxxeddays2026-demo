package com.example.demo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A conference helpdesk agent kept busy by its own traffic generator, so that Prometheus,
 * Grafana and Tempo (see {@code docker/}) have something to show: model calls, tokens,
 * tool runs, RAG searches and the advisor chain, as metrics and as traces. Failure modes
 * are switched on live through {@link HelpdeskController} to move the panels on cue.
 */
@SpringBootApplication
@EnableScheduling
public class HelpdeskApplication {

	public static void main(String[] args) {
		SpringApplication.run(HelpdeskApplication.class, args);
	}

	/**
	 * The practical FAQ the agent answers from, embedded as the store is created (the
	 * first embedding calls on the dashboard), so it is complete before the traffic
	 * starts. A store built by hand is given the registry, or its searches would not be
	 * observed (no vector store panels, no spans).
	 *
	 * Counts every tool failure, then lets Spring AI do what it does by default: hand the
	 * error to the model as the tool's result. The framework's tool timer records no
	 * error for a handled exception, so this is the one metric the app adds next to
	 * Spring AI's.
	 */
	@Bean
	ToolExecutionExceptionProcessor toolFailureCounter(MeterRegistry meterRegistry) {
		ToolExecutionExceptionProcessor defaults = new DefaultToolExecutionExceptionProcessor(false);
		Map<String, Counter> counters = new ConcurrentHashMap<>();
		return ex -> {
			counters
				.computeIfAbsent(ex.getToolDefinition().name(),
						tool -> Counter.builder("helpdesk.tool.failures")
							.description("Tool calls that failed with an exception")
							.tag("tool", tool)
							.register(meterRegistry))
				.increment();
			return defaults.process(ex);
		};
	}

	@Bean
	VectorStore vectorStore(EmbeddingModel embeddingModel, ObservationRegistry observationRegistry) {
		VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel)
			.observationRegistry(observationRegistry)
			.build();
		vectorStore.add(List.of(
				new Document(
						"Venue: the conference takes place at the Pakhuis de Zwijger, Piet Heinkade 179, Amsterdam. "
								+ "Registration and badge pick-up open at 8:00 at the main entrance."),
				new Document(
						"Wi-Fi: network VOXXED-2026, password springrocks. The network is shared by all attendees, "
								+ "so large downloads are discouraged during the keynotes."),
				new Document(
						"Food: lunch and coffee breaks are included in the ticket. Vegetarian and vegan options are "
								+ "available at every station; other dietary needs were collected at registration."),
				new Document(
						"Parking: there is no attendee parking at the venue. Use the P+R at Zeeburg or come by tram 26 "
								+ "(stop Kattenburgerstraat), five minutes on foot from the venue."),
				new Document(
						"Shuttle: a free shuttle runs between the partner hotels and the venue from 7:30 to 9:00 and "
								+ "from 17:30 to 19:00. Ask the shuttle tool for the next departure from your hotel."),
				new Document(
						"Recordings: all sessions in the Main Hall and Room 2 are recorded and published on the Voxxed "
								+ "Days YouTube channel within two weeks. Workshops are not recorded."),
				new Document(
						"Certificate of attendance: available after the event from the ticket portal under My tickets. "
								+ "It lists the sessions you checked in to with your badge."),
				new Document(
						"Code of conduct: the conference follows the Berlin Code of Conduct. Report any concern to a "
								+ "staff member wearing a red lanyard, or to conduct@voxxeddays.example."),
				new Document(
						"Speakers' dinner: Thursday 19:30 at Restaurant Kanarie Club, by invitation. Speakers find the "
								+ "invitation in their speaker portal."),
				new Document("Pets: animals are not allowed in the venue, with the exception of assistance dogs.")));
		return vectorStore;
	}

}
