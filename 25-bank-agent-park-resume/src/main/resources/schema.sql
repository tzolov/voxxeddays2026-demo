CREATE TABLE IF NOT EXISTS customers(
    id INT PRIMARY KEY,
    name VARCHAR(100),
    balance DECIMAL(12, 2)
);

CREATE TABLE IF NOT EXISTS mortgage_payments(
    customer_id INT,
    pay_month VARCHAR(7),
    due_date DATE,
    amount DECIMAL(12, 2),
    status VARCHAR(20),
    note VARCHAR(200)
);
