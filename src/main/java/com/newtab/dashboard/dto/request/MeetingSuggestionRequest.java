package com.newtab.dashboard.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class MeetingSuggestionRequest {

    @NotBlank(message = "Range start date time is required")
    private String rangeStartDateTime; // ISO-8601

    @NotBlank(message = "Range end date time is required")
    private String rangeEndDateTime; // ISO-8601

    @NotBlank(message = "Time zone is required")
    private String timeZone;

    @NotNull(message = "Duration minutes is required")
    private Integer durationMinutes;

    @NotEmpty(message = "At least one attendee email is required")
    private List<String> attendeeEmails;
}
