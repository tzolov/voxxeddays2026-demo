package com.example.demo;

import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;

/**
 * Masks account identifiers in the customer's message before it leaves the bank: the model, the
 * session log and the traces only ever see the placeholder. It is the outermost advisor, so it
 * runs once per call and before anything is stored.
 *
 * A production version would call the bank's PII detection service; the regular expressions are
 * enough to make the point on stage.
 */
public class PiiMaskingAdvisor implements BaseAdvisor {

	private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]){11,30}\\b");

	private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{9,}\\b");

	private final int order;

	public PiiMaskingAdvisor() {
		// Before the turn advisor (HIGHEST_PRECEDENCE + 100) and the session advisors behind it
		this(Ordered.HIGHEST_PRECEDENCE + 50);
	}

	public PiiMaskingAdvisor(int order) {
		this.order = order;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		String text = request.prompt().getUserMessage().getText();
		// A call that only carries an approver's decisions has no user message
		if (text == null || text.isBlank()) {
			return request;
		}
		String masked = mask(text);
		if (masked.equals(text)) {
			return request;
		}
		System.out.println("[PII] masked before the model: " + masked);
		return request.mutate().prompt(request.prompt().augmentUserMessage(masked)).build();
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
		return response;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	static String mask(String text) {
		String result = IBAN.matcher(text).replaceAll("[IBAN]");
		return LONG_NUMBER.matcher(result).replaceAll("[ACCOUNT-NUMBER]");
	}

}
