package org.springaicommunity.inspector;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

import reactor.core.publisher.Mono;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;

/**
 * Stamps the model providers' HTTP requests with the inspector's correlation headers (see
 * {@link InspectorCorrelation#headers}), so the recording proxy links each round-trip to
 * the ChatClient call and the model call it serves, whatever else is in flight. One
 * interceptor per HTTP client family Spring AI uses; each is registered only when its
 * client is on the classpath (see {@link InspectorAutoConfiguration}). Boot's builders
 * are shared by every client the app builds with them, so the headers go only on requests
 * to the inspector itself: a tool calling some other service never carries them. The proxy
 * strips them, so they never reach the provider.
 */
final class InspectorHttpHeaders {

	private InspectorHttpHeaders() {
	}

	/** Where the inspector listens: host and port of {@code spring.ai.inspector.url}. */
	record Target(String host, int port) {

		static Target of(URI inspector) {
			return new Target(inspector.getHost() == null ? "" : inspector.getHost().toLowerCase(Locale.ROOT),
					port(inspector.getPort(), inspector.getScheme()));
		}

		static int port(int port, String scheme) {
			return port > 0 ? port : "https".equalsIgnoreCase(scheme) ? 443 : 80;
		}

		boolean matches(String host, int port, String scheme) {
			return host != null && this.host.equals(host.toLowerCase(Locale.ROOT)) && this.port == port(port, scheme);
		}

		boolean matches(URI uri) {
			return matches(uri.getHost(), uri.getPort(), uri.getScheme());
		}

	}

	/** The OkHttp clients of the Anthropic and OpenAI SDKs. */
	static final class OkHttp implements okhttp3.Interceptor {

		private final Target target;

		OkHttp(URI inspector) {
			this.target = Target.of(inspector);
		}

		@Override
		public okhttp3.Response intercept(Chain chain) throws IOException {
			okhttp3.HttpUrl url = chain.request().url();
			Map<String, String> headers = this.target.matches(url.host(), url.port(), url.scheme())
					? InspectorCorrelation.headers(InspectorCorrelation.current()) : Map.of();
			if (headers.isEmpty()) {
				return chain.proceed(chain.request());
			}
			okhttp3.Request.Builder request = chain.request().newBuilder();
			headers.forEach(request::header);
			return chain.proceed(request.build());
		}

	}

	/** Spring's {@code RestClient} (Ollama, Mistral, DeepSeek, ...). */
	static final class Rest implements ClientHttpRequestInterceptor {

		private final Target target;

		Rest(URI inspector) {
			this.target = Target.of(inspector);
		}

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			if (this.target.matches(request.getURI())) {
				InspectorCorrelation.headers(InspectorCorrelation.current()).forEach((k, v) -> request.getHeaders().set(k, v));
			}
			return execution.execute(request, body);
		}

	}

	/** Spring's {@code WebClient} (streaming): the observation travels in the Reactor context. */
	static final class Web implements ExchangeFilterFunction {

		private final Target target;

		Web(URI inspector) {
			this.target = Target.of(inspector);
		}

		@Override
		public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
			if (!this.target.matches(request.url())) {
				return next.exchange(request);
			}
			return Mono.deferContextual(context -> {
				Map<String, String> headers = InspectorCorrelation.headers(InspectorCorrelation.fromContext(context));
				if (headers.isEmpty()) {
					return next.exchange(request);
				}
				return next.exchange(ClientRequest.from(request).headers(h -> headers.forEach(h::set)).build());
			});
		}

	}

}
