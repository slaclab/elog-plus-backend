package edu.stanford.slac.elog_plus.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "The query parameter")
public record QueryWithAnchorDTO(
        @Schema(description = "Is the id to point to as starting point in the search")
        String anchorID,
        @Schema(description = "Only include entries after this date. Defaults to current time.")
        @JsonDeserialize(using = LocalDateTimeDeserializer.class)
        @JsonSerialize(using = LocalDateTimeSerializer.class)
        LocalDateTime startDate,
        @Schema(description = "Only include entries before this date. If not supplied, then does not apply any filter")
        @JsonDeserialize(using = LocalDateTimeDeserializer.class)
        @JsonSerialize(using = LocalDateTimeSerializer.class)
        LocalDateTime endDate,
        @Schema(description = "Show entries tat belong to the last N shifts. If this parameter is set, the startDate and endDate are ignored.")
        Integer lastNShifts,
        @Schema(description = "Include this number of entries before the startDate (used for highlighting entries)")
        Integer contextSize,
        @Schema(description = "Limit the number the number of entries after the start date.")
        Integer limit,
        @Schema(description = "Typical search functionality.")
        String search,
        @Schema(description = "Only include entries that use one of these tags.")
        List<String> tags,
        @Schema(description = "Only include entries that belong to one of these logbooks. If the list is empty mean tha user can read everywhere, if null the user is not authorized on any logbook")
        List<String> logbooks,
        @Schema(description = "Sort by log date (the default is for event date)")
        Boolean sortByLogDate,
        @Schema(description = "Hide summaries from the query results")
        Boolean hideSummaries,
        @Schema(description = "Requires that all the found entry contains all the tags")
        Boolean requireAllTags,
        @Schema(description = "Is the id of the origin entry (used for the supersede functionality)")
        String originId
        ) {}
