package de.focusshift.zeiterfassung.user;

import de.focusshift.zeiterfassung.security.oidc.CurrentOidcUser;
import de.focusshift.zeiterfassung.web.DataProviderInterface;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

import java.security.Principal;

import static java.lang.invoke.MethodHandles.lookup;

@Component
public class UserTimeFormatDataProvider implements DataProviderInterface {

    private static final Logger LOG = LoggerFactory.getLogger(lookup().lookupClass());

    private final UserSettingsService userSettingsService;

    UserTimeFormatDataProvider(UserSettingsService userSettingsService) {
        this.userSettingsService = userSettingsService;
    }

    @Override
    public void postHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                           @NonNull Object handler, ModelAndView modelAndView) {
        if (addDataIf(modelAndView)) {
            final TimeFormat timeFormat;

            final Principal userPrincipal = request.getUserPrincipal();
            if (userPrincipal instanceof OAuth2AuthenticationToken token) {
                final OAuth2User oauth2User = token.getPrincipal();
                if (oauth2User instanceof CurrentOidcUser user) {
                    timeFormat = userSettingsService.findTimeFormat(user.getUserIdComposite())
                        .orElse(TimeFormat.HOURS_24);
                } else {
                    LOG.debug("userPrincipal not of type {}. Using default time format.", CurrentOidcUser.class.getName());
                    timeFormat = TimeFormat.HOURS_24;
                }
            } else {
                timeFormat = TimeFormat.HOURS_24;
            }

            modelAndView.addObject("timeFormatPattern", timeFormat.pattern());
        }
    }
}
