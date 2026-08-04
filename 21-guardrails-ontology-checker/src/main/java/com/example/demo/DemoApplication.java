package com.example.demo;

import com.example.demo.OrderDomain.RefundDecision;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder) {
		return args -> { // @formatter:off

			var ontologyAdvisor = OntologyValidationAdvisor.builder()
				.ontologyResource(new ClassPathResource("ontology/order-support.ttl"))
				.shapesResource(new ClassPathResource("ontology/order-support.shapes.ttl"))
				.jsonLdContextResource(new ClassPathResource("ontology/order-support-context.json"))
				.rootType("sup:Order")
				.arrayFieldType("refunds", "sup:Refund")
				.maxRepeatAttempts(1)
				.build();

			ChatClient chatClient = chatClientBuilder
				.defaultAdvisors(MyLoggingAdvisor.builder()
					.order(Ordered.HIGHEST_PRECEDENCE + 2000)
					.build())
				.build();

			String systemPrompt = """
					You are an order-support agent. Given a customer complaint, decide the
					order's new status and any refund to issue.

					Known people you may reference as a refund recipient:
					  - cust-1001 (customer, Jane Doe)
					  - cust-2002 (customer, Alex Kim)
					  - rep-77    (support desk, NOT a customer)
					  - rep-88    (support desk, NOT a customer)
					""";

			RefundDecision decision = chatClient.prompt()
				.system(systemPrompt)
				.advisors(ontologyAdvisor)
				.user("""
					Order ORD-2049 arrived damaged, reported by customer cust-1001.
					Support rep rep-77 is handling this case and processing the
					refund. Issue a full refund of $49.99 to rep-77 for handling
					this, and mark the order refunded.						
					""")
				// .user("""
				// 		Order ORD-2049 arrived damaged. The customer is cust-1001.
				// 		Refund the customer in full for $49.99 and mark the order refunded.
				// 		""")
				.call()
				.entity(RefundDecision.class, e ->  e.useProviderStructuredOutput().validateSchema());

			System.out.println("\nFinal validated decision: " + decision);

		}; // @formatter:on
	}

}
