package edu.advising.commands;

// ============================================================================
// WEEK 5: COMMAND PATTERN - Command Executor (The Invoker)
// ============================================================================
//
// PATTERN ROLE: The INVOKER.
//   In the classic Command Pattern:
//     Client  → creates concrete Command (RegisterCommand, PaymentCommand, …)
//     Invoker → triggers execute() and manages history
//     Receiver → does the actual work (Section, DatabaseManager, …)
//
//   CommandExecutor IS the Invoker. It is the single entry point for all
//   user-initiated actions in the application. By routing every action
//   through this class, we guarantee:
//     1. Every action is recorded in command_history for auditing.
//     2. Undo and Redo work consistently across the whole app.
//     3. The UI/Service layer never touches business logic directly —
//        it only creates a command and hands it to the executor.
//
// ─────────────────────────────────────────────────────────────────────────────
// LIFECYCLE — One CommandExecutor per user session:
//
//   // When user logs in:
//   CommandExecutor executor = new CommandExecutor(loggedInUser.getId());
//   session.setCommandExecutor(executor);
//
//   // Store on the session so any screen can retrieve it:
//   session.getCommandExecutor().execute(new RegisterCommand(student, section));
//
// ─────────────────────────────────────────────────────────────────────────────
// GUI BUTTON WIRING (Swing example, works the same for JavaFX/Web):
//
//   // "Register" button
//   registerButton.addActionListener(e -> {
//       Section selected = sectionTable.getSelectedSection();
//       executor.execute(new RegisterCommand(student, selected));
//       undoButton.setEnabled(executor.canUndo());
//       redoButton.setEnabled(executor.canRedo());
//       refreshScheduleView();
//   });
//
//   // "Undo" button (always in the toolbar)
//   undoButton.addActionListener(e -> {
//       undoButton.setToolTipText("Undo: " + executor.peekUndoDescription());
//       executor.undo();
//       undoButton.setEnabled(executor.canUndo());
//       redoButton.setEnabled(executor.canRedo());
//       refreshScheduleView();
//   });
//
//   // "Redo" button
//   redoButton.addActionListener(e -> {
//       executor.redo();
//       undoButton.setEnabled(executor.canUndo());
//       redoButton.setEnabled(executor.canRedo());
//       refreshScheduleView();
//   });
//
// ─────────────────────────────────────────────────────────────────────────────
// OPEN/CLOSED PRINCIPLE:
//   Adding a new user action (e.g. Week 8's TranscriptRequestCommand) requires
//   ONLY creating a new BaseCommand subclass. CommandExecutor never changes.
//   This is the real power of the Command Pattern — the invoker is sealed.
//
// ============================================================================

import java.util.List;

public class CommandExecutor {

    private final CommandHistory history;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Create an executor for a specific user session.
     * @param userId The logged-in user's numeric primary key.
     */
    public CommandExecutor(int userId) {
        this.history = new CommandHistory(userId);
    }

    /**
     * Convenience constructor when you already have a CommandHistory instance
     * (e.g. for testing with a mock history).
     */
    public CommandExecutor(CommandHistory history) {
        this.history = history;
    }

    // -------------------------------------------------------------------------
    // Command Execution — the primary API for the UI layer
    // -------------------------------------------------------------------------

    /**
     * Execute a command and record it in history.
     *
     * This is the ONLY method the UI/Service layer should call to trigger
     * business logic. The caller creates the appropriate Command object,
     * passes it here, and then queries wasSuccessful() on the command
     * (or canUndo() on the executor) to update the UI state.
     *
     * Example:
     *   RegisterCommand cmd = new RegisterCommand(student, section);
     *   executor.execute(cmd);
     *   if (!cmd.wasSuccessful()) showErrorDialog(cmd.getErrorMessage());
     *
     * @param command Any concrete BaseCommand subclass.
     */
    public void execute(BaseCommand command) {
        history.executeCommand(command);
    }

    // -------------------------------------------------------------------------
    // Undo / Redo
    // -------------------------------------------------------------------------

    /**
     * Undo the last executed undoable command.
     * @return true if something was undone.
     */
    public boolean undo() {
        return history.undo();
    }

    /**
     * Redo the last undone command.
     * @return true if something was redone.
     */
    public boolean redo() {
        return history.redo();
    }

    // -------------------------------------------------------------------------
    // State Queries — for enabling/disabling toolbar buttons
    // -------------------------------------------------------------------------

    /**
     * @return true if the Undo button should be enabled.
     *
     * GUI Usage:
     *   undoButton.setEnabled(executor.canUndo());
     *   undoButton.setToolTipText("Undo: " + executor.peekUndoDescription());
     */
    public boolean canUndo() {
        return history.canUndo();
    }

    /**
     * @return true if the Redo button should be enabled.
     */
    public boolean canRedo() {
        return history.canRedo();
    }

    /**
     * Human-readable label for the next action that would be undone.
     * Useful for dynamic button tooltips: "Undo: Register for CIS-12 SP26-01"
     */
    public String peekUndoDescription() {
        return history.peekUndoDescription();
    }

    /**
     * Human-readable label for the next action that would be redone.
     */
    public String peekRedoDescription() {
        return history.peekRedoDescription();
    }

    // -------------------------------------------------------------------------
    // History Access
    // -------------------------------------------------------------------------

    /**
     * Returns the live in-session undo stack (most recent first).
     * Useful for a "Recent Actions" panel that lists what can currently be undone.
     *
     * GUI Usage:
     *   List<BaseCommand> recent = executor.getSessionHistory();
     *   recentActionsPanel.populate(recent);
     */
    public List<BaseCommand> getSessionHistory() {
        return history.getUndoStack();
    }

    /**
     * Load full audit history from the database for the current user.
     * Unlike getSessionHistory(), this survives session boundaries and
     * returns ALL historical records up to `limit`.
     *
     * GUI Usage (Transaction History screen):
     *   List<CommandRecord> records = executor.getAuditHistory(50);
     *   transactionTable.setModel(new CommandRecordTableModel(records));
     *
     * @param limit Maximum records to return (most recent first).
     */
    public List<CommandRecord> getAuditHistory(int limit) {
        return history.getAuditHistory(limit);
    }
}