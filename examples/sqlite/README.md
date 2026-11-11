# SQLite sample questions

These six question/SQL pairs cover e-commerce, logistics and HR using fictional data.
[schema.sql](schema.sql) creates and seeds a small database;
[examples.json](examples.json) uses the engine's existing `question`, `sql`, `notes` format.
Table prefixes keep the three domains distinct in one SQLite database.

## Create the database

From the repository root, with the SQLite command-line tool installed, open a **new** database:

```sh
sqlite3 samples.db
```

Then enter these SQLite commands (also works on Windows):

```text
.read examples/sqlite/schema.sql
.quit
```

The script creates tables and inserts data once. Use a fresh database when rerunning it.
It does not drop or replace existing tables.

## Use the examples with the server

Build with `mvn verify`. From the repository root, create `samples.properties`:

```properties
nl2sql.db.type=sqlite
nl2sql.db.name=samples.db
nl2sql.examples-path=examples/sqlite/examples.json
nl2sql.llm.provider=openai
nl2sql.llm.base-url=https://api.openai.com/v1
nl2sql.llm.model=gpt-4.1-mini
```

Set `NL2SQL_LLM_API_KEY` in your environment, then start:

```sh
java -jar nl2sql-server/target/nl2sql-0.1.0-SNAPSHOT.jar --spring.config.additional-location=file:./samples.properties
```

Ask one of the questions below through `POST /v1/ask`, using the README's REST example.
For example, send `{"question":"Which customers have never placed an order?"}`.
The example SQL and results below are verified; a live model can still generate different SQL.
To use another OpenAI-compatible provider, change the base URL and model to match it.

## Questions and expected results

The SQL is in [examples.json](examples.json), in this order:

| Domain | Question | Columns | Expected rows |
|---|---|---|---|
| E-commerce | How much has each customer spent on paid orders, in cents? | `customer_name`, `paid_total_cents` | `(Asha, 15000)`, `(Ben, 8000)` |
| E-commerce | Which customers have never placed an order? | `customer_name` | `(Chen)` |
| Logistics | How many delivered shipments were late for each carrier, including carriers with none? | `carrier_name`, `late_count` | `(North Freight, 1)`, `(South Freight, 0)`, `(West Freight, 0)` |
| Logistics | Which shipments have not been delivered? | `shipment_id` | `(3)` |
| HR | How many active employees are in each department, including empty departments? | `department_name`, `active_count` | `(Engineering, 2)`, `(Sales, 1)`, `(Support, 0)` |
| HR | What is the total salary in cents of active employees in each department with active employees? | `department_name`, `salary_total_cents` | `(Engineering, 17000000)`, `(Sales, 6000000)` |

Money is stored in integer cents to avoid floating-point rounding. All timestamps use the same
`YYYY-MM-DD HH:MM:SS` text format. Late means delivered strictly after the promised time;
undelivered shipments and shipments delivered exactly on time do not count as late.
HR queries exclude inactive employees. The fixture includes an empty department and a carrier
without shipments so the zero-count examples exercise left joins.

## Verify without an LLM

```sh
mvn -pl nl2sql-core -Dtest=SampleExamplesTest test
```

`SampleExamplesTest` loads these exact files into a temporary SQLite database, checks example loading
and retrieval, then passes each SQL response through the engine's schema validation, guardrails and
execution path and compares column names and ordered rows with the expected results.
It uses a scripted LLM, so no API key or network call to an LLM is needed. This verifies the examples
and engine integration, rather than the quality of a live model's SQL generation.
