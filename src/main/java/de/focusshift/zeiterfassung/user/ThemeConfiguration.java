package de.focusshift.zeiterfassung.user;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class ThemeConfiguration implements WebMvcConfigurer {

    private final UserThemeDataProvider userThemeDataProvider;
    private final UserTimeFormatDataProvider userTimeFormatDataProvider;

    ThemeConfiguration(UserThemeDataProvider userThemeDataProvider, UserTimeFormatDataProvider userTimeFormatDataProvider) {
        this.userThemeDataProvider = userThemeDataProvider;
        this.userTimeFormatDataProvider = userTimeFormatDataProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userThemeDataProvider);
        registry.addInterceptor(userTimeFormatDataProvider);
    }
}
