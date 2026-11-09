package io.nl2sql.core.config;

import java.util.ArrayList;
import java.util.List;

/** Safety limits applied to every generated query. Safe by default. */
public class GuardrailSettings {
    /** Maximum rows returned by any query. */
    private int maxRows = 1000;
    private int statementTimeoutSeconds = 30;
    private boolean allowSelectStar = true;
    /** "table.column" entries the LLM never sees and queries may never touch (e.g. personal data). */
    private List<String> blockedColumns = new ArrayList<>();
    private List<String> forbiddenFunctions = new ArrayList<>(List.of(
            "pg_sleep", "sleep", "benchmark", "pg_read_file", "pg_ls_dir", "pg_read_binary_file",
            "load_file", "xp_cmdshell", "dbms_pipe", "dbms_lock", "utl_http", "utl_file",
            "lo_import", "lo_export", "dblink", "openrowset", "opendatasource", "openquery"));
    /** How many times a failed query is sent back to the LLM for correction. */
    private int maxCorrectionAttempts = 2;

    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = Math.max(1, maxRows); }
    public int getStatementTimeoutSeconds() { return statementTimeoutSeconds; }
    public void setStatementTimeoutSeconds(int s) { this.statementTimeoutSeconds = Math.max(1, s); }
    public boolean isAllowSelectStar() { return allowSelectStar; }
    public void setAllowSelectStar(boolean allowSelectStar) { this.allowSelectStar = allowSelectStar; }
    public List<String> getBlockedColumns() { return blockedColumns; }
    public void setBlockedColumns(List<String> blockedColumns) { this.blockedColumns = DatabaseSettings.clean(blockedColumns); }
    public List<String> getForbiddenFunctions() { return forbiddenFunctions; }
    public void setForbiddenFunctions(List<String> f) { this.forbiddenFunctions = DatabaseSettings.clean(f); }
    public int getMaxCorrectionAttempts() { return maxCorrectionAttempts; }
    public void setMaxCorrectionAttempts(int n) { this.maxCorrectionAttempts = Math.max(0, Math.min(5, n)); }
}
