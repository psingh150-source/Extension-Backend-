package com.newtab.dashboard.controller;

import com.newtab.dashboard.dto.request.CreateMeetingRequest;
import com.newtab.dashboard.dto.request.FindBestSlotRequest;
import com.newtab.dashboard.dto.request.MeetingAvailabilityRequest;
import com.newtab.dashboard.dto.request.MeetingBookingRequest;
import com.newtab.dashboard.dto.request.MeetingSuggestionRequest;
import com.newtab.dashboard.dto.response.CalendarEventResponse;
import com.newtab.dashboard.dto.response.FindBestSlotResponse;
import com.newtab.dashboard.dto.response.MeetingAvailabilityResponse;
import com.newtab.dashboard.dto.response.MeetingBookingResponse;
import com.newtab.dashboard.dto.response.MeetingSuggestionResponse;
import com.newtab.dashboard.service.CalendarService;
import com.newtab.dashboard.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final CalendarService calendarService;
    private final UserService userService;

    public CalendarController(CalendarService calendarService, UserService userService) {
        this.calendarService = calendarService;
        this.userService = userService;
    }

    @GetMapping("/events")
    public ResponseEntity<List<CalendarEventResponse>> events() {
        return ResponseEntity.ok(calendarService.getUpcomingEvents(userService.getCurrentUserEntity()));
    }

    @PostMapping("/suggest")
    public ResponseEntity<MeetingSuggestionResponse> suggestMeetingSlot(@Valid @RequestBody MeetingSuggestionRequest request) {
        return ResponseEntity.ok(calendarService.suggestBestMeetingSlot(userService.getCurrentUserEntity(), request));
    }

    @PostMapping("/book")
    public ResponseEntity<MeetingBookingResponse> bookMeeting(@Valid @RequestBody MeetingBookingRequest request) {
        return ResponseEntity.ok(calendarService.bookMeeting(userService.getCurrentUserEntity(), request));
    }

    // NEW ENDPOINTS FOR MEETING SCHEDULING FEATURE

    @PostMapping("/check-availability")
    public ResponseEntity<MeetingAvailabilityResponse> checkAvailability(@Valid @RequestBody MeetingAvailabilityRequest request) {
        return ResponseEntity.ok(calendarService.checkAvailability(userService.getCurrentUserEntity(), request));
    }

    @PostMapping("/create-meeting")
    public ResponseEntity<MeetingBookingResponse> createMeeting(@Valid @RequestBody CreateMeetingRequest request) {
        return ResponseEntity.ok(calendarService.createMeeting(userService.getCurrentUserEntity(), request));
    }

    @PostMapping("/find-best-slot")
    public ResponseEntity<FindBestSlotResponse> findBestSlot(@Valid @RequestBody FindBestSlotRequest request) {
        return ResponseEntity.ok(calendarService.findBestSlot(userService.getCurrentUserEntity(), request));
    }
}