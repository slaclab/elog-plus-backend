package edu.stanford.slac.elog_plus.service;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.*;
import edu.stanford.slac.ad.eed.baselib.api.v2.dto.LocalGroupDTO;
import edu.stanford.slac.ad.eed.baselib.api.v2.dto.LocalGroupQueryParameterDTO;
import edu.stanford.slac.ad.eed.baselib.auth.jwt.SLACAuthenticationJWTToken;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.ad.eed.baselib.service.PeopleGroupService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.api.v1.dto.NewAuthorizationDTO;
import edu.stanford.slac.elog_plus.api.v1.mapper.AuthorizationMapper;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static edu.stanford.slac.ad.eed.baselib.exception.Utility.assertion;
import static edu.stanford.slac.elog_plus.config.CacheConfig.AUTHORIZATIONS;
import static edu.stanford.slac.elog_plus.config.CacheConfig.LOGBOOKS;

@Service
@Log4j2
@AllArgsConstructor
public class AuthorizationServices {

    AuthService authService;
    AppProperties appProperties;
    PeopleGroupService peopleGroupService;
    AuthorizationMapper authorizationMapper;

    /**
     * Find users based on the query parameter
     *
     * @param personQueryParameterDTO the query parameter
     * @return the list of users found
     */
    public List<UserDetailsDTO> findUsers(PersonQueryParameterDTO personQueryParameterDTO, Boolean includeAuthorizations, Boolean includeInheritance) {
        // found users
        var foundUsers = peopleGroupService.findPersons(personQueryParameterDTO);
        log.info("Finding users with query {} return {} results", personQueryParameterDTO, foundUsers.size());
        //convert to UserDetailsDTO
        return foundUsers.stream().map(
                u -> authorizationMapper.fromPersonDTO
                        (
                                u,
                                includeAuthorizations ? authorizationMapper.fromAuthorizationDTO(
                                        authService.getAllAuthenticationForOwner(
                                                u.mail(),
                                                AuthorizationOwnerTypeDTO.User,
                                                Optional.empty(),
                                                Optional.of(includeInheritance)
                                        )
                                ) : Collections.emptyList()
                        )

        ).toList();
    }

    /**
     * Find a user
     *
     * @param userId                the id of the user to find
     * @param includeAuthorizations if true include the authorizations
     * @return the user details
     */
    @Cacheable(value = AUTHORIZATIONS, key = "'user-byId'+ #userId +'includeAuth'+ #includeAuthorizations +'includeMembers'+ #includeInheritance")
    public UserDetailsDTO findUser(String userId, Boolean includeAuthorizations, Boolean includeInheritance) {
        // found users
        var foundUser = peopleGroupService.findPersonByEMail(userId);
        //convert to UserDetailsDTO
        return authorizationMapper.fromPersonDTO
                (
                        foundUser,
                        includeAuthorizations ? authorizationMapper.fromAuthorizationDTO(
                                authService.getAllAuthenticationForOwner(
                                        foundUser.mail(),
                                        AuthorizationOwnerTypeDTO.User,
                                        Optional.of(false),
                                        Optional.of(includeInheritance)
                                )
                        ) : Collections.emptyList()
                );
    }

    /**
     * Find a group
     *
     * @param localGroupId          the id of the group to find
     * @param includeAuthorizations if true include the authorizations
     * @return the group details
     */
    @Cacheable(value = AUTHORIZATIONS, key = "'groupBy-byId'+ #localGroupId +'includeAuth'+ #includeAuthorizations +'includeMembers'+ #includeMembers")
    public GroupDetailsDTO findGroup(String localGroupId, Boolean includeMembers, Boolean includeAuthorizations) {
        // find the group
        LocalGroupDTO groupFound = authService.findLocalGroupById(localGroupId);
        boolean processGroup = includeMembers && groupFound.members()!=null;
        return GroupDetailsDTO.builder()
                .id(groupFound.id())
                .name(groupFound.name())
                .description(groupFound.description())
                .members
                        (
                                processGroup ?
                                        groupFound.members().stream().map(
                                                m -> authorizationMapper.fromPersonDTO(
                                                        m,
                                                        // user details on group doesn't have authorizations
                                                        Collections.emptyList()
                                                )
                                        ).toList() :
                                        Collections.emptyList()
                        )
                .authorizations
                        (
                                includeAuthorizations ?
                                        authorizationMapper.fromAuthorizationDTO
                                                (
                                                        authService.getAllAuthenticationForOwner
                                                                (
                                                                        groupFound.id(),
                                                                        AuthorizationOwnerTypeDTO.Group,
                                                                        Optional.empty()
                                                                )
                                                ) :
                                        Collections.emptyList()
                        )
                .build();

    }

    @Cacheable(value = AUTHORIZATIONS, key = "'findPersonByEMail'+ #email")
    public PersonDTO findPersonByEMail(String email) {
        return peopleGroupService.findPersonByEMail(email);
    }

    /**
     * Find groups based on the query parameter
     *
     * @param localGroupQueryParameterDTO the query parameter
     * @param includeAuthorizations       if true include the authorizations
     * @return the list of groups found
     */
    public List<GroupDetailsDTO> findGroups(
            LocalGroupQueryParameterDTO localGroupQueryParameterDTO,
            Boolean includeMembers,
            Boolean includeAuthorizations
    ) {
        var foundGroups = authService.findLocalGroup(localGroupQueryParameterDTO);
        log.info("Finding groups with query {} return {} results", localGroupQueryParameterDTO, foundGroups.size());
        return foundGroups.stream().map(
                g -> GroupDetailsDTO.builder()
                        .id(g.id())
                        .name(g.name())
                        .description(g.description())
                        .members(
                                includeMembers ?
                                        g.members().stream().map(
                                                m -> authorizationMapper.fromPersonDTO(
                                                        m,
                                                        // user details on group doesn't has authorizations
                                                        Collections.emptyList()
                                                )
                                        ).toList() :
                                        Collections.emptyList()
                        )
                        .authorizations(
                                includeAuthorizations ?
                                        authorizationMapper.fromAuthorizationDTO
                                                (
                                                        authService.getAllAuthenticationForOwner
                                                                (
                                                                        g.id(),
                                                                        AuthorizationOwnerTypeDTO.Group,
                                                                        Optional.empty()
                                                                )
                                                ) :
                                        Collections.emptyList()
                        )
                        .build()
        ).toList();
    }

    /**
     * Find a user
     *
     * @param applicationId         the id of the user to find
     * @param includeAuthorizations if true include the authorizations
     * @return the user details
     */
    @Cacheable(value = AUTHORIZATIONS, key = "'application-byId'+ #applicationId +'includeAuth'+ #includeAuthorizations")
    public ApplicationDetailsDTO getApplicationById(String applicationId, boolean includeAuthorizations) {
        log.info("Finding application with id {}", applicationId);
        var authTokenFound = authService.getAuthenticationTokenById(applicationId).orElseThrow(
                () -> ControllerLogicException
                        .builder()
                        .errorCode(-1)
                        .errorMessage("Application not found")
                        .errorDomain("AuthorizationServices::getApplicationById")
                        .build()
        );
        log.info("Founding authorizations for application {}", authTokenFound.id());
        List<DetailsAuthorizationDTO> auhtorizationList = includeAuthorizations ?
                authorizationMapper.fromAuthorizationDTO
                        (
                                authService.getAllAuthenticationForOwner
                                        (
                                                authTokenFound.id(),
                                                AuthorizationOwnerTypeDTO.Token,
                                                Optional.empty()
                                        )
                        ) :
                Collections.emptyList();
        if(!auhtorizationList.isEmpty()) {
            log.info("Found {} authorizations for application {}", auhtorizationList.size(), authTokenFound);
        }
        return ApplicationDetailsDTO.builder()
                .id(authTokenFound.id())
                .name(authTokenFound.name())
                .email(authTokenFound.email())
                .token(authTokenFound.token())
                .expiration(authTokenFound.expiration())
                .applicationManaged(authTokenFound.applicationManaged())
                .authorizations(auhtorizationList)
                .build();
    }

    /**
     * Find all applications
     *
     * @param authenticationTokenQueryParameterDTO the query parameter
     * @param includeMembers                       if true include the members
     * @param includeAuthorizations                if true include the authorizations
     * @return the list of applications found
     */
    public List<ApplicationDetailsDTO> findAllApplications(
            AuthenticationTokenQueryParameterDTO authenticationTokenQueryParameterDTO,
            Boolean includeMembers,
            Boolean includeAuthorizations
    ) {
        var authTokenFound = authService.findAllAuthenticationToken(authenticationTokenQueryParameterDTO);
        log.info("Finding applications with query {} return {} results", authenticationTokenQueryParameterDTO, authTokenFound.size());
        return authTokenFound.stream().map(
                a -> ApplicationDetailsDTO.builder()
                        .id(a.id())
                        .name(a.name())
                        .email(a.email())
                        .token(a.token())
                        .expiration(a.expiration())
                        .applicationManaged(a.applicationManaged())
                        .authorizations(
                                includeAuthorizations ?
                                        authorizationMapper.fromAuthorizationDTO
                                                (
                                                        authService.getAllAuthenticationForOwner
                                                                (
                                                                        a.id(),
                                                                        AuthorizationOwnerTypeDTO.Token,
                                                                        Optional.empty()
                                                                )
                                                ) :
                                        Collections.emptyList()
                        )
                        .build()
        ).toList();
    }

    /**
     * Create a new authorization
     *
     * @param newAuthorizationDTO the new authorization to create
     */
    @CacheEvict(value = {LOGBOOKS, AUTHORIZATIONS}, allEntries = true)
    public void createNew(NewAuthorizationDTO newAuthorizationDTO) {
        // check the resourceType type
        String resource = getResource(newAuthorizationDTO);
        // find resourceType for same ownerId
        var foundAuthorization = authService.getAllAuthenticationForOwner(
                newAuthorizationDTO.ownerId(),
                newAuthorizationDTO.ownerType(),
                Optional.empty()
        );

        //check if user, group or application exists before to add authorization
        String realId = ensureOwnerExistence(newAuthorizationDTO.ownerId(), newAuthorizationDTO.ownerType());

        // check if the authorization already exists
        if(foundAuthorization.stream().anyMatch(a -> a.resource().equals(resource))) {
            log.info(
                    "Resource {}/{} already authorized for {}/{} by {}",
                    newAuthorizationDTO.resourceType(), newAuthorizationDTO.resourceId(),
                    newAuthorizationDTO.ownerType(), newAuthorizationDTO.ownerId(),
                    getCurrentUsername()
            );
            return;
        }
        log.info(
                "Creating new authorization on {}/{} for {}/{} by {}",
                newAuthorizationDTO.resourceType(), newAuthorizationDTO.resourceId(),
                newAuthorizationDTO.ownerType(), newAuthorizationDTO.ownerId(),
                getCurrentUsername()
        );
        // we can create the authorization
        authService.addNewAuthorization(
                edu.stanford.slac.ad.eed.baselib.api.v1.dto.NewAuthorizationDTO
                        .builder()
                        .owner(realId)
                        .ownerType(newAuthorizationDTO.ownerType())
                        .authorizationType(newAuthorizationDTO.permission())
                        .resource(resource)
                        .build()
        );
    }

    /**
     * Check if the owner exists
     *
     * @param ownerId                   the id of the owner
     * @param authorizationOwnerTypeDTO the type of the owner
     */
    private String ensureOwnerExistence(String ownerId, AuthorizationOwnerTypeDTO authorizationOwnerTypeDTO) {
        String realIdToUse = null;
        switch (authorizationOwnerTypeDTO) {
            case User: {
                PersonDTO foundPerson = null;
                // if not found will try an exception
                try {
                    foundPerson = peopleGroupService.findPersonByEMail(ownerId);
                } catch (Exception e) {
                    // try to find As ID
                    foundPerson = peopleGroupService.findPersonByUid(ownerId);
                }
                // return always email also in case the uid is passed
                realIdToUse = foundPerson.mail();
                break;
            }
            case Group:
                // if not found will try an exception
                authService.findLocalGroupById(realIdToUse = ownerId);
                break;
            case Token:
                authService.getAuthenticationTokenById(realIdToUse = ownerId).orElseThrow(
                        () -> ControllerLogicException
                                .builder()
                                .errorCode(-1)
                                .errorMessage("Application not found")
                                .errorDomain("AuthorizationServices::ensureOwnerExistence")
                                .build()
                );
                break;
            default:
                throw ControllerLogicException
                        .builder()
                        .errorCode(-1)
                        .errorMessage("Owner type not found")
                        .errorDomain("AuthorizationServices::ensureOwnerExistence")
                        .build();
        }
        return realIdToUse;
    }

    /**
     * Delete an authorization
     *
     * @param authorizationId the id of the authorization to delete
     */
    @CacheEvict(value =  {LOGBOOKS, AUTHORIZATIONS}, allEntries = true)
    public void deleteAuthorization(String authorizationId) {
        var authorizationFound = authService.findAuthorizationById(authorizationId);
        log.info("Deleting authorization {} by {}", authorizationFound, getCurrentUsername());
        authService.deleteAuthorizationById(authorizationId);
    }

    /**
     * Update an authorization
     *
     * @param authorizationId        the id of the authorization to update
     * @param updateAuthorizationDTO the update information
     */
    @Transactional
    @CacheEvict(value =  {LOGBOOKS, AUTHORIZATIONS}, allEntries = true)
    public void updateAuthorization(String authorizationId, UpdateAuthorizationDTO updateAuthorizationDTO) {
        var authorizationFound = authService.findAuthorizationById(authorizationId);
        authService.updateAuthorizationType(authorizationId, updateAuthorizationDTO.permission());
        log.info("Updating authorization {} by {}", authorizationFound, getCurrentUsername());
    }

    /**
     * Create a new application
     *
     * @param newApplicationDTO the new application to create
     * @return the id of the new application
     */
    public String createNewApplication(NewApplicationDTO newApplicationDTO) {
        var createdAuthToken = authService.addNewApplicationAuthenticationToken(
                NewAuthenticationTokenDTO
                        .builder()
                        .name(newApplicationDTO.name())
                        .expiration(newApplicationDTO.expiration())
                        .build(),
                false);
        log.info("Created new application {} by {}", createdAuthToken, getCurrentUsername());
        return createdAuthToken.id();
    }

    /**
     * Delete an application
     *
     * @param applicationId the id of the application to delete
     */
    @CacheEvict(value =  {LOGBOOKS, AUTHORIZATIONS}, allEntries = true)
    public void deleteApplication(String applicationId) {
        authService.deleteToken(applicationId);
    }


    /**
     * Get the current username
     *
     * @return the current username
     */
    public String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof SLACAuthenticationJWTToken) {
                return ((SLACAuthenticationJWTToken) principal).getPrincipal().toString();
            } else {
                return principal.toString();
            }
        }
        return null;
    }

    /**
     * Create the resourceType analyzing the resourceType and resourceType Id
     *
     * @param newAuthorizationDTO
     * @return
     */
    public String getResource(NewAuthorizationDTO newAuthorizationDTO) {
        switch (newAuthorizationDTO.resourceType()) {
            case Logbook:
                return "/logbook/%s".formatted(newAuthorizationDTO.resourceId());
            case All:
                return "*";
            case Group:
                return "%s/group".formatted(appProperties.getAppName());
            default:
                throw ControllerLogicException
                        .builder()
                        .errorCode(-1)
                        .errorMessage("Resource type not found")
                        .errorDomain("AuthorizationServices::getResource")
                        .build();
        }
    }

}
