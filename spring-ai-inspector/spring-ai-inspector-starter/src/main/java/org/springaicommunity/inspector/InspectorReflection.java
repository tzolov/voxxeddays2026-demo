package org.springaicommunity.inspector;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only reflection helpers, used to look inside advisors (memory stores, RAG stages)
 * without compile-time dependencies on the libraries that define them. Every failure
 * is swallowed: the inspector must never break a demo.
 */
final class InspectorReflection {

	private InspectorReflection() {
	}

	/** Values of all instance fields of {@code target} whose type matches. */
	static List<Object> fieldValues(Object target, Class<?> type) {
		List<Object> values = new ArrayList<>();
		for (Field field : fields(target)) {
			if (type.isAssignableFrom(field.getType())) {
				Object value = read(field, target);
				if (value != null) {
					values.add(value);
				}
			}
		}
		return values;
	}

	/** Values of all instance fields of {@code target} whose declared type has this name. */
	static List<Object> fieldValuesByTypeName(Object target, String typeName) {
		List<Object> values = new ArrayList<>();
		for (Field field : fields(target)) {
			if (isOrImplements(field.getType(), typeName)) {
				Object value = read(field, target);
				if (value != null) {
					values.add(value);
				}
			}
		}
		return values;
	}

	static Object field(Object target, String name) {
		for (Field field : fields(target)) {
			if (field.getName().equals(name)) {
				return read(field, target);
			}
		}
		return null;
	}

	static boolean isOrImplements(Class<?> type, String name) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			if (c.getName().equals(name)) {
				return true;
			}
			for (Class<?> i : c.getInterfaces()) {
				if (isOrImplements(i, name)) {
					return true;
				}
			}
		}
		return false;
	}

	/** A readable component name: the simple class name, or "custom λ" for lambdas. */
	static String componentName(Object component) {
		if (component == null) {
			return null;
		}
		Class<?> type = component.getClass();
		if (type.isSynthetic() || type.getName().contains("$$Lambda") || type.isAnonymousClass()) {
			return "custom λ";
		}
		return type.getSimpleName();
	}

	private static List<Field> fields(Object target) {
		List<Field> fields = new ArrayList<>();
		for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
					fields.add(field);
				}
			}
		}
		return fields;
	}

	private static Object read(Field field, Object target) {
		try {
			field.setAccessible(true);
			return field.get(target);
		}
		catch (Exception | LinkageError ex) {
			return null;
		}
	}

}
