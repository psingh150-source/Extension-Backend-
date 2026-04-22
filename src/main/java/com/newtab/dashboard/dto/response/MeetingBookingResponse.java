package com.newtab.dashboard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingBookingResponse {

    private String eventId;
    private String htmlLink;
    private String status;

    private String startDateTime;
    private String endDateTime;

    private String meetingLink;
}
