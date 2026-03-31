/*
* Copyright 2025 - 2025 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.springframework.ai.mcp.sample.server;

import java.time.LocalDateTime;

import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * @author Christian Tzolov
 */
@Service
public class Tools {

	private final RestClient restClient = RestClient.create();

	@McpTool(description = "Greeting response")
	public String hello(String myName) {
		return "Hello " + myName + "!";
	}

	@McpTool(description = "Get the temperature (in celsius) for a specific location")
	public String poeticWeatherForecast(McpSyncRequestContext context,
			@McpToolParam(description = "The location latitude") double latitude,
			@McpToolParam(description = "The location longitude") double longitude) {

		context.progress(0);

		WeatherResponse weather = restClient.get()
			.uri("https://api.open-meteo.com/v1/forecast?latitude={latitude}&longitude={longitude}&current=temperature_2m",
					latitude, longitude)
			.retrieve()
			.body(WeatherResponse.class);

		var weatherJson = ModelOptionsUtils.toJsonStringPrettyPrinter(weather);

		context.progress(50);

		String weatherPoem = "none";

		
		if (context.sampleEnabled()) {
			context.info("Start sampling");

			var sampleResponse = context.sample(spec -> spec.systemPrompt("You are a poet!")
				.message(
						"Please write a poem about this weather forecast (temperature is in Celsius). Use markdown format :\n "
								+ weatherJson));

			weatherPoem = ((TextContent) sampleResponse.content()).text();

			context.info("Finish Sampling");
		}

		context.progress(100);

		return "Poem about the weather: " + weatherPoem + "\n" + weatherJson;

	}

	public record WeatherResponse(Current current) {
		public record Current(LocalDateTime time, int interval, double temperature_2m) {
		}
	}

}