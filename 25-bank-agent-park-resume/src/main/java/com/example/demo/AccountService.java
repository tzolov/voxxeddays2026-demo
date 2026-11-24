package com.example.demo;

import java.math.BigDecimal;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The account service the bank already has. The {@link Tool} annotation on one method is all it
 * takes to make it callable by the agent; the rest of the class is unchanged.
 */
@Service
public class AccountService {

	private final JdbcClient jdbcClient;

	public AccountService(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public String customerName(int customerId) {
		return this.jdbcClient.sql("SELECT name FROM customers WHERE id = :id")
			.param("id", customerId)
			.query(String.class)
			.optional()
			.orElse("customer");
	}

	public BigDecimal balance(int customerId) {
		return this.jdbcClient.sql("SELECT balance FROM customers WHERE id = :id")
			.param("id", customerId)
			.query(BigDecimal.class)
			.single();
	}

	@Tool(description = "Returns the customer's current account balance in CHF.")
	public String accountBalance(ToolContext toolContext) {
		return "CHF " + balance(CustomerIdentity.from(toolContext).customerId());
	}

}
