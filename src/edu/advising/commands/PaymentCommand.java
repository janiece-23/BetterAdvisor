package edu.advising.commands;

// ============================================================================
// WEEK 5: COMMAND PATTERN - PaymentCommand (Concrete Command)
// ============================================================================
//
// FEATURE:  Financial Information → Make a Payment
//
// WHY COMMAND PATTERN HERE:
//   A payment is a transactional operation that:
//     1. Must be logged for auditing (every cent that moves needs a record).
//     2. May need to be reversed (refunds — the undo operation).
//     3. Should trigger Observer notifications (PaymentReceived → email receipt).
//     4. Could be part of a MacroCommand (e.g., enroll + pay tuition at once).
//
//   Without Command Pattern, all of this logic would be tangled into a button
//   handler or a service method. Command Pattern separates:
//     WHO triggers the action (GUI button / REST endpoint)
//     WHAT the action does (this class)
//     HOW it is undone (the undo() method)
//
// UNDO SEMANTICS:
//   Undoing a payment marks the row as REFUNDED. In a real system this would
//   also call a payment gateway refund API. The undo is only permitted within
//   a configured window (e.g., same day / same session). After that window,
//   a separate CancelPaymentCommand should be used (handled by staff, Week 14).
//
// GUI INTEGRATION:
//   // On "Submit Payment" button click:
//   PaymentCommand cmd = new PaymentCommand(student, amount, paymentType, paymentMethod);
//   executor.execute(cmd);
//
//   if (cmd.wasSuccessful()) {
//       showReceipt(cmd.getPaymentReferenceNumber());
//   } else {
//       showError("Payment failed. Please try again.");
//   }
//
// ============================================================================

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.advising.core.DatabaseManager;
import edu.advising.notifications.NotificationManager;
import edu.advising.notifications.ObservableStudent;
import edu.advising.users.Student;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class PaymentCommand extends BaseCommand {

    // ── State stored on the command for undo purposes ──────────────────────

    private ObservableStudent student;
    private BigDecimal amount;
    private String paymentType;    // TUITION, FEE, HOUSING, etc.
    private String paymentMethod;  // CREDIT_CARD, CHECK, CASH, etc.

    // Populated after execute() completes — needed for undo and receipt display.
    private Payment paymentRecord;

    private final NotificationManager notificationManager;
    private final DatabaseManager     dbManager;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param student       The student making the payment.
     * @param amount        Payment amount (must be > 0).
     * @param paymentType   Category: TUITION, FEE, HOUSING, etc.
     * @param paymentMethod Method: CREDIT_CARD, CHECK, CASH, ONLINE, etc.
     */
    public PaymentCommand(ObservableStudent student, BigDecimal amount,
                          String paymentType, String paymentMethod) {
        super();
        this.commandType         = "PAYMENT";
        this.student             = student;
        this.amount              = amount;
        this.paymentType         = paymentType;
        this.paymentMethod       = paymentMethod;
        this.notificationManager = NotificationManager.getInstance();
        this.dbManager           = DatabaseManager.getInstance();
    }

    /** Backward-compatible convenience constructor for double amounts. */
    public PaymentCommand(ObservableStudent student, double amount, String paymentType) {
        this(student, BigDecimal.valueOf(amount), paymentType, "ONLINE");
    }

    // -------------------------------------------------------------------------
    // Command Interface — execute()
    // -------------------------------------------------------------------------

    @Override
    public void execute() {
        executionTime = LocalDateTime.now();

        // ── Pre-condition validation ─────────────────────────────────────────
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            successful = false;
            errorMessage = "Payment amount must be greater than zero.";
            System.out.println("✗ " + errorMessage);
            return;
        }

        // ── Build and persist the Payment entity via ORM ─────────────────────
        paymentRecord = new Payment(
                student.getId(),
                amount,
                paymentType,
                paymentMethod,
                "COMPLETED"
        );
        paymentRecord.setNotes("Processed via " + paymentMethod);

        try {
            // DatabaseManager.upsert() uses @Table/@Column annotations on Payment
            // to build the INSERT ... ON DUPLICATE KEY UPDATE statement.
            dbManager.upsert(paymentRecord);

            if (paymentRecord.getId() <= 0) {
                // upsert() should set the generated id via setId() — something went wrong.
                throw new IllegalStateException("Payment was saved but no ID was returned.");
            }

            // ── Update the student's account balance ─────────────────────────
            updateStudentAccountBalance(amount.negate()); // Payment reduces the balance owed

            executed  = true;
            successful = true;

            System.out.printf("✓ Payment processed: $%.2f (%s) via %s | Ref: %s%n",
                    amount, paymentType, paymentMethod, paymentRecord.getReferenceNumber());

            // ── Trigger Observer notification ─────────────────────────────────
            // This fires the NotificationManager which pushes to all attached
            // Observer channels (email receipt, push notification, etc.)
            notificationManager.notifyPaymentReceived(student, amount.doubleValue(), paymentType);

        } catch (SQLException | IllegalAccessException | IllegalStateException e) {
            successful   = false;
            errorMessage = "Payment processing failed: " + e.getMessage();
            System.err.println("✗ " + errorMessage);
        }
    }

    // -------------------------------------------------------------------------
    // Command Interface — undo()
    // -------------------------------------------------------------------------

    @Override
    public void undo() {
        if (!executed || !successful || paymentRecord == null) {
            System.out.println("Cannot undo — payment was not completed.");
            return;
        }

        try {
            // Mark the persisted Payment record as REFUNDED.
            paymentRecord.setStatus("REFUNDED");
            dbManager.upsert(paymentRecord);

            // Reverse the account balance adjustment.
            updateStudentAccountBalance(amount); // Adds the amount back to balance owed

            undoneAt = LocalDateTime.now();
            isUndone = true;

            System.out.printf("↶ Undone: Refund issued $%.2f (%s) | Ref: %s%n",
                    amount, paymentType, paymentRecord.getReferenceNumber());

            // Notify student of the refund.
            notificationManager.notifyPaymentReceived(student, -amount.doubleValue(), "REFUND-" + paymentType);

        } catch (SQLException | IllegalAccessException e) {
            System.err.println("✗ Failed to process refund: " + e.getMessage());
        }
    }

    @Override
    public boolean isUndoable() {
        // Can only refund if the original payment was in this session and succeeded.
        // In production you'd also enforce a refund window (e.g., same calendar day).
        return executed && successful && paymentRecord != null && paymentRecord.isCompleted();
    }

    @Override
    public String getDescription() {
        return String.format("Payment of $%.2f (%s via %s)", amount, paymentType, paymentMethod);
    }

    // -------------------------------------------------------------------------
    // Convenience Getter — used by the UI to show a receipt after execute()
    // -------------------------------------------------------------------------

    /**
     * @return the reference number for the completed payment, or null if not yet executed.
     *
     * GUI Usage:
     *   executor.execute(cmd);
     *   if (cmd.wasSuccessful()) receiptLabel.setText("Ref: " + cmd.getPaymentReferenceNumber());
     */
    public String getPaymentReferenceNumber() {
        return paymentRecord != null ? paymentRecord.getReferenceNumber() : null;
    }

    // -------------------------------------------------------------------------
    // Serialization — for CommandHistory persistence
    // -------------------------------------------------------------------------

    @Override
    protected String serializeCommandData() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new HashMap<>();
        data.put("studentId",      student.getId());
        data.put("amount",         amount.toPlainString());
        data.put("paymentType",    paymentType);
        data.put("paymentMethod",  paymentMethod);
        // Store the generated payment record id so we can retrieve it on undo/redo
        data.put("paymentId",      paymentRecord != null ? paymentRecord.getId() : 0);
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("PaymentCommand: serialization failed", e);
        }
    }

    @Override
    protected void deserializeCommandData(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Map<String, Object> data = mapper.readValue(json, Map.class);

            // Reconstruct the student by numeric pk (not the String student_id field)
            int studentPk = (int) data.get("studentId");
            Student rawStudent = dbManager.fetchOne(Student.class, "id", studentPk);
            if (rawStudent != null) {
                this.student = ObservableStudent.fromSuperType(rawStudent);
            }

            this.amount        = new BigDecimal(data.get("amount").toString());
            this.paymentType   = (String) data.get("paymentType");
            this.paymentMethod = (String) data.get("paymentMethod");

            // Re-hydrate the Payment record so undo() can find the DB row.
            int paymentId = (int) data.get("paymentId");
            if (paymentId > 0) {
                this.paymentRecord = dbManager.fetchOne(Payment.class, "id", paymentId);
            }

        } catch (JsonProcessingException | SQLException e) {
            throw new RuntimeException("PaymentCommand: deserialization failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private Helpers
    // -------------------------------------------------------------------------

    /**
     * Adjusts the student's account balance in student_accounts.
     * A negative delta reduces balance owed (payment applied).
     * A positive delta increases balance owed (refund reversed).
     *
     * The student_accounts table tracks: current_balance, total_payments.
     * This method uses a SQL UPDATE rather than ORM upsert because we need
     * an atomic increment, not a full row replace.
     */
    private void updateStudentAccountBalance(BigDecimal delta) {
        String sql = "UPDATE student_accounts " +
                "SET current_balance = current_balance + ?, " +
                "    total_payments  = total_payments  + ?, " +
                "    last_updated    = CURRENT_TIMESTAMP " +
                "WHERE student_id = ?";
        try {
            int rows = dbManager.executeUpdate(sql, delta, delta.negate(), student.getId());
            if (rows == 0) {
                // Account row doesn't exist yet — create it.
                String insert = "INSERT INTO student_accounts " +
                        "(student_id, current_balance, total_charges, total_payments) " +
                        "VALUES (?, ?, 0.00, ?)";
                dbManager.executeInsert(insert, student.getId(), delta, delta.negate());
            }
        } catch (SQLException e) {
            System.err.println("PaymentCommand: could not update student account — " + e.getMessage());
        }
    }
}