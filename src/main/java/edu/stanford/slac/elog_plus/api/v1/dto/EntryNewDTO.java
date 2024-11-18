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

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder(toBuilder = true)
@Schema(description = "Is the model for the new ELog creation")
public record EntryNewDTO(
        @NotNull
        @Schema(description = "Is the logbooks where the new log belong")
        Set<String> logbooks,
        @NotNull
        @NotEmpty
        @Schema(description = "The title of the log")
        String title,
        @Schema(description = "The content of the log")
        @NotNull
        String text,
        @Schema(description = "Is the general note field")
        String note,
        @Schema(description = "The tags describes represent the log")
        Set<String> tags,
        @Schema(description = "The list of the attachment of the log")
        Set<String> attachments,
        @Schema(description = "Identify the entry as summarize of a shift")
        SummarizesDTO summarizes,
        @Schema(description = "The timestamp when the event is occurred")
        @JsonDeserialize(using = LocalDateTimeDeserializer.class)
        @JsonSerialize(using = LocalDateTimeSerializer.class)
        LocalDateTime eventAt,
        @Schema(description = "The list user that need to be notify by email")
        Set<String> userIdsToNotify
) {
    public EntryNewDTO {
        if (tags == null) tags = Collections.emptySet();
        if (attachments == null) attachments = Collections.emptySet();
    }
}
