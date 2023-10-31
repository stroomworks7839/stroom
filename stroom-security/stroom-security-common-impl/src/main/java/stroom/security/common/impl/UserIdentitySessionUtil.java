package stroom.security.common.impl;

import stroom.security.api.UserIdentity;
import stroom.util.NullSafe;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Arrays;
import java.util.Optional;

public final class UserIdentitySessionUtil {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(UserIdentitySessionUtil.class);

    private static final String SESSION_USER_IDENTITY = "SESSION_USER_IDENTITY";
    private static final String STROOM_SESSION_ID = "STROOM_SESSION_ID";
    private static final String JSESSIONID = "JSESSIONID";

    private UserIdentitySessionUtil() {
    }

    /**
     * Set the {@link UserIdentity} on the session
     */
    public static void set(final HttpSession session, final UserIdentity userIdentity) {
        LOGGER.debug(() -> LogUtil.message("Setting userIdentity {} of type {} in session {}",
                userIdentity,
                NullSafe.get(userIdentity, UserIdentity::getClass, Class::getSimpleName),
                NullSafe.get(session, HttpSession::getId)));

        session.setAttribute(SESSION_USER_IDENTITY, userIdentity);
    }

    /**
     * Set the userIdentity on the session if session cookies are found, creating the session as required
     */
    public static void set(final HttpServletRequest request, final UserIdentity userIdentity) {
        if (requestHasSessionCookie(request)) {
            // Set the user ref in the session.
            set(request.getSession(true), userIdentity);
        }
    }

    public static Optional<UserIdentity> get(final HttpSession session) {
        return Optional.ofNullable(session)
                .map(session2 -> (UserIdentity) session2.getAttribute(SESSION_USER_IDENTITY));
    }

    public static Optional<UserIdentity> get(final HttpServletRequest request) {
        return Optional.ofNullable(request)
                .flatMap(req -> get(req.getSession(false)));
    }

    public static boolean requestHasSessionCookie(final HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return false;
        }

        // Find out if we have a session cookie
        return Arrays
                .stream(request.getCookies())
                .anyMatch(cookie ->
                        cookie.getName().equalsIgnoreCase(STROOM_SESSION_ID) ||
                                cookie.getName().equalsIgnoreCase(JSESSIONID));
    }
}
