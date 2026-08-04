package com.example.demo;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.ValidityReport;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.validation.ReportEntry;
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 * Guardrail advisor that lifts the chat client's structured JSON output into an RDF
 * graph (via a JSON-LD {@code @context}) and checks it against a domain ontology,
 * mirroring the "Pydantic at the door, ontology at the ledger" pattern: use
 * {@code StructuredOutputValidationAdvisor} for JSON-Schema shape, use this advisor
 * for the semantic layer JSON Schema cannot express.
 * <p>
 * Two independent checks run against the merged (background facts + candidate output)
 * graph:
 * <ul>
 * <li><b>SHACL shapes</b> - structural / enumeration constraints (cardinality, allowed
 * values, datatypes), evaluated directly against the graph, no inference required.</li>
 * <li><b>OWL reasoning</b> - relational / identity constraints that only surface after
 * inference over the ontology's RDFS domain/range and {@code owl:disjointWith} axioms
 * combined with known background facts (e.g. "this refund recipient is, by inference, a
 * support representative - which is impossible because Customer and
 * SupportRepresentative are disjoint").</li>
 * </ul>
 * On failure, the violation messages are appended to the user message and the model is
 * re-invoked, up to {@code maxRepeatAttempts} times - the same retry-with-feedback loop
 * used by {@code StructuredOutputValidationAdvisor}.
 * <p>
 * Streaming responses are not supported.
 */
public final class OntologyValidationAdvisor implements CallAdvisor, StreamAdvisor {

	private static final Log logger = LogFactory.getLog(OntologyValidationAdvisor.class);

	private final int advisorOrder;

	private final int maxRepeatAttempts;

	private final boolean runOwlConsistencyCheck;

	private final JsonMapper jsonMapper;

	private final JsonNode jsonLdContextNode;

	private final String rootType;

	private final Map<String, String> arrayFieldTypes;

	private final Model backgroundModel;

	private final Shapes shapes;

	private OntologyValidationAdvisor(int advisorOrder, int maxRepeatAttempts, boolean runOwlConsistencyCheck,
			JsonMapper jsonMapper, Resource ontologyResource, Resource shapesResource,
			Resource jsonLdContextResource, String rootType, Map<String, String> arrayFieldTypes) {

		this.advisorOrder = advisorOrder;
		this.maxRepeatAttempts = maxRepeatAttempts;
		this.runOwlConsistencyCheck = runOwlConsistencyCheck;
		this.jsonMapper = jsonMapper;
		this.rootType = rootType;
		this.arrayFieldTypes = arrayFieldTypes;

		try (var in = jsonLdContextResource.getInputStream()) {
			JsonNode contextDocument = jsonMapper.readTree(in);
			JsonNode context = contextDocument.get("@context");
			Assert.notNull(context, "jsonLdContextResource must contain a top-level '@context' key");
			this.jsonLdContextNode = context;
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Failed to read JSON-LD context resource", e);
		}

		this.backgroundModel = ModelFactory.createDefaultModel();
		try (var in = ontologyResource.getInputStream()) {
			RDFDataMgr.read(this.backgroundModel, in, Lang.TURTLE);
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Failed to read ontology resource", e);
		}

		Model shapesModel = ModelFactory.createDefaultModel();
		try (var in = shapesResource.getInputStream()) {
			RDFDataMgr.read(shapesModel, in, Lang.TURTLE);
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Failed to read SHACL shapes resource", e);
		}
		this.shapes = Shapes.parse(shapesModel);
	}

	@Override
	public String getName() {
		return "Ontology Validation Advisor";
	}

	@Override
	public int getOrder() {
		return this.advisorOrder;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		Assert.notNull(callAdvisorChain, "callAdvisorChain must not be null");
		Assert.notNull(chatClientRequest, "chatClientRequest must not be null");

		ChatClientResponse chatClientResponse = null;
		boolean isValidationSuccess = false;
		int validatedAttempts = 0;
		OntologyCheckResult lastResult = null;
		var processedChatClientRequest = chatClientRequest;

		for (var currentAttemptNumber = 1 + this.maxRepeatAttempts; currentAttemptNumber > 0
				&& !isValidationSuccess; currentAttemptNumber--) {

			chatClientResponse = callAdvisorChain.copy(this).nextCall(processedChatClientRequest);

			ChatResponse chatResponse = chatClientResponse.chatResponse();

			if (chatResponse == null || !chatResponse.hasToolCalls()) {
				validatedAttempts++;
				lastResult = validateOutput(chatClientResponse);
				isValidationSuccess = lastResult.success();

				if (!isValidationSuccess) {
					if (logger.isWarnEnabled()) {
						logger.warn("Ontology validation failed: " + lastResult);
					}

					String validationErrorMessage = "Output ontology validation failed because of: "
							+ lastResult.errorMessage();

					Prompt augmentedPrompt = chatClientRequest.prompt()
						.augmentUserMessage(userMessage -> userMessage.mutate()
							.text(userMessage.getText() + System.lineSeparator() + validationErrorMessage)
							.build());

					processedChatClientRequest = chatClientRequest.mutate().prompt(augmentedPrompt).build();
				}
				else if (logger.isDebugEnabled()) {
					logger.debug("Ontology validation succeeded");
				}
			}
		}

		if (validatedAttempts > 0 && !isValidationSuccess) {
			throw new OntologyValidationException(
					"Ontology validation failed after " + validatedAttempts + " attempt(s): "
							+ Objects.requireNonNull(lastResult).errorMessage(),
					Objects.requireNonNull(chatClientResponse));
		}

		return Objects.requireNonNull(chatClientResponse);
	}

	private OntologyCheckResult validateOutput(ChatClientResponse chatClientResponse) {
		if (chatClientResponse.chatResponse() == null || chatClientResponse.chatResponse().getResult() == null
				|| chatClientResponse.chatResponse().getResult().getOutput().getText() == null) {
			return OntologyCheckResult.failed("Missing required json output for validation.");
		}

		String json = chatClientResponse.chatResponse().getResult().getOutput().getText();
		return validate(json);
	}

	OntologyCheckResult validate(String json) {
		if (json == null || json.isBlank()) {
			return OntologyCheckResult.failed("Empty JSON output for validation.");
		}

		ObjectNode root;
		try {
			JsonNode parsed = this.jsonMapper.readTree(json);
			if (!(parsed instanceof ObjectNode objectNode)) {
				return OntologyCheckResult.failed("Expected a JSON object at the root.");
			}
			root = objectNode.deepCopy();
		}
		catch (JacksonException e) {
			return OntologyCheckResult.failed("Invalid JSON: " + e.getMessage());
		}

		root.put("@type", this.rootType);
		for (Map.Entry<String, String> arrayFieldType : this.arrayFieldTypes.entrySet()) {
			JsonNode array = root.get(arrayFieldType.getKey());
			if (array instanceof ArrayNode arrayNode) {
				for (JsonNode element : arrayNode) {
					if (element instanceof ObjectNode elementObject) {
						elementObject.put("@type", arrayFieldType.getValue());
					}
				}
			}
		}
		// JSON-LD's number-to-RDF conversion renders non-integral JSON numbers using
		// exponential notation (e.g. 49.99 -> "4.999E1"), which is not a valid lexical
		// form for xsd:decimal. Stringifying first preserves the literal digits.
		stringifyNumbers(root);

		// jsonLdContextNode is a single shared instance reused across every request on
		// this (singleton-scoped) advisor; deep-copy it so no per-request tree ever
		// holds a live reference into it.
		root.set("@context", this.jsonLdContextNode.deepCopy());

		String jsonLd = this.jsonMapper.writeValueAsString(root);

		Model candidate = ModelFactory.createDefaultModel();
		try {
			RDFDataMgr.read(candidate, new StringReader(jsonLd), null, Lang.JSONLD11);
		}
		catch (Exception e) {
			// Deliberately broad: Titanium's JSON-LD 1.1 processor throws the checked
			// com.apicatalog.jsonld.JsonLdError, which Jena's RIOT layer lets propagate
			// undeclared (not wrapped in the usual unchecked RiotException) for some
			// error paths - e.g. keyword redefinition. It can't be named in a catch
			// clause here because no method in this try block declares it, so a narrower
			// catch would silently miss it.
			return OntologyCheckResult
				.failed("Could not interpret output as RDF via the configured JSON-LD context: " + e.getMessage());
		}

		Model combined = ModelFactory.createDefaultModel();
		combined.add(this.backgroundModel);
		combined.add(candidate);

		List<String> violations = new ArrayList<>();

		ValidationReport shaclReport = ShaclValidator.get().validate(this.shapes, combined.getGraph());
		if (!shaclReport.conforms()) {
			for (ReportEntry entry : shaclReport.getEntries()) {
				violations.add("[shape] " + entry.message());
			}
		}

		if (this.runOwlConsistencyCheck) {
			InfModel infModel = ModelFactory.createInfModel(ReasonerRegistry.getOWLReasoner(), combined);
			ValidityReport validity = infModel.validate();
			if (!validity.isValid()) {
				Iterator<ValidityReport.Report> it = validity.getReports();
				while (it.hasNext()) {
					ValidityReport.Report report = it.next();
					if (report.isError()) {
						violations.add("[ontology] " + report.getDescription());
					}
				}
			}
		}

		if (violations.isEmpty()) {
			return OntologyCheckResult.passed();
		}
		return OntologyCheckResult.failed(String.join("; ", violations));
	}

	private static void stringifyNumbers(JsonNode node) {
		if (node instanceof ObjectNode objectNode) {
			for (Map.Entry<String, JsonNode> field : List.copyOf(objectNode.properties())) {
				JsonNode value = field.getValue();
				if (value.isNumber()) {
					objectNode.put(field.getKey(), value.asString());
				}
				else {
					stringifyNumbers(value);
				}
			}
		}
		else if (node instanceof ArrayNode arrayNode) {
			for (int i = 0; i < arrayNode.size(); i++) {
				JsonNode value = arrayNode.get(i);
				if (value.isNumber()) {
					arrayNode.set(i, value.asString());
				}
				else {
					stringifyNumbers(value);
				}
			}
		}
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {
		return Flux.error(
				new UnsupportedOperationException("The Ontology Validation Advisor does not support streaming."));
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private int advisorOrder = BaseAdvisor.LOWEST_PRECEDENCE - 2000;

		private int maxRepeatAttempts = 3;

		private boolean runOwlConsistencyCheck = true;

		private JsonMapper jsonMapper = JacksonUtils.getDefaultJsonMapper();

		private Resource ontologyResource;

		private Resource shapesResource;

		private Resource jsonLdContextResource;

		private String rootType;

		private final Map<String, String> arrayFieldTypes = new HashMap<>();

		private Builder() {
		}

		public Builder advisorOrder(int advisorOrder) {
			this.advisorOrder = advisorOrder;
			return this;
		}

		public Builder maxRepeatAttempts(int maxRepeatAttempts) {
			this.maxRepeatAttempts = maxRepeatAttempts;
			return this;
		}

		/**
		 * Enables/disables the OWL reasoning consistency check (default: enabled). The
		 * SHACL shape check always runs.
		 */
		public Builder runOwlConsistencyCheck(boolean runOwlConsistencyCheck) {
			this.runOwlConsistencyCheck = runOwlConsistencyCheck;
			return this;
		}

		public Builder jsonMapper(JsonMapper jsonMapper) {
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Turtle resource containing the domain ontology's TBox (classes, properties,
		 * domain/range, disjointWith) and any ABox background facts to reason over.
		 */
		public Builder ontologyResource(Resource ontologyResource) {
			this.ontologyResource = ontologyResource;
			return this;
		}

		/** Turtle resource containing the SHACL shapes to validate the output against. */
		public Builder shapesResource(Resource shapesResource) {
			this.shapesResource = shapesResource;
			return this;
		}

		/**
		 * JSON-LD {@code @context} document used to lift the LLM's JSON output into an
		 * RDF graph.
		 */
		public Builder jsonLdContextResource(Resource jsonLdContextResource) {
			this.jsonLdContextResource = jsonLdContextResource;
			return this;
		}

		/** Compact IRI (e.g. {@code "sup:Order"}) injected as {@code @type} on the JSON root. */
		public Builder rootType(String rootType) {
			this.rootType = rootType;
			return this;
		}

		/**
		 * Registers a JSON array field whose object elements should each be tagged with
		 * the given compact IRI as {@code @type} (e.g. {@code "refunds" -> "sup:Refund"}).
		 */
		public Builder arrayFieldType(String fieldName, String typeCompactIri) {
			this.arrayFieldTypes.put(fieldName, typeCompactIri);
			return this;
		}

		public OntologyValidationAdvisor build() {
			Assert.notNull(this.ontologyResource, "ontologyResource must not be null");
			Assert.notNull(this.shapesResource, "shapesResource must not be null");
			Assert.notNull(this.jsonLdContextResource, "jsonLdContextResource must not be null");
			Assert.hasText(this.rootType, "rootType must not be empty");

			return new OntologyValidationAdvisor(this.advisorOrder, this.maxRepeatAttempts,
					this.runOwlConsistencyCheck, this.jsonMapper, this.ontologyResource, this.shapesResource,
					this.jsonLdContextResource, this.rootType, Map.copyOf(this.arrayFieldTypes));
		}

	}

	/**
	 * Thrown when the model still fails ontology validation after
	 * {@code maxRepeatAttempts} retries. Carries the last (invalid) response so callers
	 * can inspect what the model actually produced.
	 */
	public static final class OntologyValidationException extends RuntimeException {

		private final transient ChatClientResponse lastResponse;

		public OntologyValidationException(String message, ChatClientResponse lastResponse) {
			super(message);
			this.lastResponse = lastResponse;
		}

		public ChatClientResponse lastResponse() {
			return this.lastResponse;
		}

	}

	record OntologyCheckResult(boolean success, String errorMessage) {

		static OntologyCheckResult passed() {
			return new OntologyCheckResult(true, "");
		}

		static OntologyCheckResult failed(String errorMessage) {
			return new OntologyCheckResult(false, errorMessage);
		}

	}

}
