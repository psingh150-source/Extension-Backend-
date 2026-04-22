package com.newtab.dashboard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FindBestSlotResponse {
    private List<MeetingSuggestionResponse> suggestions;
    private int totalSlotsChecked;
    private String message;
}