CREATE TABLE appointments (
                              id BIGSERIAL PRIMARY KEY,
                              dealership_id BIGINT NOT NULL,
                              customer_contact VARCHAR(255) NOT NULL,
                              scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
                              status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
                              created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reminders (
    id BIGSERIAL PRIMARY KEY,
    appointment_id BIGSERIAL NOT NULL ,
    reminder_type varchar(10) NOT NULL ,
    scheduled_at TIMESTAMP with time zone not null ,
    status varchar(20) not null default 'PENDING',
    attempt_count INTEGER NOT NULL default 0,
    processing_started_at timestamp with time zone ,
    sent_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,

    constraint fk_reminder_appointment
                       foreign key (appointment_id)
                       references appointments(id),
    constraint uk_reminder_appointment_reminder_type
                       unique (appointment_id,reminder_type)


);

create index  idx_reminders_due
 on reminders(status,scheduled_at);
create index idx_reminders_appointment
on reminders(appointment_id)