package com.newtab.dashboard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEventResponse {
    private String title;
    private String startTime;
    private String endTime;
    private String date;
    private String location;
    private String meetingLink;
}
