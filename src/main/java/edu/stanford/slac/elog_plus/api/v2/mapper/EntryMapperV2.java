package edu.stanford.slac.elog_plus.api.v2.mapper;

import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.api.v2.dto.NewEntryDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static edu.stanford.slac.ad.eed.baselib.exception.Utility.wrapCatch;

@Mapper(
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        componentModel = "spring"
)
public abstract class EntryMapperV2 {
    @Mapping(target = "logbooks", source = "logbooks")
    @Mapping(target = "tags", source = "tags")
    @Mapping(target = "attachments", source = "attachments")
    public abstract EntryNewDTO toEntryNewDTO(NewEntryDTO entryNewDTO, Set<String> logbooks, Set<String> tags, Set<String> attachments);
}
