package edu.advising.commands;

// ============================================================================
// WEEK 5: COMMAND PATTERN - Command Record (Audit/History DTO)
// ============================================================================
// WHY THIS CLASS EXISTS:
//   When we load command history from the database for display (audit trails,
//   "My Transaction History", admin dashboards), we only need the metadata
//   about each command — not a fully reconstructed, executable command object.
//
//   Trying to reconstruct a full RegisterCommand or PaymentCommand from the DB
//   just to display a list would require re-fetching Student, Section, and other
//   objects unnecessarily. Instead, we load this lightweight DTO.
//
// GUI INTEGRATION NOTE:
//   A "Transaction History" screen would call:
//       CommandExecutor executor = session.getCommandExecutor();
//       List<CommandRecord> history = executor.getHistory(20);
//   Then bind the list to a JTable or ListView. Each row shows:
//       - What was done (commandType)
//       - When it happened (executedAt)
//       - Whether it succeeded (success)
//       - Whether it was reversed (undone)
// ============================================================================

import java.time.LocalDateTime;

public class CommandRecord {
    private final int id;
    private final String commandType;
    private final String commandData;   // Raw JSON payload for debugging
    private final LocalDateTime executedAt;
    private final LocalDateTime undoneAt;
    private final boolean undone;
    private final boolean success;
    private final String errorMessage;

    public CommandRecord(int id, String commandType, String commandData,
                  LocalDateTime executedAt, LocalDateTime undoneAt,
                  boolean undone, boolean success, String errorMessage) {
        this.id           = id;
        this.commandType  = commandType;
        this.commandData  = commandData;
        this.executedAt   = executedAt;
        this.undoneAt     = undoneAt;
        this.undone       = undone;
        this.success      = success;
        this.errorMessage = errorMessage;
    }

    // -------------------------------------------------------------------------
    // Getters — read-only, this is a value object
    // -------------------------------------------------------------------------

    public int getId()                  { return id; }
    public String getCommandType()      { return commandType; }
    public String getCommandData()      { return commandData; }
    public LocalDateTime getExecutedAt(){ return executedAt; }
    public LocalDateTime getUndoneAt()  { return undoneAt; }
    public boolean isUndone()           { return undone; }
    public boolean isSuccess()          { return success; }
    public String getErrorMessage()     { return errorMessage; }

    /** Human-readable status badge for display in a UI table cell. */
    public String getStatusLabel() {
        if (!success)  return "✗ Failed";
        if (undone)    return "↶ Reversed";
        return "✓ Completed";
    }

    @Override
    public String toString() {
        return String.format("[%s] %-20s %s  %s",
                executedAt, commandType, getStatusLabel(),
                errorMessage != null ? "| " + errorMessage : "");
    }
}