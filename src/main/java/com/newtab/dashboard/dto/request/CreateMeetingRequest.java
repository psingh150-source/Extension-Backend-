package com.newtab.dashboard.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CreateMeetingRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    @NotBlank(message = "Start date time is required")
    private String startDateTime;

    @NotBlank(message = "End date time is required")
    private String endDateTime;

    @NotBlank(message = "Time zone is required")
    private String timeZone;

    @NotEmpty(message = "At least one attendee email is required")
    private List<String> attendeeEmails;

    private boolean sendUpdates = true;  // Default to true
    private boolean createConferenceCall = true;
}