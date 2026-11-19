package com.example.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mortgage service the bank already has, with three methods exposed as tools. The
 * customer id comes from the tool context, never from the model.
 *
 * Two tools read. The third one moves money, so it is marked
 * {@code requiresConfirmation}: when the model asks for it, the tool loop pauses and
 * nothing runs until a person decides.
 */
@Service
public class MortgageService {

	private final JdbcClient jdbcClient;

	private final LocalDate today;

	public MortgageService(JdbcClient jdbcClient, @Value("${demo.today}") String today) {
		this.jdbcClient = jdbcClient;
		this.today = LocalDate.parse(today);
	}

	@Tool(description = "Returns the customer's mortgage payments for one month: due date, amount in CHF, status "
			+ "(PAID, FAILED, RETRY_PLANNED, SCHEDULED) and, for a failed payment, the reason.")
	public String mortgagePaymentStatus(@ToolParam(description = "The month, as YYYY-MM") String month,
			ToolContext toolContext) {
		int customerId = CustomerIdentity.from(toolContext).customerId();
		List<Map<String, Object>> rows = this.jdbcClient
			.sql("SELECT due_date, amount, status, note FROM mortgage_payments "
					+ "WHERE customer_id = :id AND pay_month = :month ORDER BY due_date")
			.param("id", customerId)
			.param("month", month)
			.query()
			.listOfRows();
		if (rows.isEmpty()) {
			return "No mortgage payment is registered for " + month + ".";
		}
		return rows.stream().map(MortgageService::describe).collect(Collectors.joining("\n"));
	}

	@Tool(description = "Returns the customer's upcoming (scheduled or planned) mortgage payments.")
	public String upcomingMortgagePayments(ToolContext toolContext) {
		int customerId = CustomerIdentity.from(toolContext).customerId();
		List<Map<String, Object>> rows = this.jdbcClient
			.sql("SELECT due_date, amount, status, note FROM mortgage_payments "
					+ "WHERE customer_id = :id AND status IN ('SCHEDULED', 'RETRY_PLANNED') ORDER BY due_date")
			.param("id", customerId)
			.query()
			.listOfRows();
		return rows.isEmpty() ? "No upcoming mortgage payments."
				: rows.stream().map(MortgageService::describe).collect(Collectors.joining("\n"));
	}

	// Spring AI 2.1: this call moves money, so a person approves it first
	@Tool(description = "Collects the planned retry of a failed mortgage payment today instead of on its planned "
			+ "date. It debits the customer's account.", requiresConfirmation = true)
	@Transactional
	public String retryMortgagePaymentNow(@ToolParam(description = "The month of the payment, as YYYY-MM") String month,
			ToolContext toolContext) {

		int customerId = CustomerIdentity.from(toolContext).customerId();

		BigDecimal amount = this.jdbcClient
			.sql("SELECT amount FROM mortgage_payments "
					+ "WHERE customer_id = :id AND pay_month = :month AND status = 'RETRY_PLANNED'")
			.param("id", customerId)
			.param("month", month)
			.query(BigDecimal.class)
			.optional()
			.orElse(null);
		if (amount == null) {
			return "Nothing was collected: no retry is planned for " + month + ".";
		}
		BigDecimal balance = this.jdbcClient.sql("SELECT balance FROM customers WHERE id = :id")
			.param("id", customerId)
			.query(BigDecimal.class)
			.single();
		if (balance.compareTo(amount) < 0) {
			return "Nothing was collected: the account balance of CHF %s is below the CHF %s due.".formatted(balance,
					amount);
		}
		this.jdbcClient.sql("UPDATE customers SET balance = balance - :amount WHERE id = :id")
			.param("amount", amount)
			.param("id", customerId)
			.update();
		this.jdbcClient
			.sql("UPDATE mortgage_payments SET status = 'PAID', due_date = :today, "
					+ "note = 'Collected on the customer''s request, approved' "
					+ "WHERE customer_id = :id AND pay_month = :month AND status = 'RETRY_PLANNED'")
			.param("today", this.today)
			.param("id", customerId)
			.param("month", month)
			.update();
		return "Collected CHF %s for %s on %s. The new account balance is CHF %s.".formatted(amount, month, this.today,
				balance.subtract(amount));
	}

	private static String describe(Map<String, Object> row) {
		String note = row.get("NOTE") != null ? " (" + row.get("NOTE") + ")" : "";
		return "%s: CHF %s, %s%s".formatted(row.get("DUE_DATE"), row.get("AMOUNT"), row.get("STATUS"), note);
	}

}
