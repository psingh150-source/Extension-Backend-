package com.newtab.dashboard.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class MeetingBookingRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    @NotBlank(message = "Start date time is required")
    private String startDateTime; // ISO-8601

    @NotBlank(message = "End date time is required")
    private String endDateTime; // ISO-8601

    @NotBlank(message = "Time zone is required")
    private String timeZone;

    @NotEmpty(message = "At least one attendee email is required")
    private List<String> attendeeEmails;
}
