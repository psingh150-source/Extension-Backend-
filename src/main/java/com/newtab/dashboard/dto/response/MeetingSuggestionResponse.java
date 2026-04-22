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
public class MeetingSuggestionResponse {

    private String startDateTime;
    private String endDateTime;

    private int conflicts;
    private List<String> conflictEmails;
}
