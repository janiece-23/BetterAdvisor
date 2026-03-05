package edu.advising.commands;

// ============================================================================
// WEEK 5: COMMAND PATTERN - Command History (Invoker's Memory)
// ============================================================================
//
// PATTERN ROLE: This class is the "Invoker's memory" in the Command Pattern.
//   The Invoker (CommandExecutor) delegates every execute/undo/redo call here.
//   CommandHistory owns the undo and redo stacks and knows how to persist
//   commands to the `command_history` table via the ORM.
//
// HOW UNDO/REDO STACKS WORK:
//
//   START:    undoStack=[]        redoStack=[]
//
//   User registers for CIS-12:
//             undoStack=[REG]     redoStack=[]
//
//   User registers for MATH-10:
//             undoStack=[MATH,REG] redoStack=[]
//
//   User clicks Undo (MATH-10):
//             undoStack=[REG]     redoStack=[MATH]
//
//   User clicks Redo (MATH-10):
//             undoStack=[MATH,REG] redoStack=[]
//
//   User takes a NEW action (drops CIS-12) — redo chain breaks:
//             undoStack=[DROP,MATH,REG] redoStack=[]   (MATH redo is gone)
//
// PERSISTENCE:
//   Each command is inserted into `command_history` on execute.
//   On undo the row is updated (is_undone=TRUE, undone_at=now).
//   This gives faculty/admins a full audit trail even if the user
//   navigates away, and lets analysts see exactly what happened.
//
// GUI INTEGRATION:
//   After any execute/undo/redo call, check canUndo()/canRedo() to decide
//   whether the toolbar Undo and Redo buttons should be enabled:
//
//     executor.execute(new RegisterCommand(student, section));
//     undoButton.setEnabled(executor.canUndo());   // Swing example
//     redoButton.setEnabled(executor.canRedo());
//
// ============================================================================

import edu.advising.core.DatabaseManager;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class CommandHistory {

    // In-memory stacks scoped to the current user session.
    // ArrayDeque is used as a LIFO stack: push() adds to front, pop() removes from front.
    private final Deque<BaseCommand> undoStack;
    private final Deque<BaseCommand> redoStack;

    private final int userId;
    private final int maxStackSize;           // Keeps memory bounded
    private final DatabaseManager dbManager;

    /** Default: keep up to 20 actions in the live undo stack. */
    private static final int DEFAULT_MAX_SIZE = 20;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public CommandHistory(int userId) {
        this(userId, DEFAULT_MAX_SIZE);
    }

    public CommandHistory(int userId, int maxStackSize) {
        this.userId       = userId;
        this.maxStackSize = maxStackSize;
        this.undoStack    = new ArrayDeque<>();
        this.redoStack    = new ArrayDeque<>();
        this.dbManager    = DatabaseManager.getInstance();
    }

    // -------------------------------------------------------------------------
    // Core Execute / Undo / Redo
    // -------------------------------------------------------------------------

    /**
     * Execute a command and record it in history.
     *
     * Called by CommandExecutor — not directly by the UI.
     * After execution:
     *   - Successful commands go onto the undo stack and are persisted to DB.
     *   - Any pending redo stack is cleared (new action breaks redo chain).
     *   - Failed commands are persisted for audit purposes but NOT pushed
     *     onto the undo stack (nothing to undo if nothing happened).
     */
    public void executeCommand(BaseCommand command) {
        command.setUserId(userId);
        command.execute();

        // Always persist — even failures go into the audit log.
        persistNewCommand(command);

        if (command.wasSuccessful()) {
            // Enforce cap: evict the oldest entry before pushing new one.
            if (undoStack.size() >= maxStackSize) {
                undoStack.pollLast(); // remove oldest (back of deque)
            }
            undoStack.push(command);

            // A new action breaks the forward timeline — redo is no longer valid.
            redoStack.clear();
        }
    }

    /**
     * Undo the most recently executed undoable command.
     *
     * @return true if undo succeeded, false if nothing to undo or not undoable.
     */
    public boolean undo() {
        if (undoStack.isEmpty()) {
            System.out.println("↶ Nothing to undo.");
            return false;
        }

        BaseCommand command = undoStack.peek();

        if (!command.isUndoable()) {
            System.out.println("↶ Command cannot be undone: " + command.getDescription());
            return false;
        }

        undoStack.pop();
        command.undo();

        // Update the persisted record to mark it as reversed.
        markCommandUndone(command);

        // The undone command is pushed onto the redo stack so it can be re-applied.
        redoStack.push(command);
        return true;
    }

    /**
     * Redo the most recently undone command.
     *
     * @return true if redo succeeded, false if nothing to redo.
     */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            System.out.println("↷ Nothing to redo.");
            return false;
        }

        BaseCommand command = redoStack.pop();

        // Re-run the command's execute logic from scratch.
        // The command's own execute() validates pre-conditions (capacity, conflicts, etc.)
        // so it's safe to call again — it won't blindly re-do something invalid.
        command.execute();

        if (command.wasSuccessful()) {
            command.setUndone(false);
            command.setUndoneAt(null);
            markCommandRedone(command);
            undoStack.push(command);
        } else {
            // Redo failed (e.g. section is now full); discard rather than loop.
            System.out.println("↷ Redo failed: " + command.getDescription());
        }

        return command.wasSuccessful();
    }

    // -------------------------------------------------------------------------
    // State Queries — used by GUI to enable/disable Undo/Redo buttons
    // -------------------------------------------------------------------------

    /** @return true if there is at least one undoable command in history. */
    public boolean canUndo() {
        return !undoStack.isEmpty() && undoStack.peek().isUndoable();
    }

    /** @return true if there is at least one redoable command in history. */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** @return description of the next command that would be undone, or null. */
    public String peekUndoDescription() {
        BaseCommand top = undoStack.peek();
        return top != null ? top.getDescription() : null;
    }

    /** @return description of the next command that would be redone, or null. */
    public String peekRedoDescription() {
        BaseCommand top = redoStack.peek();
        return top != null ? top.getDescription() : null;
    }

    /**
     * Returns an ordered snapshot of the in-memory undo stack (most recent first).
     * Useful for showing a "Recent Actions" panel in the UI.
     */
    public List<BaseCommand> getUndoStack() {
        return new ArrayList<>(undoStack);
    }

    // -------------------------------------------------------------------------
    // Audit Trail — load historical records from DB
    // -------------------------------------------------------------------------

    /**
     * Loads a page of past commands from the `command_history` table.
     *
     * WHY THIS IS SEPARATE FROM getUndoStack():
     *   The in-memory stack only holds the current session's actions, bounded by
     *   maxStackSize. The database holds every action ever taken by this user.
     *   This method powers audit dashboards, "My Transaction History" screens,
     *   and admin review panels.
     *
     * GUI INTEGRATION:
     *   List<CommandRecord> history = commandHistory.getAuditHistory(50);
     *   // Bind history to a JTable model or a RecyclerView adapter.
     *
     * @param limit Max number of records to return (most recent first).
     */
    public List<CommandRecord> getAuditHistory(int limit) {
        String sql = "SELECT id, command_type, command_data, executed_at, " +
                "undone_at, is_undone, success, error_message " +
                "FROM command_history WHERE user_id = ? " +
                "ORDER BY executed_at DESC LIMIT ?";
        try {
            return dbManager.fetchList(sql, rs -> new CommandRecord(
                    rs.getInt("id"),
                    rs.getString("command_type"),
                    rs.getString("command_data"),
                    rs.getTimestamp("executed_at") != null
                            ? rs.getTimestamp("executed_at").toLocalDateTime() : null,
                    rs.getTimestamp("undone_at") != null
                            ? rs.getTimestamp("undone_at").toLocalDateTime() : null,
                    rs.getBoolean("is_undone"),
                    rs.getBoolean("success"),
                    rs.getString("error_message")
            ), userId, limit);
        } catch (SQLException e) {
            System.err.println("CommandHistory: failed to load audit history - " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // -------------------------------------------------------------------------
    // Private DB Helpers
    // -------------------------------------------------------------------------

    /**
     * Insert a new command record into command_history.
     * Uses ORM upsert via BaseCommand's @Table / @Column annotations.
     * Falls back to manual SQL if ORM fails (defensive coding).
     */
    private void persistNewCommand(BaseCommand command) {
        try {
            // prepareForStorage() calls serializeCommandData() on the concrete subclass,
            // storing the JSON payload into command.commandData before we persist.
            command.prepareForStorage();
            dbManager.upsert(command);
        } catch (SQLException | IllegalAccessException e) {
            System.err.println("CommandHistory: ORM upsert failed, trying fallback SQL — " + e.getMessage());
            persistCommandFallback(command);
        }
    }

    /**
     * Fallback insertion when ORM upsert cannot be used (e.g. subclass not directly annotated).
     */
    private void persistCommandFallback(BaseCommand command) {
        String sql = "INSERT INTO command_history " +
                "(user_id, command_type, command_data, executed_at, is_undone, success, error_message) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try {
            int generatedId = dbManager.executeInsert(sql,
                    command.getUserId(),
                    command.getCommandType(),
                    command.getCommandData(),
                    command.getExecutionTime() != null
                            ? Timestamp.valueOf(command.getExecutionTime()) : null,
                    false,
                    command.wasSuccessful(),
                    command.getErrorMessage());

            // Write the generated DB id back onto the command so markCommandUndone()
            // can find the correct row by id later.
            if (generatedId > 0) command.setId(generatedId);

        } catch (SQLException ex) {
            System.err.println("CommandHistory: fallback persist also failed — " + ex.getMessage());
        }
    }

    /**
     * Update an existing command_history row to mark the command as undone.
     */
    private void markCommandUndone(BaseCommand command) {
        if (command.getId() <= 0) return;  // Row was never persisted — skip.
        String sql = "UPDATE command_history " +
                "SET is_undone = TRUE, undone_at = CURRENT_TIMESTAMP " +
                "WHERE id = ?";
        try {
            dbManager.executeUpdate(sql, command.getId());
            command.setUndone(true);
            command.setUndoneAt(LocalDateTime.now());
        } catch (SQLException e) {
            System.err.println("CommandHistory: failed to mark command undone — " + e.getMessage());
        }
    }

    /**
     * Update a command_history row to reflect that a previously-undone command was redone.
     */
    private void markCommandRedone(BaseCommand command) {
        if (command.getId() <= 0) return;
        String sql = "UPDATE command_history " +
                "SET is_undone = FALSE, undone_at = NULL " +
                "WHERE id = ?";
        try {
            dbManager.executeUpdate(sql, command.getId());
        } catch (SQLException e) {
            System.err.println("CommandHistory: failed to mark command redone — " + e.getMessage());
        }
    }
}