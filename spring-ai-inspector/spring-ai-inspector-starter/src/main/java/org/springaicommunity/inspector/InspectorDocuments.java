package org.springaicommunity.inspector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;

/** Structured form of {@link Document}s for inspector events: id, score, text, metadata. */
final class InspectorDocuments {

	private InspectorDocuments() {
	}

	static boolean isDocuments(Object value) {
		return value instanceof Collection<?> c && !c.isEmpty() && c.stream().allMatch(Document.class::isInstance);
	}

	static List<Map<String, Object>> of(Collection<?> documents, int maxText) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object o : documents) {
			if (o instanceof Document doc) {
				out.add(of(doc, maxText));
			}
		}
		return out;
	}

	static Map<String, Object> of(Document doc, int maxText) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("id", doc.getId());
		d.put("score", doc.getScore());
		String text = doc.getText();
		d.put("text", text == null || text.length() <= maxText ? text : text.substring(0, maxText) + "…");
		Map<String, Object> metadata = new LinkedHashMap<>();
		doc.getMetadata().forEach((k, v) -> {
			// Numbers and booleans stay typed (scores); anything else becomes bounded text.
			String value = String.valueOf(v);
			metadata.put(k, v instanceof Number || v instanceof Boolean ? v
					: value.length() <= 200 ? value : value.substring(0, 200) + "…");
		});
		d.put("metadata", metadata);
		return d;
	}

}
