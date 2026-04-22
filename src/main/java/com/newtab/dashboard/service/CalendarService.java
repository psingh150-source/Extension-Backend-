package com.newtab.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.newtab.dashboard.entity.User;
import com.newtab.dashboard.exception.ConflictException;
import com.newtab.dashboard.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CalendarService {

    private static final Logger logger = LoggerFactory.getLogger(CalendarService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TokenEncryptionService tokenEncryptionService;
    private final GoogleTokenRefreshService tokenRefreshService;

    public CalendarService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            TokenEncryptionService tokenEncryptionService,
            GoogleTokenRefreshService tokenRefreshService
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.tokenEncryptionService = tokenEncryptionService;
        this.tokenRefreshService = tokenRefreshService;
    }

    public List<CalendarEventResponse> getUpcomingEvents(User user) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OffsetDateTime start = today.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = today.plusDays(14).atTime(23, 59, 59).atOffset(ZoneOffset.UTC);

        String accessToken = getValidAccessToken(user);

        try {
            return fetchEvents(accessToken, start, end);
        } catch (HttpClientErrorException.Unauthorized ex) {
            logger.warn("Token expired, attempting refresh");
            String newToken = tokenRefreshService.refreshAccessToken(user);
            return fetchEvents(newToken, start, end);
        }
    }

    public MeetingSuggestionResponse suggestBestMeetingSlot(User user, MeetingSuggestionRequest request) {
        String accessToken = getValidAccessToken(user);

        OffsetDateTime rangeStart = OffsetDateTime.parse(request.getRangeStartDateTime());
        OffsetDateTime rangeEnd = OffsetDateTime.parse(request.getRangeEndDateTime());

        if (!rangeEnd.isAfter(rangeStart)) {
            throw new IllegalArgumentException("Range end must be after range start");
        }

        int durationMinutes = request.getDurationMinutes();
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("Duration must be > 0");
        }

        List<String> attendeeEmails = normalizeEmails(request.getAttendeeEmails());

        Map<String, List<TimeRange>> busyMap = fetchBusyMapWithRetry(
                accessToken,
                attendeeEmails,
                request.getRangeStartDateTime(),
                request.getRangeEndDateTime(),
                request.getTimeZone(),
                user
        );

        Duration meetingDuration = Duration.ofMinutes(durationMinutes);

        OffsetDateTime cursor = rangeStart;
        MeetingSuggestionResponse best = null;

        while (!cursor.plus(meetingDuration).isAfter(rangeEnd)) {
            OffsetDateTime slotEnd = cursor.plus(meetingDuration);

            List<String> conflictEmails = new ArrayList<>();
            for (String email : attendeeEmails) {
                List<TimeRange> busyRanges = busyMap.getOrDefault(email, List.of());
                if (isOverlappingAny(cursor, slotEnd, busyRanges)) {
                    conflictEmails.add(email);
                }
            }

            MeetingSuggestionResponse current = MeetingSuggestionResponse.builder()
                    .startDateTime(cursor.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .endDateTime(slotEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .conflicts(conflictEmails.size())
                    .conflictEmails(conflictEmails)
                    .build();

            if (best == null) {
                best = current;
            } else {
                if (current.getConflicts() < best.getConflicts()) {
                    best = current;
                }
            }

            if (current.getConflicts() == 0) {
                return current;
            }

            cursor = cursor.plusMinutes(15);
        }

        if (best == null) {
            throw new ConflictException("No valid meeting slot found");
        }

        return best;
    }

    public MeetingBookingResponse bookMeeting(User user, MeetingBookingRequest request) {
        String accessToken = getValidAccessToken(user);

        String start = request.getStartDateTime();
        String end = request.getEndDateTime();

        List<String> attendeeEmails = normalizeEmails(request.getAttendeeEmails());

        Map<String, List<TimeRange>> busyMap = fetchBusyMapWithRetry(
                accessToken,
                attendeeEmails,
                start,
                end,
                request.getTimeZone(),
                user
        );

        List<String> conflicts = new ArrayList<>();
        OffsetDateTime slotStart = OffsetDateTime.parse(start);
        OffsetDateTime slotEnd = OffsetDateTime.parse(end);

        for (String email : attendeeEmails) {
            if (isOverlappingAny(slotStart, slotEnd, busyMap.getOrDefault(email, List.of()))) {
                conflicts.add(email);
            }
        }

        if (!conflicts.isEmpty()) {
            throw new ConflictException("Some attendees are busy in the selected time: " + conflicts);
        }

        JsonNode created = createEventWithEmailNotifications(accessToken, request, user.getEmail());

        return MeetingBookingResponse.builder()
                .eventId(created.path("id").asText(null))
                .htmlLink(created.path("htmlLink").asText(null))
                .status(created.path("status").asText(null))
                .startDateTime(start)
                .endDateTime(end)
                .meetingLink(extractMeetingLink(created))
                .build();
    }

    public MeetingAvailabilityResponse checkAvailability(User user, MeetingAvailabilityRequest request) {
        String accessToken = getValidAccessToken(user);

        List<String> attendeeEmails = normalizeEmails(request.getAttendeeEmails());

        Map<String, List<TimeRange>> busyMap = fetchBusyMapWithRetry(
                accessToken,
                attendeeEmails,
                request.getStartDateTime(),
                request.getEndDateTime(),
                request.getTimeZone(),
                user
        );

        Map<String, List<MeetingAvailabilityResponse.TimeSlot>> attendeeBusySlots = new HashMap<>();
        List<String> unavailableAttendees = new ArrayList<>();
        boolean allAvailable = true;

        OffsetDateTime slotStart = OffsetDateTime.parse(request.getStartDateTime());
        OffsetDateTime slotEnd = OffsetDateTime.parse(request.getEndDateTime());

        for (String email : attendeeEmails) {
            List<TimeRange> busyRanges = busyMap.getOrDefault(email, List.of());
            List<MeetingAvailabilityResponse.TimeSlot> busySlots = busyRanges.stream()
                    .map(range -> MeetingAvailabilityResponse.TimeSlot.builder()
                            .start(range.start().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                            .end(range.end().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                            .build())
                    .collect(Collectors.toList());

            attendeeBusySlots.put(email, busySlots);

            if (isOverlappingAny(slotStart, slotEnd, busyRanges)) {
                allAvailable = false;
                unavailableAttendees.add(email);
            }
        }

        MeetingSuggestionResponse suggestedSlot = null;
        if (!allAvailable) {
            MeetingSuggestionRequest suggestionRequest = new MeetingSuggestionRequest();
            suggestionRequest.setRangeStartDateTime(request.getStartDateTime());
            suggestionRequest.setRangeEndDateTime(request.getEndDateTime());
            suggestionRequest.setTimeZone(request.getTimeZone());
            suggestionRequest.setDurationMinutes((int) Duration.between(slotStart, slotEnd).toMinutes());
            suggestionRequest.setAttendeeEmails(attendeeEmails);

            try {
                suggestedSlot = suggestBestMeetingSlot(user, suggestionRequest);
            } catch (ConflictException e) {
                logger.warn("No alternative slot found: {}", e.getMessage());
            }
        }

        return MeetingAvailabilityResponse.builder()
                .allAvailable(allAvailable)
                .attendeeBusySlots(attendeeBusySlots)
                .unavailableAttendees(unavailableAttendees)
                .suggestedSlot(suggestedSlot)
                .build();
    }

    public MeetingBookingResponse createMeeting(User user, CreateMeetingRequest request) {
        String accessToken = getValidAccessToken(user);

        MeetingAvailabilityRequest availabilityRequest = new MeetingAvailabilityRequest();
        availabilityRequest.setStartDateTime(request.getStartDateTime());
        availabilityRequest.setEndDateTime(request.getEndDateTime());
        availabilityRequest.setTimeZone(request.getTimeZone());
        availabilityRequest.setAttendeeEmails(request.getAttendeeEmails());

        MeetingAvailabilityResponse availability = checkAvailability(user, availabilityRequest);

        if (!availability.isAllAvailable()) {
            throw new ConflictException(
                    "Cannot create meeting. Following attendees are busy: " +
                            String.join(", ", availability.getUnavailableAttendees())
            );
        }

        JsonNode created = createEventWithEmailNotifications(accessToken, request, user.getEmail());

        logger.info("Meeting created successfully. Event ID: {}, Organizer: {}, Attendees: {}",
                created.path("id").asText(), user.getEmail(), request.getAttendeeEmails());

        return MeetingBookingResponse.builder()
                .eventId(created.path("id").asText(null))
                .htmlLink(created.path("htmlLink").asText(null))
                .status(created.path("status").asText(null))
                .startDateTime(request.getStartDateTime())
                .endDateTime(request.getEndDateTime())
                .meetingLink(extractMeetingLink(created))
                .build();
    }

    public FindBestSlotResponse findBestSlot(User user, FindBestSlotRequest request) {
        String accessToken = getValidAccessToken(user);

        List<String> attendeeEmails = normalizeEmails(request.getAttendeeEmails());
        LocalDate targetDate = LocalDate.parse(request.getDate());

        List<MeetingSuggestionResponse> suggestions = new ArrayList<>();
        int totalSlotsChecked = 0;

        LocalTime currentTime = request.getWorkingHoursStart();
        LocalTime workEnd = request.getWorkingHoursEnd();

        while (currentTime.plusMinutes(request.getDurationMinutes()).compareTo(workEnd) <= 0) {
            ZonedDateTime slotStartZoned = ZonedDateTime.of(
                    targetDate, currentTime,
                    ZoneId.of(request.getTimeZone())
            );
            ZonedDateTime slotEndZoned = slotStartZoned.plusMinutes(request.getDurationMinutes());

            String startIso = slotStartZoned.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String endIso = slotEndZoned.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            Map<String, List<TimeRange>> busyMap = fetchBusyMapWithRetry(
                    accessToken,
                    attendeeEmails,
                    startIso,
                    endIso,
                    request.getTimeZone(),
                    user
            );

            List<String> conflictEmails = new ArrayList<>();
            for (String email : attendeeEmails) {
                if (isOverlappingAny(slotStartZoned.toOffsetDateTime(), slotEndZoned.toOffsetDateTime(),
                        busyMap.getOrDefault(email, List.of()))) {
                    conflictEmails.add(email);
                }
            }

            suggestions.add(MeetingSuggestionResponse.builder()
                    .startDateTime(startIso)
                    .endDateTime(endIso)
                    .conflicts(conflictEmails.size())
                    .conflictEmails(conflictEmails)
                    .build());

            totalSlotsChecked++;
            currentTime = currentTime.plusMinutes(30);
        }

        suggestions.sort(Comparator
                .comparingInt(MeetingSuggestionResponse::getConflicts)
                .thenComparing(MeetingSuggestionResponse::getStartDateTime));

        String message = suggestions.isEmpty() ?
                "No available slots found in working hours" :
                "Found " + suggestions.size() + " potential slots. " +
                        (suggestions.get(0).getConflicts() == 0 ?
                                "Perfect slot available!" :
                                "Best slot has " + suggestions.get(0).getConflicts() + " conflict(s)");

        return FindBestSlotResponse.builder()
                .suggestions(suggestions)
                .totalSlotsChecked(totalSlotsChecked)
                .message(message)
                .build();
    }

    private String getValidAccessToken(User user) {
        String accessToken = tokenEncryptionService.decrypt(user.getGoogleAccessToken());
        if (accessToken == null) {
            throw new UnauthorizedException("Missing Google access token");
        }
        return accessToken;
    }

    private Map<String, List<TimeRange>> fetchBusyMapWithRetry(
            String accessToken,
            List<String> emails,
            String timeMin,
            String timeMax,
            String timeZone,
            User user
    ) {
        try {
            return fetchBusyMap(accessToken, emails, timeMin, timeMax, timeZone);
        } catch (UnauthorizedException ex) {
            if (ex.getMessage() != null && (ex.getMessage().contains("insufficient") ||
                    ex.getMessage().contains("permission") ||
                    ex.getMessage().contains("scope"))) {
                logger.error("Calendar permission error. User needs to re-authenticate.");
                throw new UnauthorizedException(
                        "Calendar permission denied. Please log out and log back in to grant calendar access."
                );
            }

            logger.warn("Token may be expired, attempting refresh");
            String newToken = tokenRefreshService.refreshAccessToken(user);
            return fetchBusyMap(newToken, emails, timeMin, timeMax, timeZone);
        }
    }

    private Map<String, List<TimeRange>> fetchBusyMap(
            String accessToken,
            List<String> emails,
            String timeMin,
            String timeMax,
            String timeZone
    ) {
        String url = "https://www.googleapis.com/calendar/v3/freeBusy";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String itemsJson = emails.stream()
                .map(e -> "{\"id\":\"" + escapeJson(e) + "\"}")
                .collect(Collectors.joining(","));

        String payload = """
            {
              "timeMin": "%s",
              "timeMax": "%s",
              "timeZone": "%s",
              "items": [%s]
            }
            """.formatted(timeMin, timeMax, timeZone, itemsJson);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
        } catch (HttpClientErrorException ex) {
            String responseBody = ex.getResponseBodyAsString();
            logger.error("Google FreeBusy API error: {}", responseBody);

            if (responseBody.contains("insufficientAuthenticationScopes") ||
                    responseBody.contains("insufficientPermissions") ||
                    responseBody.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT")) {
                throw new UnauthorizedException(
                        "Calendar permission denied. Please re-authenticate with calendar access."
                );
            }

            throw new UnauthorizedException("Google FreeBusy API error: " + responseBody);
        }

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode calendarsNode = root.path("calendars");

            Map<String, List<TimeRange>> busyMap = new HashMap<>();

            for (String email : emails) {
                JsonNode busyNode = calendarsNode.path(email).path("busy");
                List<TimeRange> ranges = new ArrayList<>();

                if (busyNode.isArray()) {
                    for (JsonNode b : busyNode) {
                        String start = b.path("start").asText(null);
                        String end = b.path("end").asText(null);
                        if (start != null && end != null) {
                            ranges.add(new TimeRange(OffsetDateTime.parse(start), OffsetDateTime.parse(end)));
                        }
                    }
                }

                busyMap.put(email, ranges);
            }

            return busyMap;
        } catch (Exception ex) {
            logger.error("Failed to parse free/busy response", ex);
            throw new UnauthorizedException("Failed to parse free/busy response");
        }
    }

    private List<CalendarEventResponse> fetchEvents(String accessToken, OffsetDateTime start, OffsetDateTime end) {
        String url = UriComponentsBuilder
                .fromHttpUrl("https://www.googleapis.com/calendar/v3/calendars/primary/events")
                .queryParam("singleEvents", true)
                .queryParam("orderBy", "startTime")
                .queryParam("timeMin", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .queryParam("timeMax", end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .queryParam("timeZone", "UTC")
                .queryParam("maxResults", 50)
                .build()
                .encode()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
        } catch (HttpClientErrorException ex) {
            String details = ex.getResponseBodyAsString();
            throw new UnauthorizedException("Google Calendar API error: " + details);
        }

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode items = root.path("items");
            List<CalendarEventResponse> events = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    events.add(toEvent(item));
                }
            }
            events.sort(Comparator.comparing(CalendarEventResponse::getDate)
                    .thenComparing(CalendarEventResponse::getStartTime));
            return events;
        } catch (Exception ex) {
            throw new UnauthorizedException("Failed to parse calendar response");
        }
    }

    private CalendarEventResponse toEvent(JsonNode item) {
        String title = item.path("summary").asText("(No title)");
        String location = item.path("location").asText(null);
        String meetingLink = extractMeetingLink(item);

        JsonNode startNode = item.path("start");
        JsonNode endNode = item.path("end");

        String startDateTime = startNode.path("dateTime").asText(null);
        String startDate = startNode.path("date").asText(null);
        String endDateTime = endNode.path("dateTime").asText(null);
        String endDate = endNode.path("date").asText(null);

        if (startDateTime == null && startDate != null) {
            return CalendarEventResponse.builder()
                    .title(title)
                    .date(startDate)
                    .startTime("All day")
                    .endTime("All day")
                    .location(location)
                    .meetingLink(meetingLink)
                    .build();
        }

        String date = startDateTime != null ? startDateTime.substring(0, 10) : startDate;
        return CalendarEventResponse.builder()
                .title(title)
                .date(date)
                .startTime(startDateTime)
                .endTime(endDateTime != null ? endDateTime : endDate)
                .location(location)
                .meetingLink(meetingLink)
                .build();
    }

    private String extractMeetingLink(JsonNode item) {
        String hangoutLink = item.path("hangoutLink").asText(null);
        if (hangoutLink != null && !hangoutLink.isBlank()) {
            return hangoutLink;
        }

        JsonNode entryPoints = item.path("conferenceData").path("entryPoints");
        if (entryPoints.isArray()) {
            for (JsonNode entry : entryPoints) {
                String uri = entry.path("uri").asText(null);
                if (uri != null && !uri.isBlank()) {
                    return uri;
                }
            }
        }

        return null;
    }

    private boolean isOverlappingAny(OffsetDateTime start, OffsetDateTime end, List<TimeRange> busyRanges) {
        for (TimeRange range : busyRanges) {
            if (start.isBefore(range.end) && end.isAfter(range.start)) {
                return true;
            }
        }
        return false;
    }

    // Updated method with proper email notification handling for MeetingBookingRequest
    private JsonNode createEventWithEmailNotifications(String accessToken, MeetingBookingRequest request, String organizerEmail) {
        String url = "https://www.googleapis.com/calendar/v3/calendars/primary/events?conferenceDataVersion=1";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String description = request.getDescription() != null ? request.getDescription() : "";

        // Build attendees list - DO NOT include organizer as an attendee
        List<Map<String, Object>> attendeesList = new ArrayList<>();
        Set<String> uniqueAttendees = new HashSet<>(request.getAttendeeEmails());

        for (String email : uniqueAttendees) {
            if (!email.equalsIgnoreCase(organizerEmail)) {
                Map<String, Object> attendee = new HashMap<>();
                attendee.put("email", email);
                attendee.put("responseStatus", "needsAction");
                attendeesList.add(attendee);
            }
        }

        String requestId = UUID.randomUUID().toString();

        // Build the complete event payload
        Map<String, Object> event = new HashMap<>();
        event.put("summary", request.getTitle());
        event.put("description", description);
        event.put("sendUpdates", "all"); // This ensures email notifications are sent
        event.put("guestsCanModify", false);
        event.put("guestsCanInviteOthers", false);
        event.put("guestsCanSeeOtherGuests", true);

        Map<String, String> start = new HashMap<>();
        start.put("dateTime", request.getStartDateTime());
        start.put("timeZone", request.getTimeZone());
        event.put("start", start);

        Map<String, String> end = new HashMap<>();
        end.put("dateTime", request.getEndDateTime());
        end.put("timeZone", request.getTimeZone());
        event.put("end", end);

        if (!attendeesList.isEmpty()) {
            event.put("attendees", attendeesList);
        }

        Map<String, Object> conferenceData = new HashMap<>();
        Map<String, Object> createRequest = new HashMap<>();
        createRequest.put("requestId", requestId);
        Map<String, String> conferenceSolutionKey = new HashMap<>();
        conferenceSolutionKey.put("type", "hangoutsMeet");
        createRequest.put("conferenceSolutionKey", conferenceSolutionKey);
        conferenceData.put("createRequest", createRequest);
        event.put("conferenceData", conferenceData);

        Map<String, Object> reminders = new HashMap<>();
        reminders.put("useDefault", true);
        event.put("reminders", reminders);

        try {
            String payload = objectMapper.writeValueAsString(event);
            logger.info("Creating event with email notifications. Payload: {}", payload);

            HttpEntity<String> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            JsonNode result = objectMapper.readTree(response.getBody());

            logger.info("Event created. Response status: {}", response.getStatusCode());
            logger.info("Event ID: {}", result.path("id").asText());
            logger.info("HTML Link: {}", result.path("htmlLink").asText());

            // Verify attendees
            if (result.has("attendees")) {
                for (JsonNode attendee : result.path("attendees")) {
                    String email = attendee.path("email").asText();
                    String responseStatus = attendee.path("responseStatus").asText();
                    logger.info("Attendee {} - responseStatus: {}", email, responseStatus);
                }
            } else if (!attendeesList.isEmpty()) {
                logger.warn("No attendees found in response even though attendees were requested!");
            }

            // Check if conference data was created
            if (result.has("conferenceData")) {
                logger.info("Conference data (Google Meet) created successfully");
            } else {
                logger.info("No conference data (meet link) created");
            }

            return result;
        } catch (Exception ex) {
            logger.error("Failed to create calendar event", ex);
            throw new UnauthorizedException("Failed to create calendar event: " + ex.getMessage());
        }
    }

    // Updated method with proper email notification handling for CreateMeetingRequest
    private JsonNode createEventWithEmailNotifications(String accessToken, CreateMeetingRequest request, String organizerEmail) {
        String url = "https://www.googleapis.com/calendar/v3/calendars/primary/events?conferenceDataVersion="
                + (request.isCreateConferenceCall() ? 1 : 0);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String description = request.getDescription() != null ? request.getDescription() : "";

        // Build attendees list - DO NOT include organizer as an attendee
        List<Map<String, Object>> attendeesList = new ArrayList<>();
        Set<String> uniqueAttendees = new HashSet<>(request.getAttendeeEmails());

        for (String email : uniqueAttendees) {
            if (!email.equalsIgnoreCase(organizerEmail)) {
                Map<String, Object> attendee = new HashMap<>();
                attendee.put("email", email);
                attendee.put("responseStatus", "needsAction");
                attendeesList.add(attendee);
            }
        }

        // Build the complete event payload
        Map<String, Object> event = new HashMap<>();
        event.put("summary", request.getTitle());
        event.put("description", description);
        event.put("sendUpdates", "all"); // This ensures email notifications are sent
        event.put("guestsCanModify", false);
        event.put("guestsCanInviteOthers", false);
        event.put("guestsCanSeeOtherGuests", true);

        Map<String, String> start = new HashMap<>();
        start.put("dateTime", request.getStartDateTime());
        start.put("timeZone", request.getTimeZone());
        event.put("start", start);

        Map<String, String> end = new HashMap<>();
        end.put("dateTime", request.getEndDateTime());
        end.put("timeZone", request.getTimeZone());
        event.put("end", end);

        if (!attendeesList.isEmpty()) {
            event.put("attendees", attendeesList);
        }

        Map<String, Object> reminders = new HashMap<>();
        reminders.put("useDefault", true);
        event.put("reminders", reminders);

        if (request.isCreateConferenceCall()) {
            Map<String, Object> conferenceData = new HashMap<>();
            Map<String, Object> createRequest = new HashMap<>();
            createRequest.put("requestId", UUID.randomUUID().toString());
            Map<String, String> conferenceSolutionKey = new HashMap<>();
            conferenceSolutionKey.put("type", "hangoutsMeet");
            createRequest.put("conferenceSolutionKey", conferenceSolutionKey);
            conferenceData.put("createRequest", createRequest);
            event.put("conferenceData", conferenceData);
        }

        try {
            String payload = objectMapper.writeValueAsString(event);
            logger.info("Creating event with email notifications. Payload: {}", payload);

            HttpEntity<String> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            JsonNode result = objectMapper.readTree(response.getBody());

            logger.info("Event created successfully. Event ID: {}, HTML Link: {}",
                    result.path("id").asText(), result.path("htmlLink").asText());

            // Verify attendees
            if (result.has("attendees")) {
                for (JsonNode attendee : result.path("attendees")) {
                    String email = attendee.path("email").asText();
                    String responseStatus = attendee.path("responseStatus").asText();
                    logger.info("Attendee {} status: {}", email, responseStatus);
                }
            } else if (!attendeesList.isEmpty()) {
                logger.warn("No attendees found in response even though attendees were requested!");
            }

            return result;
        } catch (Exception ex) {
            logger.error("Failed to create calendar event", ex);
            throw new UnauthorizedException("Failed to create calendar event: " + ex.getMessage());
        }
    }

    private List<String> normalizeEmails(List<String> attendeeEmails) {
        if (attendeeEmails == null) {
            return List.of();
        }

        Set<String> set = new HashSet<>();
        for (String email : attendeeEmails) {
            if (email != null && !email.isBlank()) {
                set.add(email.trim().toLowerCase());
            }
        }
        return new ArrayList<>(set);
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t");
    }

    private record TimeRange(OffsetDateTime start, OffsetDateTime end) {
    }
}