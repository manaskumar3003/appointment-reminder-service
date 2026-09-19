package com.product.appointment.controller;

import com.product.appointment.dto.AppointmentResponse;
import com.product.appointment.dto.CreateAppointmentRequest;
import com.product.appointment.service.AppointmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }
    
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse createAppointment(@Valid @RequestBody CreateAppointmentRequest request) {
        return appointmentService.createAppointment(request);
    }

    @GetMapping("/{id}")
    public AppointmentResponse getAppointment(@PathVariable Long id) {
        return appointmentService.getAppointment(id);
    }

    /** Idempotent: cancelling an already-cancelled appointment returns 200, not an error. */
    @PostMapping("/{id}/cancel")
    public AppointmentResponse cancelAppointment(@PathVariable Long id) {
        return appointmentService.cancelAppointment(id);
    }
}
