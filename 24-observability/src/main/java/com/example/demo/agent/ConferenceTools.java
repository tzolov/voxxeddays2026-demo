package com.example.demo.agent;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The schedule, as tools. Each tool consults {@link Chaos} first: when a failure rate is
 * set, it throws. The model gets the error as the tool result and recovers on its own.
 */
@Component
public class ConferenceTools {

	record Session(String title, String speaker, String track, String room, String time) {
	}

	static final List<Session> SESSIONS = List.of(
			new Session("Opening keynote: the next ten years of the JVM", "Mala Gupta", "Keynote", "Main Hall",
					"09:00"),
			new Session("Spring AI in production: agents, tools and observability", "Christian Tzolov", "AI",
					"Main Hall", "10:15"),
			new Session("Kotlin coroutines, structured concurrency and you", "Urs Peter", "Languages", "Room 2",
					"10:15"),
			new Session("Testcontainers beyond the basics", "Oleg Selajev", "Testing", "Room 3", "11:30"),
			new Session("Virtual threads: one year in", "Jose Paumard", "JVM", "Main Hall", "11:30"),
			new Session("GraalVM native images for Spring Boot 4", "Sebastien Deleuze", "JVM", "Room 2", "13:30"),
			new Session("Building MCP servers with Java", "Dan Vega", "AI", "Room 3", "13:30"),
			new Session("Event sourcing without the pain", "Allard Buijze", "Architecture", "Room 2", "14:45"),
			new Session("Kotlin Multiplatform for backend engineers", "Pamela Hill", "Languages", "Room 3", "14:45"),
			new Session("Closing keynote: software and the people who write it", "Trisha Gee", "Keynote", "Main Hall",
					"16:30"));

	private final Chaos chaos;

	public ConferenceTools(Chaos chaos) {
		this.chaos = chaos;
	}

	@Tool(description = "Find conference sessions by topic, speaker name or track (AI, JVM, Languages, Testing, Architecture, Keynote)")
	public String findSessions(String query) {
		this.chaos.maybeFailTool("findSessions");
		String q = Objects.requireNonNullElse(query, "").toLowerCase();
		String found = SESSIONS.stream()
			.filter(s -> s.title().toLowerCase().contains(q) || s.speaker().toLowerCase().contains(q)
					|| s.track().toLowerCase().contains(q))
			.map(s -> "%s by %s, %s at %s (%s track)".formatted(s.title(), s.speaker(), s.room(), s.time(), s.track()))
			.collect(Collectors.joining("\n"));
		return found.isEmpty() ? "No session matches '" + query + "'." : found;
	}

	@Tool(description = "The room and start time of a session, given (part of) its title")
	public String roomFor(String sessionTitle) {
		this.chaos.maybeFailTool("roomFor");
		String q = Objects.requireNonNullElse(sessionTitle, "").toLowerCase();
		return SESSIONS.stream()
			.filter(s -> s.title().toLowerCase().contains(q))
			.findFirst()
			.map(s -> "%s is in %s at %s.".formatted(s.title(), s.room(), s.time()))
			.orElse("No session with a title like '" + sessionTitle + "'.");
	}

	@Tool(description = "Next shuttle departures from a partner hotel (Hilton, Movenpick, Inntel) to the venue")
	public String shuttleTimes(String hotel) {
		this.chaos.maybeFailTool("shuttleTimes");
		return switch (Objects.requireNonNullElse(hotel, "").toLowerCase()) {
			case "hilton" -> "From the Hilton: 7:40, 8:10, 8:40; back at 17:45 and 18:30.";
			case "movenpick", "mövenpick" -> "From the Movenpick: 7:30, 8:00, 8:30; back at 17:30 and 18:15.";
			case "inntel" -> "From the Inntel: 7:50, 8:20, 8:50; back at 18:00 and 18:45.";
			default -> "No shuttle serves '" + hotel + "'. The partner hotels are Hilton, Movenpick and Inntel.";
		};
	}

}
