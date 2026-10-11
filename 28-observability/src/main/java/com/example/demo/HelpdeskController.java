package com.example.demo;

import com.example.demo.agent.Chaos;
import java.util.Map;

import com.example.demo.agent.HelpdeskAgent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The knobs for the talk. Ask a question by hand, pause or resume the traffic, and switch
 * the failure modes on and off while the dashboards are on screen.
 *
 * <pre>
 * curl -X POST 'localhost:8080/ask?q=Where+is+the+keynote'
 * curl -X POST localhost:8080/traffic/stop
 * curl -X POST 'localhost:8080/chaos?toolFailureRate=0.5'     # half the tool calls fail
 * curl -X POST 'localhost:8080/chaos?latencyMs=1500'          # a slow advisor
 * curl -X POST 'localhost:8080/chaos?badModelEveryN=3'        # every third call asks for a model that does not exist
 * curl -X POST 'localhost:8080/chaos?logContent=true'         # questions and answers in the log, with trace ids
 * curl -X POST localhost:8080/chaos/reset
 * </pre>
 */
@RestController
public class HelpdeskController {

	private final HelpdeskAgent agent;

	private final UserQuestionGenerator traffic;

	private final Chaos chaos;

	HelpdeskController(HelpdeskAgent agent, UserQuestionGenerator traffic, Chaos chaos) {
		this.agent = agent;
		this.traffic = traffic;
		this.chaos = chaos;
	}

	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	Map<String, String> badRequest(IllegalArgumentException ex) {
		return Map.of("error", ex.getMessage());
	}

	@PostMapping("/ask")
	HelpdeskAgent.Answer ask(@RequestParam String q,
			@RequestParam(defaultValue = HelpdeskAgent.ANTHROPIC) String provider) {
		return this.agent.ask(q, provider);
	}

	@GetMapping("/traffic")
	UserQuestionGenerator.Status traffic() {
		return this.traffic.status();
	}

	@PostMapping("/traffic/start")
	UserQuestionGenerator.Status startTraffic() {
		return this.traffic.start();
	}

	@PostMapping("/traffic/stop")
	UserQuestionGenerator.Status stopTraffic() {
		return this.traffic.stop();
	}

	@GetMapping("/chaos")
	Chaos.Settings chaos() {
		return this.chaos.settings();
	}

	@PostMapping("/chaos")
	Chaos.Settings chaos(@RequestParam(required = false) Double toolFailureRate,
			@RequestParam(required = false) Long latencyMs, @RequestParam(required = false) Integer badModelEveryN,
			@RequestParam(required = false) Boolean logContent) {
		return this.chaos.update(toolFailureRate, latencyMs, badModelEveryN, logContent);
	}

	@PostMapping("/chaos/reset")
	Chaos.Settings resetChaos() {
		return this.chaos.reset();
	}

}
