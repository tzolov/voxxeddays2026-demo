package com.example.demo;

import java.util.List;

/**
 * Structured output shape the LLM is asked to produce for an order-support decision.
 */
public class OrderDomain {

	public record RefundDecision(String orderId, String status, List<RefundLine> refunds) {
	}

	public record RefundLine(String refundId, double amount, String recipientId) {
	}

}
