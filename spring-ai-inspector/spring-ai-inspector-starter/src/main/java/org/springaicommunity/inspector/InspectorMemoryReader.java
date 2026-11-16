package org.springaicommunity.inspector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

/**
 * Snapshots what the memory stores of a ChatClient call hold, so the inspector can show
 * memory being written, not only read back into the prompt. Each snapshot reads the stores
 * (two per call, on the caller's thread): cheap in memory, a round-trip each with a
 * database-backed store; {@code spring.ai.inspector.memory-snapshots=false} turns them off.
 * Stores are found on the advisors of the chain:
 * <ul>
 * <li>{@code chat-memory}: any {@link ChatMemory} field (e.g. MessageChatMemoryAdvisor)</li>
 * <li>{@code session}: a Spring AI {@code SessionService} field (SessionMemoryAdvisor,
 * SessionTurnAdvisor), read reflectively: the whole log, with the events a compaction
 * moved out of the active view marked archived</li>
 * <li>{@code files}: the directories named by {@code spring.ai.inspector.memory-dirs} (e.g.
 * 19-auto-memory sets it to its {@code agent.memory.dir})</li>
 * </ul>
 */
class InspectorMemoryReader {

	static final String CONVERSATION_ID = ChatMemory.CONVERSATION_ID;

	private static final String SESSION_SERVICE = "org.springframework.ai.session.SessionService";

	private static final String EVENT_FILTER = "org.springframework.ai.session.EventFilter";

	private static final int MAX_FILE = 4000;

	private final List<Path> memoryDirs;

	InspectorMemoryReader(List<Path> memoryDirs) {
		this.memoryDirs = memoryDirs;
	}

	List<Map<String, Object>> snapshot(List<? extends Advisor> advisors, Map<String, Object> context) {
		List<Map<String, Object>> stores = new ArrayList<>();
		Object id = context.get(CONVERSATION_ID);
		String conversationId = id == null ? "default" : String.valueOf(id);
		Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

		for (Advisor advisor : advisors) {
			if (advisor instanceof InspectorAdvisor) {
				continue;
			}
			try {
				for (Object memory : InspectorReflection.fieldValues(advisor, ChatMemory.class)) {
					if (seen.add(memory)) {
						stores.add(chatMemory(advisor, (ChatMemory) memory, conversationId));
					}
				}
				for (Object service : InspectorReflection.fieldValuesByTypeName(advisor, SESSION_SERVICE)) {
					if (seen.add(service)) {
						Map<String, Object> store = session(advisor, service, conversationId);
						if (store != null) {
							stores.add(store);
						}
					}
				}
			}
			catch (Exception | LinkageError ex) {
				// never break the demo
			}
		}
		for (Path dir : this.memoryDirs) {
			Map<String, Object> store = files(dir);
			if (store != null) {
				stores.add(store);
			}
		}
		return stores;
	}

	private Map<String, Object> chatMemory(Advisor advisor, ChatMemory memory, String conversationId) {
		Map<String, Object> store = store("chat-memory", advisor.getName(), conversationId);
		store.put("memory", memory.getClass().getSimpleName());
		store.put("items", memory.get(conversationId).stream().map(InspectorAdvisor::message).toList());
		return store;
	}

	private Map<String, Object> session(Advisor advisor, Object service, String sessionId) throws Exception {
		Object events;
		Set<Object> activeIds = new java.util.HashSet<>();
		try {
			// The whole log: what a compaction covered stays in it, the active view moves on
			events = service.getClass().getMethod("getEvents", String.class).invoke(service, sessionId);
			Class<?> eventFilter = Class.forName(EVENT_FILTER, false, service.getClass().getClassLoader());
			Object active = eventFilter.getMethod("active").invoke(null);
			Object activeEvents = service.getClass()
				.getMethod("getEvents", String.class, eventFilter)
				.invoke(service, sessionId, active);
			if (activeEvents instanceof List<?> list) {
				for (Object event : list) {
					activeIds.add(call(event, "getId"));
				}
			}
		}
		catch (java.lang.reflect.InvocationTargetException ex) {
			return null; // a service that rejects the id; an unknown session returns an empty list
		}
		Map<String, Object> store = store("session", advisor.getName(), sessionId);
		store.put("memory", service.getClass().getSimpleName());
		List<Map<String, Object>> items = new ArrayList<>();
		if (events instanceof List<?> list) {
			for (Object event : list) {
				if (!Boolean.TRUE.equals(call(event, "hasMessage"))) {
					continue; // a compaction boundary or another runtime event, not conversation
				}
				Map<String, Object> item = InspectorAdvisor.message((Message) call(event, "getMessage"));
				// archived: compacted out of the active view, still in the log
				item.put("archived", !activeIds.contains(call(event, "getId")));
				item.put("synthetic", call(event, "isSynthetic"));
				item.put("ts", String.valueOf(call(event, "getTimestamp")));
				items.add(item);
			}
		}
		store.put("items", items);
		return store;
	}

	private Map<String, Object> files(Path dir) {
		if (!Files.isDirectory(dir)) {
			return null;
		}
		Map<String, Object> store = store("files", "memory directory", dir.toString());
		List<Map<String, Object>> items = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(dir, 3)) {
			for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("name", dir.relativize(file).toString());
				item.put("size", Files.size(file));
				item.put("modified", Files.getLastModifiedTime(file).toMillis());
				String content = Files.readString(file);
				item.put("content", content.length() <= MAX_FILE ? content : content.substring(0, MAX_FILE) + "…");
				items.add(item);
			}
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
		store.put("items", items);
		return store;
	}

	private static Map<String, Object> store(String kind, String source, String id) {
		Map<String, Object> store = new LinkedHashMap<>();
		store.put("kind", kind);
		store.put("source", source);
		store.put("id", id);
		return store;
	}

	private static Object call(Object target, String method) throws Exception {
		return target.getClass().getMethod(method).invoke(target);
	}

}
