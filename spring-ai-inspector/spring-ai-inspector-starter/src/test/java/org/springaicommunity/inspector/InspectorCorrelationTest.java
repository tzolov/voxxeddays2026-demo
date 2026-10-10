package org.springaicommunity.inspector;

import java.util.List;
import java.util.Map;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import static org.assertj.core.api.Assertions.assertThat;

class InspectorCorrelationTest {

	/** A registry with no handlers makes every observation a no-op; the app always has the tool handler. */
	private final ObservationRegistry registry = ObservationRegistry.create();

	InspectorCorrelationTest() {
		this.registry.observationConfig().observationHandler(context -> true);
	}

	private Observation observation(String name, Observation parent) {
		return Observation.createNotStarted(name, this.registry).parentObservation(parent).start();
	}

	@Test
	void findsTagsUpTheParentChainAndTheNearestOneWins() {
		Observation outerCall = observation("chat-client", null);
		InspectorCorrelation.tag(outerCall, InspectorCorrelation.CALL_ID, "c1");
		Observation tool = observation("tool", outerCall);
		InspectorCorrelation.tag(tool, InspectorCorrelation.TOOL_ID, "t1");
		Observation innerCall = observation("chat-client", tool);
		InspectorCorrelation.tag(innerCall, InspectorCorrelation.CALL_ID, "c2");
		Observation modelCall = observation("advisor", innerCall);
		InspectorCorrelation.tag(modelCall, InspectorCorrelation.MODEL_CALL_ID, "m1");

		assertThat(InspectorCorrelation.find(modelCall, InspectorCorrelation.CALL_ID)).isEqualTo("c2");
		assertThat(InspectorCorrelation.find(tool, InspectorCorrelation.CALL_ID)).isEqualTo("c1");
		assertThat(InspectorCorrelation.find(outerCall, InspectorCorrelation.TOOL_ID)).isNull();
		assertThat(InspectorCorrelation.headers(modelCall))
			.containsExactly(Map.entry("X-Inspector-Call", "c2"), Map.entry("X-Inspector-Model-Call", "m1"));
		assertThat(InspectorCorrelation.headers(null)).isEmpty();
	}

	@Test
	void aNewCallIsNestedInTheNearestCallAndNamesOnlyThatCallsTool() {
		Observation outerCall = observation("chat-client", null);
		InspectorCorrelation.tag(outerCall, InspectorCorrelation.CALL_ID, "c1");
		Observation tool = observation("tool", outerCall);
		InspectorCorrelation.tag(tool, InspectorCorrelation.TOOL_ID, "t1");
		Observation subAgentCall = observation("chat-client", tool);
		InspectorCorrelation.tag(subAgentCall, InspectorCorrelation.CALL_ID, "c2");

		// Started by the tool: nested in c1, made by t1.
		assertThat(InspectorCorrelation.parentOf(observation("advisor", tool)))
			.isEqualTo(new InspectorCorrelation.Parent("c1", "t1"));
		// Started by the sub-agent's model (not a tool): nested in c2, and t1 is an outer call's tool.
		assertThat(InspectorCorrelation.parentOf(observation("advisor", subAgentCall)))
			.isEqualTo(new InspectorCorrelation.Parent("c2", null));
		assertThat(InspectorCorrelation.parentOf(null)).isEqualTo(new InspectorCorrelation.Parent(null, null));
	}

	@Test
	void nothingIsTaggedOrFoundWithoutObservations() {
		InspectorCorrelation.tag(Observation.NOOP, InspectorCorrelation.CALL_ID, "c1"); // no exception
		InspectorCorrelation.tag(null, InspectorCorrelation.CALL_ID, "c1");

		assertThat(InspectorCorrelation.currentCallId()).isNull();
		assertThat(InspectorCorrelation.fromContext(Context.empty())).isNull();
	}

	private static final java.net.URI INSPECTOR = java.net.URI.create("http://localhost:9001");

	@Test
	void theRestClientInterceptorStampsTheHeadersOfTheCurrentCallOnRequestsToTheInspectorOnly() throws Exception {
		Observation call = observation("chat-client", null);
		InspectorCorrelation.tag(call, InspectorCorrelation.CALL_ID, "c1");
		ClientHttpRequestExecution execution = Mockito.mock(ClientHttpRequestExecution.class);
		Mockito.when(execution.execute(Mockito.any(), Mockito.any())).thenReturn(Mockito.mock(ClientHttpResponse.class));
		HttpRequest toInspector = request("http://LOCALHOST:9001/r/run/ollama/api/chat");
		HttpRequest toInspectorAgain = request("http://localhost:9001/r/run/ollama/api/chat");
		HttpRequest elsewhere = request("https://api.weather.example/today"); // a tool's own call

		try (Observation.Scope scope = call.openScope()) {
			new InspectorHttpHeaders.Rest(INSPECTOR).intercept(toInspector, new byte[0], execution);
			new InspectorHttpHeaders.Rest(INSPECTOR).intercept(elsewhere, new byte[0], execution);
		}
		new InspectorHttpHeaders.Rest(INSPECTOR).intercept(toInspectorAgain, new byte[0], execution); // outside any call

		assertThat(toInspector.getHeaders().getFirst("X-Inspector-Call")).isEqualTo("c1");
		assertThat(elsewhere.getHeaders().isEmpty()).isTrue();
		assertThat(toInspectorAgain.getHeaders().isEmpty()).isTrue();
	}

	private static HttpRequest request(String url) {
		HttpRequest request = Mockito.mock(HttpRequest.class);
		HttpHeaders headers = new HttpHeaders();
		Mockito.when(request.getHeaders()).thenReturn(headers);
		Mockito.when(request.getURI()).thenReturn(java.net.URI.create(url));
		return request;
	}

	@Test
	void theWebClientFilterReadsTheObservationFromTheReactorContext() {
		Observation call = observation("chat-client", null);
		InspectorCorrelation.tag(call, InspectorCorrelation.CALL_ID, "c1");
		InspectorCorrelation.tag(call, InspectorCorrelation.MODEL_CALL_ID, "m1");
		ExchangeFunction next = Mockito.mock(ExchangeFunction.class);
		Mockito.when(next.exchange(Mockito.any())).thenReturn(Mono.just(Mockito.mock(ClientResponse.class)));
		ClientRequest toInspector = ClientRequest.create(org.springframework.http.HttpMethod.POST, java.net.URI.create("http://localhost:9001/r/run/ollama/api/chat")).build();
		ClientRequest elsewhere = ClientRequest.create(org.springframework.http.HttpMethod.GET, java.net.URI.create("http://localhost:11434/api/tags")).build();

		for (ClientRequest request : List.of(toInspector, elsewhere)) {
			new InspectorHttpHeaders.Web(INSPECTOR).filter(request, next)
				.contextWrite(ctx -> ctx.put(io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor.KEY, call))
				.block();
		}

		org.mockito.ArgumentCaptor<ClientRequest> sent = org.mockito.ArgumentCaptor.forClass(ClientRequest.class);
		Mockito.verify(next, Mockito.times(2)).exchange(sent.capture());
		assertThat(sent.getAllValues().get(0).headers().getFirst("X-Inspector-Call")).isEqualTo("c1");
		assertThat(sent.getAllValues().get(0).headers().getFirst("X-Inspector-Model-Call")).isEqualTo("m1");
		assertThat(sent.getAllValues().get(1).headers().isEmpty()).isTrue();
	}

	@Test
	void theTargetMatchesHostAndEffectivePort() {
		InspectorHttpHeaders.Target target = InspectorHttpHeaders.Target.of(java.net.URI.create("https://inspector.example"));

		assertThat(target.matches(java.net.URI.create("https://inspector.example/r/x"))).isTrue();
		assertThat(target.matches(java.net.URI.create("https://inspector.example:443/r/x"))).isTrue();
		assertThat(target.matches(java.net.URI.create("http://inspector.example/r/x"))).isFalse();
		assertThat(target.matches(java.net.URI.create("https://other.example/r/x"))).isFalse();
	}

}
