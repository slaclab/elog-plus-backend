package edu.stanford.slac.elog_plus.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.apache.commons.lang3.StringEscapeUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Is the model for the new ELog creation")
public record EntryImportDTO(
        @Schema(description = "Identifier for unique find the record into the original system")
        String originId,
        @Schema(description = "Is the original id for which this entry is supersede")
        String supersedeOfByOriginId,
        @Schema(description = "Is the list of the original ids where this entry reference to")
        List<String> referencesByOriginId,
        @NotNull
        @Schema(description = "Is the logbooks where the new log belong")
        List<String> logbooks,
        @NotNull
        @NotEmpty
        @Schema(description = "The title of the log")
        String title,
        @Schema(description = "The content of the log")
        @NotNull
        String text,
        @Schema(description = "The last name of user that generates the entry")
        String lastName,
        @Schema(description = "The first name of user that generates the entry")
        String firstName,
        @Schema(description = "The username of user that generates the entry")
        String userName,
        @Schema(description = "Is the general note field")
        String note,
        @Schema(description = "The tags describes represent the log")
        List<String> tags,
        @Schema(description = "The timestamp when the event is occurred")
        @JsonDeserialize(using = LocalDateTimeDeserializer.class)
        @JsonSerialize(using = LocalDateTimeSerializer.class)
        LocalDateTime loggedAt,
        @Schema(description = "The timestamp when the event is occurred")
        @JsonDeserialize(using = LocalDateTimeDeserializer.class)
        @JsonSerialize(using = LocalDateTimeSerializer.class)
        LocalDateTime eventAt
) {
    public EntryImportDTO {
        if (tags == null) tags = Collections.emptyList();
        title = StringEscapeUtils.unescapeHtml4(title); // Decode HTML entities
        text = StringEscapeUtils.unescapeHtml4(text);
    }
}
