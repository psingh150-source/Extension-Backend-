package com.newtab.dashboard.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Data
public class FindBestSlotRequest {

    @NotBlank(message = "Date is required (YYYY-MM-DD)")
    private String date;

    @NotNull(message = "Working hours start is required")
    private LocalTime workingHoursStart;

    @NotNull(message = "Working hours end is required")
    private LocalTime workingHoursEnd;

    @NotNull(message = "Duration minutes is required")
    private Integer durationMinutes;

    @NotBlank(message = "Time zone is required")
    private String timeZone;

    @NotEmpty(message = "At least one attendee email is required")
    private List<String> attendeeEmails;
}