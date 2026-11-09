package io.nl2sql.core.config;

/** All settings for one nl2sql engine. Bound from {@code nl2sql.*} properties or {@code NL2SQL_*} variables. */
public class Nl2SqlSettings {
    private DatabaseSettings db = new DatabaseSettings();
    private LlmSettings llm = new LlmSettings();
    private GuardrailSettings guardrails = new GuardrailSettings();
    /** Semantic layer JSON (table/column descriptions, synonyms, glossary). Same format as the Python version. */
    private String semanticsPath;
    /** Verified question/SQL examples JSON. Same format as the Python version. */
    private String examplesPath;
    /** Extra rules appended to the system prompt. */
    private String extraInstructions;

    public DatabaseSettings getDb() { return db; }
    public void setDb(DatabaseSettings db) { this.db = db; }
    public LlmSettings getLlm() { return llm; }
    public void setLlm(LlmSettings llm) { this.llm = llm; }
    public GuardrailSettings getGuardrails() { return guardrails; }
    public void setGuardrails(GuardrailSettings guardrails) { this.guardrails = guardrails; }
    public String getSemanticsPath() { return semanticsPath; }
    public void setSemanticsPath(String semanticsPath) { this.semanticsPath = semanticsPath; }
    public String getExamplesPath() { return examplesPath; }
    public void setExamplesPath(String examplesPath) { this.examplesPath = examplesPath; }
    public String getExtraInstructions() { return extraInstructions; }
    public void setExtraInstructions(String extraInstructions) { this.extraInstructions = extraInstructions; }
}
