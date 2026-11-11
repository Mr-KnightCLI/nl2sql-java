PRAGMA foreign_keys = ON;

CREATE TABLE shop_customers (customer_id INTEGER PRIMARY KEY, customer_name TEXT NOT NULL);
CREATE TABLE shop_orders (order_id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL REFERENCES shop_customers(customer_id), status TEXT NOT NULL, total_cents INTEGER NOT NULL);
INSERT INTO shop_customers VALUES (1, 'Asha'), (2, 'Ben'), (3, 'Chen');
INSERT INTO shop_orders VALUES (1, 1, 'PAID', 12000), (2, 1, 'CANCELLED', 5000), (3, 2, 'PAID', 8000), (4, 1, 'PAID', 3000);

CREATE TABLE logistics_carriers (carrier_id INTEGER PRIMARY KEY, carrier_name TEXT NOT NULL);
CREATE TABLE logistics_shipments (shipment_id INTEGER PRIMARY KEY, carrier_id INTEGER NOT NULL REFERENCES logistics_carriers(carrier_id), promised_at TEXT NOT NULL, delivered_at TEXT);
INSERT INTO logistics_carriers VALUES (1, 'North Freight'), (2, 'South Freight'), (3, 'West Freight');
INSERT INTO logistics_shipments VALUES
    (1, 1, '2026-06-01 12:00:00', '2026-06-01 13:00:00'),
    (2, 1, '2026-06-02 12:00:00', '2026-06-02 11:00:00'),
    (3, 2, '2026-06-03 12:00:00', NULL),
    (4, 2, '2026-06-04 12:00:00', '2026-06-04 12:00:00');

CREATE TABLE hr_departments (department_id INTEGER PRIMARY KEY, department_name TEXT NOT NULL);
CREATE TABLE hr_employees (employee_id INTEGER PRIMARY KEY, employee_name TEXT NOT NULL, department_id INTEGER NOT NULL REFERENCES hr_departments(department_id), salary_cents INTEGER NOT NULL, active INTEGER NOT NULL);
INSERT INTO hr_departments VALUES (1, 'Engineering'), (2, 'Sales'), (3, 'Support');
INSERT INTO hr_employees VALUES (1, 'Dev', 1, 9000000, 1), (2, 'Eli', 1, 7000000, 0), (3, 'Farah', 2, 6000000, 1), (4, 'Gus', 1, 8000000, 1);
