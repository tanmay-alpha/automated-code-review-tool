package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.config.AppConfig;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * Names, attributes and lookup of the authentication cookies.
 *
 * <p>All cookies are host-only (no {@code Domain}), {@code Path=/},
 * {@code HttpOnly} and {@code SameSite=Lax}. When {@code app.cookie-secure}
 * is on (every HTTPS deployment) they also carry {@code Secure} and the
 * {@code __Host-} name prefix. Browsers only accept a {@code __Host-} cookie
 * that is Secure, has {@code Path=/} and no {@code Domain}, so a sibling
 * subdomain (e.g. {@code blog.example.com} next to {@code app.example.com})
 * cannot plant ("toss") a session or OAuth-state cookie for this host.
 * Local plain-HTTP development cannot use the prefix and keeps bare names.</p>
 *
 * <p>A plain value object (not a bean) so filters picked up by
 * {@code @WebMvcTest} slices need nothing beyond {@link AppConfig}.</p>
 */
public final class AuthCookies {

    public static final String ACCESS_TOKEN = "accessToken";
    public static final String REFRESH_TOKEN = "refreshToken";
    public static final String OAUTH_STATE = "oauth_state";

    static final String HOST_PREFIX = "__Host-";

    private final boolean secure;

    public AuthCookies(AppConfig appConfig) {
        this.secure = appConfig.isCookieSecure();
    }

    /** The on-the-wire name for one of {@link #ACCESS_TOKEN}, {@link #REFRESH_TOKEN}, {@link #OAUTH_STATE}. */
    public String name(String baseName) {
        return secure ? HOST_PREFIX + baseName : baseName;
    }

    /** Value of the named auth cookie, or {@code null}. Only the exact wire name counts. */
    public String read(HttpServletRequest request, String baseName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String wireName = name(baseName);
        for (Cookie cookie : cookies) {
            if (wireName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** True when the request carries a session cookie (access or refresh). */
    public boolean hasSessionCookie(HttpServletRequest request) {
        return read(request, ACCESS_TOKEN) != null || read(request, REFRESH_TOKEN) != null;
    }

    public ResponseCookie build(String baseName, String value, Duration maxAge) {
        return ResponseCookie.from(name(baseName), value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    public ResponseCookie clear(String baseName) {
        return build(baseName, "", Duration.ZERO);
    }
}
