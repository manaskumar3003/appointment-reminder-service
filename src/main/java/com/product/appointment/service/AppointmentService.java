package com.product.appointment.service;

import com.product.appointment.dto.AppointmentResponse;
import com.product.appointment.dto.CreateAppointmentRequest;
import com.product.appointment.entity.Appointment;
import com.product.appointment.entity.AppointmentStatus;
import com.product.appointment.entity.Reminder;
import com.product.appointment.entity.ReminderStatus;
import com.product.appointment.entity.ReminderType;
import com.product.appointment.repository.AppointmentRepository;
import com.product.appointment.repository.ReminderRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appointmentRepository;
    private final ReminderRepository reminderRepository;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              ReminderRepository reminderRepository) {
        this.appointmentRepository = appointmentRepository;
        this.reminderRepository = reminderRepository;
    }

    /** One transaction, so an appointment can never exist without the reminders it owes. */
    @Transactional
    public AppointmentResponse createAppointment(CreateAppointmentRequest request) {

        Appointment appointment = new Appointment();
        appointment.setDealershipId(request.getDealershipId());
        appointment.setCustomerContact(request.getCustomerContact());
        appointment.setScheduledAt(request.getScheduledAt());
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        Appointment saved = appointmentRepository.save(appointment);

        List<Reminder> reminders = List.of(
                createReminder(saved, ReminderType.TWENTY_FOUR_HOURS, 24),
                createReminder(saved, ReminderType.TWO_HOURS, 2));

        log.info("Created appointment {} for dealership {} at {} with {} reminders",
                saved.getId(), saved.getDealershipId(), saved.getScheduledAt(), reminders.size());

        return toResponse(saved, reminders);
    }

    /** A send time already in the past is written SKIPPED, not PENDING - and still written, so it's auditable. */
    private Reminder createReminder(Appointment appointment, ReminderType type, int hoursBefore) {

        OffsetDateTime sendAt = appointment.getScheduledAt().minusHours(hoursBefore);
        boolean alreadyPast = sendAt.isBefore(OffsetDateTime.now());

        Reminder reminder = new Reminder();
        reminder.setAppointment(appointment);
        reminder.setReminderType(type);
        reminder.setScheduledAt(sendAt);
        reminder.setStatus(alreadyPast ? ReminderStatus.SKIPPED : ReminderStatus.PENDING);
        reminder.setAttemptCount(0);

        Reminder saved = reminderRepository.save(reminder);

        if (alreadyPast) {
            log.info("Skipping {} reminder for appointment {}: send time {} already passed",
                    type, appointment.getId(), sendAt);
        }
        return saved;
    }

    @Transactional
    public AppointmentResponse cancelAppointment(Long id) {

        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No appointment with id " + id));

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            return toResponse(appointment, remindersFor(id));
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        int cancelled = reminderRepository.cancelPendingForAppointment(id);
        log.info("Cancelled appointment {} and {} pending reminders", id, cancelled);

        // Re-read: the bulk UPDATE above bypasses the persistence context.
        return toResponse(appointment, remindersFor(id));
    }

    @Transactional(readOnly = true)
    public AppointmentResponse getAppointment(Long id) {
        return appointmentRepository.findById(id)
                .map(appointment -> toResponse(appointment, remindersFor(id)))
                .orElseThrow(() -> new EntityNotFoundException("No appointment with id " + id));
    }

    private List<Reminder> remindersFor(Long appointmentId) {
        return reminderRepository.findByAppointmentIdOrderByScheduledAt(appointmentId);
    }

    private AppointmentResponse toResponse(Appointment appointment, List<Reminder> reminders) {
        AppointmentResponse response = new AppointmentResponse();
        response.setId(appointment.getId());
        response.setDealershipId(appointment.getDealershipId());
        response.setCustomerContact(appointment.getCustomerContact());
        response.setScheduledAt(appointment.getScheduledAt());
        response.setStatus(appointment.getStatus());
        response.setReminders(reminders.stream()
                .map(r -> new AppointmentResponse.ReminderView(
                        r.getId(), r.getReminderType(), r.getScheduledAt(), r.getStatus()))
                .toList());
        return response;
    }
}
