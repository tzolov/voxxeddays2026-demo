package com.example.demo;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link OntologyValidationAdvisor}'s ontology-checking core directly against
 * canned JSON payloads, bypassing the LLM entirely, to confirm the SHACL + OWL
 * reasoning wiring actually catches the violations described in the ontology.
 */
class OntologyValidationAdvisorTest {

	private OntologyValidationAdvisor advisor;

	@BeforeEach
	void setUp() {
		this.advisor = OntologyValidationAdvisor.builder()
			.ontologyResource(new ClassPathResource("ontology/order-support.ttl"))
			.shapesResource(new ClassPathResource("ontology/order-support.shapes.ttl"))
			.jsonLdContextResource(new ClassPathResource("ontology/order-support-context.json"))
			.rootType("sup:Order")
			.arrayFieldType("refunds", "sup:Refund")
			.build();
	}

	@Test
	void validDecisionPasses() {
		String json = """
				{
				  "orderId": "ORD-2049",
				  "status": "refunded",
				  "refunds": [
				    { "refundId": "RF-1", "amount": 49.99, "recipientId": "cust-1001" }
				  ]
				}
				""";

		var result = this.advisor.validate(json);

		assertThat(result.success()).as(result.errorMessage()).isTrue();
	}

	@Test
	void invalidStatusEnumFailsShaclCheck() {
		String json = """
				{
				  "orderId": "ORD-2050",
				  "status": "probably shipped",
				  "refunds": []
				}
				""";

		var result = this.advisor.validate(json);

		assertThat(result.success()).isFalse();
		assertThat(result.errorMessage()).contains("[shape]").contains("paid, shipped, refunded");
	}

	@Test
	void duplicateRefundsFailShaclCardinalityCheck() {
		String json = """
				{
				  "orderId": "ORD-2051",
				  "status": "refunded",
				  "refunds": [
				    { "refundId": "RF-1", "amount": 20.00, "recipientId": "cust-1001" },
				    { "refundId": "RF-2", "amount": 20.00, "recipientId": "cust-1001" }
				  ]
				}
				""";

		var result = this.advisor.validate(json);

		assertThat(result.success()).isFalse();
		assertThat(result.errorMessage()).contains("[shape]").contains("more than one refund");
	}

	@Test
	void negativeRefundAmountFailsShaclDatatypeCheck() {
		String json = """
				{
				  "orderId": "ORD-2052",
				  "status": "refunded",
				  "refunds": [
				    { "refundId": "RF-1", "amount": -5.00, "recipientId": "cust-1001" }
				  ]
				}
				""";

		var result = this.advisor.validate(json);

		assertThat(result.success()).isFalse();
		assertThat(result.errorMessage()).contains("[shape]").contains("positive decimal");
	}

	@Test
	void refundToSupportRepFailsOwlDisjointnessCheck() {
		String json = """
				{
				  "orderId": "ORD-2053",
				  "status": "refunded",
				  "refunds": [
				    { "refundId": "RF-1", "amount": 15.00, "recipientId": "rep-77" }
				  ]
				}
				""";

		var result = this.advisor.validate(json);

		assertThat(result.success()).isFalse();
		assertThat(result.errorMessage()).contains("[ontology]");
	}

	@Test
	void adviseCallThrowsAfterExhaustingRetriesOnPersistentViolation() {
		var persistentlyInvalidAdvisor = OntologyValidationAdvisor.builder()
			.ontologyResource(new ClassPathResource("ontology/order-support.ttl"))
			.shapesResource(new ClassPathResource("ontology/order-support.shapes.ttl"))
			.jsonLdContextResource(new ClassPathResource("ontology/order-support-context.json"))
			.rootType("sup:Order")
			.arrayFieldType("refunds", "sup:Refund")
			.maxRepeatAttempts(2)
			.build();

		String invalidJson = """
				{
				  "orderId": "ORD-2053",
				  "status": "refunded",
				  "refunds": [
				    { "refundId": "RF-1", "amount": 15.00, "recipientId": "rep-77" }
				  ]
				}
				""";

		var stubChain = new StubCallAdvisorChain(invalidJson);

		assertThatThrownBy(() -> persistentlyInvalidAdvisor.adviseCall(new ChatClientRequest(new Prompt("test"), Map.of()),
				stubChain)).isInstanceOf(OntologyValidationAdvisor.OntologyValidationException.class)
			.hasMessageContaining("failed after 3 attempt(s)")
			.satisfies(ex -> assertThat(
					((OntologyValidationAdvisor.OntologyValidationException) ex).lastResponse().chatResponse()
						.getResult()
						.getOutput()
						.getText()).isEqualTo(invalidJson));

		// initial attempt + 2 retries = 3 calls to the underlying chain
		assertThat(stubChain.callCount).isEqualTo(3);
	}

	/** Always returns the same (fixed) model output, regardless of the retry feedback appended to the prompt. */
	private static final class StubCallAdvisorChain implements CallAdvisorChain {

		private final String modelOutput;

		int callCount = 0;

		StubCallAdvisorChain(String modelOutput) {
			this.modelOutput = modelOutput;
		}

		@Override
		public ChatClientResponse nextCall(ChatClientRequest request) {
			this.callCount++;
			ChatResponse chatResponse = ChatResponse.builder()
				.generations(List.of(new Generation(new AssistantMessage(this.modelOutput))))
				.build();
			return new ChatClientResponse(chatResponse, Map.of());
		}

		@Override
		public List<CallAdvisor> getCallAdvisors() {
			return List.of();
		}

		@Override
		public CallAdvisorChain copy(CallAdvisor advisor) {
			return this;
		}

	}

}
