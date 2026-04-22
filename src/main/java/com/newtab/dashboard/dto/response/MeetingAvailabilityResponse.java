package com.newtab.dashboard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingAvailabilityResponse {
    private boolean allAvailable;
    private Map<String, List<TimeSlot>> attendeeBusySlots;
    private List<String> unavailableAttendees;
    private MeetingSuggestionResponse suggestedSlot;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSlot {
        private String start;
        private String end;
    }
}