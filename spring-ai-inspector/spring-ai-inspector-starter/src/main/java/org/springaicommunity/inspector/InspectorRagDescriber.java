package org.springaicommunity.inspector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * Describes the RAG advisors of a chain (their configured stages) by reading their
 * fields reflectively, so {@code common} needs no dependency on spring-ai-rag or the
 * vector store advisors.
 */
final class InspectorRagDescriber {

	private static final String MODULAR = "org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor";

	private static final String QUESTION_ANSWER = "org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor";

	private InspectorRagDescriber() {
	}

	static List<Map<String, Object>> describe(List<? extends Advisor> advisors) {
		List<Map<String, Object>> rag = new ArrayList<>();
		for (Advisor advisor : advisors) {
			try {
				String type = advisor.getClass().getName();
				if (MODULAR.equals(type)) {
					rag.add(modular(advisor));
				}
				else if (QUESTION_ANSWER.equals(type)) {
					rag.add(questionAnswer(advisor));
				}
			}
			catch (Exception | LinkageError ex) {
				// never break the demo
			}
		}
		return rag;
	}

	private static Map<String, Object> modular(Advisor advisor) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("advisor", advisor.getName());
		d.put("kind", "modular");
		d.put("contextKey", "rag_document_context");
		d.put("queryTransformers", names(InspectorReflection.field(advisor, "queryTransformers")));
		d.put("queryExpander", InspectorReflection.componentName(InspectorReflection.field(advisor, "queryExpander")));
		Object retriever = InspectorReflection.field(advisor, "documentRetriever");
		d.put("documentRetriever", InspectorReflection.componentName(retriever));
		if (retriever != null) {
			d.put("topK", InspectorReflection.field(retriever, "topK"));
			d.put("similarityThreshold", InspectorReflection.field(retriever, "similarityThreshold"));
		}
		d.put("documentJoiner", InspectorReflection.componentName(InspectorReflection.field(advisor, "documentJoiner")));
		d.put("documentPostProcessors", names(InspectorReflection.field(advisor, "documentPostProcessors")));
		d.put("queryAugmenter", InspectorReflection.componentName(InspectorReflection.field(advisor, "queryAugmenter")));
		return d;
	}

	private static Map<String, Object> questionAnswer(Advisor advisor) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("advisor", advisor.getName());
		d.put("kind", "question-answer");
		d.put("contextKey", "qa_retrieved_documents");
		Object searchRequest = InspectorReflection.field(advisor, "searchRequest");
		if (searchRequest != null) {
			d.put("topK", InspectorReflection.field(searchRequest, "topK"));
			d.put("similarityThreshold", InspectorReflection.field(searchRequest, "similarityThreshold"));
		}
		Object store = InspectorReflection.field(advisor, "vectorStore");
		d.put("documentRetriever", store == null ? null : "VectorStore");
		return d;
	}

	private static List<String> names(Object components) {
		List<String> names = new ArrayList<>();
		if (components instanceof List<?> list) {
			list.forEach(c -> names.add(InspectorReflection.componentName(c)));
		}
		return names;
	}

}
