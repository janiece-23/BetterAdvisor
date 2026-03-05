package edu.advising.commands;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.advising.core.DatabaseManager;
import edu.advising.core.Table;
import edu.advising.notifications.NotificationManager;
import edu.advising.notifications.ObservableStudent;

import java.sql.SQLException;
import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.advising.users.Student;

import java.util.HashMap;
import java.util.Map;

/**
 * RegisterCommand - Register student for a course section
 */
@Table(name = "command_history", isSubTable = true)
public class RegisterCommand extends BaseCommand {
    private ObservableStudent student;
    private Section section;
    private NotificationManager notificationManager;
    private int enrollmentId;

    public RegisterCommand(ObservableStudent student, Section section) {
        super();
        this.commandType = "REGISTER";
        this.student = student;
        this.section = section;
        this.notificationManager = NotificationManager.getInstance();
    }

    @Override
    public void execute() {
        executionTime = LocalDateTime.now();

        if (!section.hasCapacity()) {
            successful = false;
            System.out.printf("✗ Registration failed for %s - section full%n", section.getCourseCode());
            this.setErrorMessage(String.format("✗ Registration failed for %s - section full", section.getCourseCode()));
            return;
        }

        // Check for schedule conflicts (simplified)
        if (hasScheduleConflict()) {  // TODO: Should be a student schedule check
            successful = false;
            System.out.printf("✗ Registration failed for %s - schedule conflict%n", section.getCourseCode());
            this.setErrorMessage(
                    String.format("✗ Registration failed for %s - schedule conflict", section.getCourseCode()));
            return;
        }

        if ((this.enrollmentId = section.enroll(student)) > 0) {
            executed = true;
            successful = true;
            System.out.printf("✓ Student %s registered for %s%n", student.getStudentId(), section.getCourseCode());

            // Trigger notification
            notificationManager.notifyRegistration(student, section.getCourseCode(), true);
        } else {
            successful = false;
        }
    }

    @Override
    public void undo() {
        if (!executed || !successful) {
            System.out.println("Cannot undo - command not executed or failed");
            return;
        }

        // Remove from section
        if( section.drop(student) ) {
            System.out.printf("↶ Undone: Registration for %s%n", section.getCourseCode());
            this.undoneAt = LocalDateTime.now();
            this.isUndone = true;
            // Notify about drop
            notificationManager.notifyRegistration(student, section.getCourseCode(), false);
        }
    }

    @Override
    public boolean isUndoable() {
        return executed && successful;
    }

    @Override
    public String getDescription() {
        return String.format("Register for %s (%s)", section.getCourseCode(), section.getCourseName());
    }

    private boolean hasScheduleConflict() {
        // Simplified - in real implementation, check time conflicts in student.
        return false;
    }

    @Override
    protected String serializeCommandData() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new HashMap<>();
        data.put("studentPk", student.getId());    //TODO: I'm not sure this is needed since my ORM handles sub-classes.
        data.put("studentId", student.getStudentId());
        data.put("sectionId", section.getId()); // Assuming Section has an id
        data.put("enrollmentId", enrollmentId);
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize RegisterCommand data", e);
        }
    }

    @Override
    protected void deserializeCommandData(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Map<String, Object> data = mapper.readValue(json, Map.class);
            // Reconstruct student, section, etc. from the data
            // TODO: Figure out if we have to really deal with studentPk because student is a subclass of  User.
            int studentPk = (int) data.get("studentPk");
            Student raw = DatabaseManager.getInstance().fetchOne(Student.class, "id", studentPk);
            if (raw != null) {
                this.student = ObservableStudent.fromSuperType(raw);
            }

            this.section = DatabaseManager.getInstance()
                    .fetchOne(Section.class, "id", data.get("sectionId"));
            this.enrollmentId = (int)data.get("enrollmentId");
        } catch (JsonProcessingException | SQLException e) {
            throw new RuntimeException("Failed to deserialize RegisterCommand data", e);
        }
    }
}

