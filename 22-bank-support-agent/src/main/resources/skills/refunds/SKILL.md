---
name: refunds
description: Refund policies and how to check the status of a customer's refund. Load this skill when the customer asks about refunds, disputed charges, or duplicate charges.
---

# Refund Handling

## Policy

- Refunds for duplicate or erroneous charges are approved automatically when the
  duplicate transaction is confirmed in the customer's transaction history.
- Approved refunds are credited back to the customer's account within 3-5 business days.
- Refunds above $500 require a manual review by the fraud team and may take up to
  10 business days.
- A refund request never requires blocking the customer's card. Treat refund
  questions as low risk (0-2) unless the customer reports unauthorized activity.

## Checking refund status

Use the `refundStatus` tool to look up the current status of the customer's
pending refunds. Always quote the refund amount, the approval date, and the
expected settlement window back to the customer.
