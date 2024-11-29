package edu.stanford.slac.elog_plus.api.v1.mapper;

import edu.stanford.slac.ad.eed.base_mongodb_lib.repository.AuthorizationRepository;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationDTO;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationOwnerTypeDTO;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationTypeDTO;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.PersonDTO;
import edu.stanford.slac.ad.eed.baselib.api.v1.mapper.AuthMapper;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.ad.eed.baselib.model.AuthorizationOwnerType;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.service.AuthorizationServices;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.List;

import static edu.stanford.slac.ad.eed.baselib.exception.Utility.wrapCatch;

@Mapper(
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = TagMapper.class,
        componentModel = "spring"
)
public abstract class LogbookMapper {
    @Autowired
    protected AuthorizationRepository authorizationRepository;
    @Autowired
    protected AuthMapper authMapper;
    @Autowired
    protected AppProperties appProperties;
    @Autowired
    protected AuthorizationServices authorizationServices;

    public abstract LogbookSummaryDTO fromModelToSummaryDTO(Logbook log);

    public abstract LogbookSummaryDTO fromModelToSummaryDTO(LogbookDTO log);

    public abstract LogbookDTO fromModel(Logbook logbook);

    @Mapping(target = "authorizations", expression = "java(getAuthorizations(logbook, includeAuthorizations))")
    public abstract LogbookDTO fromModel(Logbook logbook, boolean includeAuthorizations);

    public abstract Logbook fromDTO(NewLogbookDTO logbookDTO);

    public abstract Logbook fromDTO(LogbookDTO logbookDTO);

    @Mapping(target = "readAll", source = "readAll", defaultValue = "false")
    @Mapping(target = "writeAll", source = "writeAll", defaultValue = "false")
    public abstract Logbook fromDTO(UpdateLogbookDTO logbookDTO);

    public abstract Entry fromDTO(EntryNewDTO entryNewDTO, String firstName, String lastName, String userName);

    public abstract UpdateLogbookDTO toUpdateDTO(LogbookDTO logbook);

    /**
     * Return all the authorizations for a logbook
     *
     * @param logbook                    the id of the logbook
     * @param includeAuthorizations if false a null is returned as list of authorizations
     * @return the list of authorizations of the logbook
     */
    public List<DetailsAuthorizationDTO> getAuthorizations(Logbook logbook, boolean includeAuthorizations) {
        if (logbook == null) return Collections.emptyList();
        if (!includeAuthorizations) return Collections.emptyList();
        return wrapCatch(
                () -> authorizationRepository.findByResourceIs(String.format("/logbook/%s", logbook.getId()))
                        .stream()
                        .map(
                                auth -> {
                                    var convertedAuth = fromAuthorization(auth);
                                    return convertedAuth.toBuilder()
                                            .resourceId(logbook.getId())
                                            .resourceName(logbook.getName())
                                            .resourceType(ResourceTypeDTO.Logbook)
                                            .build();
                                }
                        ).toList(),
                -1,
                "LogbookMapper::getAuthorizations"
        );
    }

    /**
     * Return all the authorizations for a logbook
     *
     * @param authorization standard authorization
     * @return the list of ownerId authorizations
     */
    public DetailsAuthorizationDTO fromAuthorization(Authorization authorization) {
        AuthorizationDTO dto = authMapper.fromModel(authorization);
        return fromAuthorizationDTO(dto);
    }

    /**
     * Return all the authorizations for a logbook
     *
     * @param authorization standard authorization
     * @return the list of ownerId authorizations
     */
    public DetailsAuthorizationDTO fromAuthorizationDTO(AuthorizationDTO authorization) {
        String ownerLabel = authorization.owner();
        switch(authorization.ownerType()){
            case User: {
                PersonDTO person = authorizationServices.findPersonByEMail(authorization.owner());
                ownerLabel = person != null ? person.gecos() : null;
                break;
            }
            case Group: {
                var groupFound = authorizationServices.findGroup(authorization.owner(), false, false);
                ownerLabel = groupFound != null ? groupFound.name() : null;
                break;
            }
            case Token: {
                var foundApplication = authorizationServices.getApplicationById(authorization.owner(), false);
                ownerLabel = foundApplication != null ? foundApplication.name() : null;
                break;
            }
        }
        return DetailsAuthorizationDTO.builder()
                .id(authorization.id())
                .ownerId(authorization.owner())
                .ownerType(authorization.ownerType())
                .ownerName(ownerLabel)
                .permission(authorization.authorizationType())
                .build();
    }

    /**
     * Return the AuthorizationOwnerTypeDTO from the AuthorizationOwnerType
     *
     * @param oType the AuthorizationOwnerType
     * @return the AuthorizationOwnerTypeDTO
     */
    public AuthorizationOwnerType getAuthorizationOwnerType(AuthorizationOwnerTypeDTO oType) {
        if(oType == null) return null;
        switch (oType){
            case AuthorizationOwnerTypeDTO.User:
                return AuthorizationOwnerType.User;
            case AuthorizationOwnerTypeDTO.Group:
                return AuthorizationOwnerType.Group;
            case AuthorizationOwnerTypeDTO.Token:
                return AuthorizationOwnerType.Token;
        }
        return null;
    }

    /**
     * Return the AuthorizationOwnerTypeDTO from the AuthorizationOwnerType
     *
     * @param aType the AuthorizationType
     * @return the Authorization.Type
     */
    public Integer getAuthorizationType(AuthorizationTypeDTO aType) {
        if (aType == null) return null;
        switch (aType){
            case AuthorizationTypeDTO.Read:
                return Authorization.Type.Read.getValue();
            case AuthorizationTypeDTO.Write:
                return Authorization.Type.Write.getValue();
            case AuthorizationTypeDTO.Admin:
                return Authorization.Type.Admin.getValue();
        }
        return null;
    }
}
