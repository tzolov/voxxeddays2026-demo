package com.example.demo.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * Failure modes switched on live during the talk ({@code POST /chaos}), each visible on a
 * different panel: tool errors, advisor latency, model errors, and content in the logs.
 */
@Component
public class Chaos {

	public record Settings(double toolFailureRate, long latencyMs, int badModelEveryN, boolean logContent) {
	}

	/** 0..1: the share of tool calls that fail with an exception. */
	private volatile double toolFailureRate;

	/** Milliseconds {@link ChaosLatencyAdvisor} sleeps before each call. */
	private volatile long latencyMs;

	/** Every n-th call of a provider asks for a model that does not exist; 0 is off. */
	private volatile int badModelEveryN;

	private final Map<String, AtomicInteger> callsByProvider = new ConcurrentHashMap<>();

	/** Whether {@link ContentLogAdvisor} logs questions and answers. */
	private volatile boolean logContent;

	public Settings settings() {
		return new Settings(this.toolFailureRate, this.latencyMs, this.badModelEveryN, this.logContent);
	}

	public Settings update(Double toolFailureRate, Long latencyMs, Integer badModelEveryN, Boolean logContent) {
		if (toolFailureRate != null) {
			this.toolFailureRate = Math.max(0, Math.min(1, toolFailureRate));
		}
		if (latencyMs != null) {
			this.latencyMs = Math.max(0, latencyMs);
		}
		if (badModelEveryN != null) {
			this.badModelEveryN = Math.max(0, badModelEveryN);
		}
		if (logContent != null) {
			this.logContent = logContent;
		}
		return settings();
	}

	public Settings reset() {
		return update(0d, 0L, 0, false);
	}

	/**
	 * Throws for the share of calls set by {@code toolFailureRate}. A tool that throws is not
	 * an error on Spring AI's tool timer: the exception is handled and handed to the model as
	 * the tool's result, so the model can recover. The app counts it in its own
	 * {@code helpdesk.tool.failures} meter, in the exception processor (see
	 * {@code HelpdeskApplication}), so a real failure counts the same way.
	 */
	void maybeFailTool(String tool) {
		if (this.toolFailureRate > 0 && ThreadLocalRandom.current().nextDouble() < this.toolFailureRate) {
			throw new IllegalStateException("the service behind " + tool + " is unavailable (chaos)");
		}
	}

	long latencyMs() {
		return this.latencyMs;
	}

	boolean logContent() {
		return this.logContent;
	}

	/**
	 * The misspelled model name for this call, or null when this call is a normal one.
	 * Counted per provider, so an even {@code n} does not single out one of them when the
	 * traffic alternates.
	 */
	String badModelFor(String provider) {
		int n = this.badModelEveryN;
		if (n > 0 && this.callsByProvider.computeIfAbsent(provider, k -> new AtomicInteger()).incrementAndGet() % n == 0) {
			return HelpdeskAgent.OPENAI.equals(provider) ? "gpt-5-mini-does-not-exist" : "claude-haiku-does-not-exist";
		}
		return null;
	}

}
