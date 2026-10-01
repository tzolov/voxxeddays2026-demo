package com.example.demo;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A thin wrapper over the bank's customer database.
 *
 * Mirrors the {@code DatabaseConn} class of the Pydantic AI bank support example (a
 * wrapper over an in-memory SQLite connection), backed here by Spring JDBC's
 * {@link JdbcClient} over the auto-configured, in-memory H2 {@code DataSource}. The
 * schema and seed data come from {@code schema.sql} / {@code data.sql}.
 */
@Component
public class DatabaseConn {

	private final JdbcClient jdbcClient;

	public DatabaseConn(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<String> customerName(int id) {
		return this.jdbcClient.sql("SELECT name FROM customers WHERE id = :id")
			.param("id", id)
			.query(String.class)
			.optional();
	}

	public double customerBalance(int id) {
		return this.jdbcClient.sql("SELECT balance FROM customers WHERE id = :id")
			.param("id", id)
			.query(Double.class)
			.optional()
			.orElseThrow(() -> new IllegalStateException("Customer not found"));
	}

}
