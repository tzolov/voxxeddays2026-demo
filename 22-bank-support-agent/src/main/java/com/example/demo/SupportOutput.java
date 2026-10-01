package com.example.demo;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The structured result every agent run must produce — the analog of Pydantic AI's
 * {@code output_type=SupportOutput} (a pydantic BaseModel).
 */
public record SupportOutput(

		@JsonPropertyDescription("Advice returned to the customer") String supportAdvice,

		@JsonPropertyDescription("Whether to block their card or not") boolean blockCard,

		@JsonPropertyDescription("Risk level of query, between 0 and 10") int risk) {
}
