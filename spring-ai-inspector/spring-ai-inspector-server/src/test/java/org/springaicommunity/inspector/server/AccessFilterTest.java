package org.springaicommunity.inspector.server;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = { "spring.ai.inspector.token=s3cret", "spring.ai.inspector.max-request-bytes=100",
		"spring.ai.inspector.max-import-bytes=1000" })
@AutoConfigureMockMvc
class AccessFilterTest {

	@Autowired
	MockMvc mvc;

	@Test
	void aForeignHostIsRefused() throws Exception {
		// A DNS-rebinding page sends its own hostname, resolved to 127.0.0.1.
		this.mvc.perform(get("/api/ping").header("Host", "evil.example")).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/ping").header("Host", "localhost:9001")).andExpect(status().isOk());
		this.mvc.perform(get("/api/ping").header("Host", "127.0.0.1")).andExpect(status().isOk());
	}

	@Test
	void theEventApiNeedsTheTokenButThePingDoesNot() throws Exception {
		this.mvc.perform(get("/api/ping")).andExpect(status().isOk());
		this.mvc.perform(post("/api/events").contentType("application/json").content("{\"type\":\"x\"}"))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(delete("/api/events")).andExpect(status().isUnauthorized());
		this.mvc.perform(post("/api/events").header("X-Inspector-Token", "s3cret").contentType("application/json")
			.content("{\"type\":\"x\"}")).andExpect(status().isOk());
		this.mvc.perform(delete("/api/events").param("token", "s3cret")).andExpect(status().isOk());
		this.mvc.perform(delete("/api/events").param("token", "wrong")).andExpect(status().isUnauthorized());
	}

	@Test
	void withATokenTheProxyServesOnlyRegisteredRuns() throws Exception {
		// The proxy route carries no token; a run that never registered has no upstream, even a default one.
		this.mvc.perform(post("/r/unknown/anthropic/v1/messages").content("{}")).andExpect(status().isNotFound());
	}

	@Test
	void oversizedEventPostsAreRefused() throws Exception {
		this.mvc.perform(post("/api/events").header("X-Inspector-Token", "s3cret").contentType("application/json")
			.content("{\"type\":\"x\",\"pad\":\"" + "y".repeat(200) + "\"}")).andExpect(status().is(413));
	}

	@Test
	void importsHaveTheirOwnLargerLimit() throws Exception {
		// A recording is many events: bigger than one event post, but still capped.
		String recording = "[{\"type\":\"run-start\",\"runId\":\"r\",\"pad\":\"" + "y".repeat(500) + "\"}]";
		this.mvc.perform(post("/api/import").header("X-Inspector-Token", "s3cret").contentType("application/json")
			.content(recording)).andExpect(status().isOk());
		this.mvc.perform(post("/api/import").header("X-Inspector-Token", "s3cret").contentType("application/json")
			.content(recording.replace("y".repeat(500), "y".repeat(1500)))).andExpect(status().is(413));
	}

}
