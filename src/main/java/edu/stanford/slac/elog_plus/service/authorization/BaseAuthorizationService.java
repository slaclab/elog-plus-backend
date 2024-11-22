
package edu.stanford.slac.elog_plus.service.authorization;

import edu.stanford.slac.ad.eed.baselib.exception.NotAuthenticated;
import edu.stanford.slac.ad.eed.baselib.exception.NotAuthorized;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.exception.ResourceNotFound;
import lombok.AllArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import static edu.stanford.slac.ad.eed.baselib.exception.Utility.assertion;

/**
 * Base authorization service
 */
@Service
@AllArgsConstructor
public class BaseAuthorizationService {
    private final AuthService authService;
    /**
     * Check if the user is authenticated
     *
     * @param authentication the authentication object
     * @return true if the user is authenticated, false otherwise
     */
    public boolean checkAuthenticated(Authentication authentication) {
        // check for auth
        assertion(
                NotAuthenticated.notAuthenticatedBuilder()
                        .errorCode(-1)
                        .errorDomain("BaseAuthorizationService::checkAuthenticated")
                        .build(),
                // should be authenticated
                () -> authService.checkAuthentication(authentication)
        );
        return true;
    }

    /**
     * Check if the user is authenticated
     *
     * @param authentication the authentication object
     * @return true if the user is authenticated, false otherwise
     */
    public boolean checkForRoot(Authentication authentication) {
        // check for auth
        assertion(
                NotAuthorized.notAuthorizedBuilder()
                        .errorCode(-1)
                        .errorDomain("BaseAuthorizationService::checkForRoot")
                        .build(),
                // should be authenticated
                () -> authService.checkForRoot(authentication)
        );
        return true;
    }

    /**
     * Check if the user is authenticated
     *
     * @param authentication the authentication object
     * @return true if the user is authenticated, false otherwise
     */
    public boolean checkForRootReturnNotFound(Authentication authentication) {
        // check for auth
        assertion(
                ResourceNotFound.genericBuilder()
                        .errorCode(-1)
                        .errorDomain("BaseAuthorizationService::checkForRootReturnNotFound")
                        .build(),
                // should be authenticated
                () -> authService.checkForRoot(authentication)
        );
        return true;
    }
}
