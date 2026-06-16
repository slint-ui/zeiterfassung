package de.focusshift.zeiterfassung.notification;

import de.focusshift.zeiterfassung.email.EMailService;
import de.focusshift.zeiterfassung.settings.LockTimeEntriesSettings;
import de.focusshift.zeiterfassung.tenancy.tenant.TenantContextRunner;
import de.focusshift.zeiterfassung.tenancy.user.EMailAddress;
import de.focusshift.zeiterfassung.timeentry.ShouldWorkingHours;
import de.focusshift.zeiterfassung.timeentry.TimeEntryDay;
import de.focusshift.zeiterfassung.timeentry.TimeEntryDayService;
import de.focusshift.zeiterfassung.timeentry.TimeEntryLockService;
import de.focusshift.zeiterfassung.user.UserIdComposite;
import de.focusshift.zeiterfassung.user.UserId;
import de.focusshift.zeiterfassung.user.UserSettings;
import de.focusshift.zeiterfassung.user.UserSettingsService;
import de.focusshift.zeiterfassung.usermanagement.User;
import de.focusshift.zeiterfassung.usermanagement.UserLocalId;
import de.focusshift.zeiterfassung.usermanagement.UserManagementService;
import de.focusshift.zeiterfassung.workduration.WorkDuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    private NotificationServiceImpl sut;

    @Mock private TenantContextRunner tenantContextRunner;
    @Mock private UserManagementService userManagementService;
    @Mock private UserSettingsService userSettingsService;
    @Mock private TimeEntryDayService timeEntryDayService;
    @Mock private TimeEntryLockService timeEntryLockService;
    @Mock private EMailService eMailService;
    @Mock private ITemplateEngine mailTemplateEngine;

    // today = 2025-06-08 (Sunday)
    private final Clock clock = Clock.fixed(Instant.parse("2025-06-08T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        sut = new NotificationServiceImpl(tenantContextRunner, userManagementService, userSettingsService,
            timeEntryDayService, timeEntryLockService, eMailService, mailTemplateEngine, clock);
        // not exercised by the pure template-rendering tests, so stub leniently
        lenient().when(tenantContextRunner.runForEachActiveTenant(any(Runnable.class)))
            .thenAnswer(inv -> inv.getArgument(0, Runnable.class));
    }

    @Nested
    class SendLockWarnings {

        @Test
        void skipsWhenLockingIsInactive() throws Exception {
            when(timeEntryLockService.getLockTimeEntriesSettings())
                .thenReturn(new LockTimeEntriesSettings(false, 5));

            sut.sendLockWarnings();

            verifyNoInteractions(userManagementService, eMailService);
        }

        @Test
        void skipsUserWhenThresholdNotLargeEnoughForAdvanceWarning() throws Exception {
            // reminderDays=3, threshold=3 → 3 <= 3 → user skipped
            when(timeEntryLockService.getLockTimeEntriesSettings())
                .thenReturn(new LockTimeEntriesSettings(true, 3));
            when(userManagementService.findAllUsers()).thenReturn(List.of(user(1L)));
            UserSettings s = settings(true, 3);
            when(userSettingsService.getUserSettings(any(UserIdComposite.class))).thenReturn(s);

            sut.sendLockWarnings();

            verifyNoInteractions(eMailService);
        }

        @Test
        void skipsUserWhenNotificationsAreDisabled() throws Exception {
            when(timeEntryLockService.getLockTimeEntriesSettings())
                .thenReturn(new LockTimeEntriesSettings(true, 5));
            when(userManagementService.findAllUsers()).thenReturn(List.of(user(1L)));
            UserSettings s = settings(false, 2);
            when(userSettingsService.getUserSettings(any(UserIdComposite.class))).thenReturn(s);

            sut.sendLockWarnings();

            verifyNoInteractions(eMailService);
        }

        @Test
        void skipsUserWhenNoWorkPlannedOnWarningDate() throws Exception {
            // today=2025-06-08, threshold=5, daysAhead=2 → warningDate=2025-06-05
            when(timeEntryLockService.getLockTimeEntriesSettings())
                .thenReturn(new LockTimeEntriesSettings(true, 5));
            when(userManagementService.findAllUsers()).thenReturn(List.of(user(1L)));
            UserSettings s = settings(true, 2);
            when(userSettingsService.getUserSettings(any(UserIdComposite.class))).thenReturn(s);

            // no work planned → overtime is not negative → skip
            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 5), Duration.ZERO);

            sut.sendLockWarnings();

            verify(eMailService, never()).sendMail(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void skipsUserWhenAbsenceOnWarningDate() throws Exception {
            when(timeEntryLockService.getLockTimeEntriesSettings())
                .thenReturn(new LockTimeEntriesSettings(true, 5));
            when(userManagementService.findAllUsers()).thenReturn(List.of(user(1L)));
            UserSettings s = settings(true, 2);
            when(userSettingsService.getUserSettings(any(UserIdComposite.class))).thenReturn(s);

            // absence covers the day → overtime is not negative → skip
            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 5), Duration.ZERO);

            sut.sendLockWarnings();

            verify(eMailService, never()).sendMail(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void skipsUserWhenEntriesExistOnWarningDate() throws Exception {
            when(timeEntryLockService.getLockTimeEntriesSettings())
                .thenReturn(new LockTimeEntriesSettings(true, 5));
            when(userManagementService.findAllUsers()).thenReturn(List.of(user(1L)));
            UserSettings s = settings(true, 2);
            when(userSettingsService.getUserSettings(any(UserIdComposite.class))).thenReturn(s);

            // time entries cover the required hours → positive overtime → skip
            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 5), Duration.ofHours(1));

            sut.sendLockWarnings();

            verify(eMailService, never()).sendMail(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void sendsWarningEmailWithUsersReminderDays() throws Exception {
            // today=2025-06-08, threshold=5, daysAhead=2 → warningDate=2025-06-05
            when(timeEntryLockService.getLockTimeEntriesSettings())
                .thenReturn(new LockTimeEntriesSettings(true, 5));
            when(userManagementService.findAllUsers()).thenReturn(List.of(user(1L)));
            UserSettings s = settings(true, 2);
            when(userSettingsService.getUserSettings(any(UserIdComposite.class))).thenReturn(s);

            // under the required working time → negative overtime → warn
            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 5), Duration.ofHours(-8));
            when(mailTemplateEngine.process(eq("text/notification-lock-warning"), any(IContext.class)))
                .thenReturn("email body");

            sut.sendLockWarnings();

            ArgumentCaptor<Context> ctxCaptor = ArgumentCaptor.forClass(Context.class);
            verify(mailTemplateEngine).process(eq("text/notification-lock-warning"), ctxCaptor.capture());
            assertThat(ctxCaptor.getValue().getVariable("daysUntilLock")).isEqualTo(2);
            verify(eMailService).sendMail(eq("email@example.org"), anyString(), eq("email body"), eq(""));
        }

        @Test
        void eachUserGetsWarningForTheirOwnReminderDays() throws Exception {
            // threshold=7; user1 daysAhead=3 → warningDate=2025-06-04; user2 daysAhead=1 → warningDate=2025-06-02
            when(timeEntryLockService.getLockTimeEntriesSettings())
                .thenReturn(new LockTimeEntriesSettings(true, 7));

            User user1 = user(1L, "alice@example.org");
            User user2 = user(2L, "bob@example.org");
            when(userManagementService.findAllUsers()).thenReturn(List.of(user1, user2));

            UserSettings s1 = settings(true, 3);
            UserSettings s2 = settings(true, 1);
            when(userSettingsService.getUserSettings(compositeFor(1L))).thenReturn(s1);
            when(userSettingsService.getUserSettings(compositeFor(2L))).thenReturn(s2);

            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 4), Duration.ofHours(-8));
            stubDayService(new UserLocalId(2L), LocalDate.of(2025, 6, 2), Duration.ofHours(-8));
            when(mailTemplateEngine.process(any(String.class), any(IContext.class))).thenReturn("body");

            sut.sendLockWarnings();

            ArgumentCaptor<Context> ctxCaptor = ArgumentCaptor.forClass(Context.class);
            verify(mailTemplateEngine, times(2)).process(any(String.class), ctxCaptor.capture());
            assertThat(ctxCaptor.getAllValues())
                .extracting(ctx -> ctx.getVariable("daysUntilLock"))
                .containsExactlyInAnyOrder(3, 1);
        }

        @Test
        void includesLoggedTargetAndMissingForPartiallyLoggedDay() throws Exception {
            when(timeEntryLockService.getLockTimeEntriesSettings())
                .thenReturn(new LockTimeEntriesSettings(true, 5));
            when(userManagementService.findAllUsers()).thenReturn(List.of(user(1L)));
            UserSettings s = settings(true, 2);
            when(userSettingsService.getUserSettings(any(UserIdComposite.class))).thenReturn(s);

            // logged 3h of an 8h day → something logged, 5h still missing
            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 5), Duration.ofHours(3), Duration.ofHours(8));
            when(mailTemplateEngine.process(eq("text/notification-lock-warning"), any(IContext.class)))
                .thenReturn("body");

            sut.sendLockWarnings();

            ArgumentCaptor<Context> ctxCaptor = ArgumentCaptor.forClass(Context.class);
            verify(mailTemplateEngine).process(eq("text/notification-lock-warning"), ctxCaptor.capture());
            Context ctx = ctxCaptor.getValue();
            assertThat(ctx.getVariable("nothingLogged")).isEqualTo(false);
            assertThat(ctx.getVariable("worked")).isEqualTo("03:00");
            assertThat(ctx.getVariable("target")).isEqualTo("08:00");
            assertThat(ctx.getVariable("remaining")).isEqualTo("05:00");

            ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
            verify(eMailService).sendMail(eq("email@example.org"), subjectCaptor.capture(), eq("body"), eq(""));
            assertThat(subjectCaptor.getValue()).startsWith("Reminder: Incomplete time entries for ");
        }

        @Test
        void marksNothingLoggedAndShowsFullTargetAsMissingForEmptyDay() throws Exception {
            when(timeEntryLockService.getLockTimeEntriesSettings())
                .thenReturn(new LockTimeEntriesSettings(true, 5));
            when(userManagementService.findAllUsers()).thenReturn(List.of(user(1L)));
            UserSettings s = settings(true, 2);
            when(userSettingsService.getUserSettings(any(UserIdComposite.class))).thenReturn(s);

            // nothing logged on an 8h day → whole target missing
            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 5), Duration.ZERO, Duration.ofHours(8));
            when(mailTemplateEngine.process(eq("text/notification-lock-warning"), any(IContext.class)))
                .thenReturn("body");

            sut.sendLockWarnings();

            ArgumentCaptor<Context> ctxCaptor = ArgumentCaptor.forClass(Context.class);
            verify(mailTemplateEngine).process(eq("text/notification-lock-warning"), ctxCaptor.capture());
            Context ctx = ctxCaptor.getValue();
            assertThat(ctx.getVariable("nothingLogged")).isEqualTo(true);
            assertThat(ctx.getVariable("worked")).isEqualTo("00:00");
            assertThat(ctx.getVariable("target")).isEqualTo("08:00");
            assertThat(ctx.getVariable("remaining")).isEqualTo("08:00");

            ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
            verify(eMailService).sendMail(eq("email@example.org"), subjectCaptor.capture(), eq("body"), eq(""));
            assertThat(subjectCaptor.getValue()).startsWith("Reminder: No time entries for ");
        }
    }

    @Nested
    class TemplateRendering {

        private final ITemplateEngine engine = realTextTemplateEngine();

        @Test
        void partiallyLoggedDayBodyShowsLoggedTargetAndMissing() {
            Context ctx = baseContext(false);
            ctx.setVariable("worked", "03:00");
            ctx.setVariable("target", "08:00");
            ctx.setVariable("remaining", "05:00");

            String body = engine.process("text/notification-lock-warning", ctx);

            assertThat(body)
                .contains("not fully logged yet")
                .contains("Logged:").contains("03:00")
                .contains("Target:").contains("08:00")
                .contains("Missing:").contains("05:00")
                .contains("locked in 2 days")
                .doesNotContain("you have no time entries");
        }

        @Test
        void emptyDayBodyUsesNoTimeEntriesWording() {
            Context ctx = baseContext(true);
            ctx.setVariable("worked", "00:00");
            ctx.setVariable("target", "08:00");
            ctx.setVariable("remaining", "08:00");

            String body = engine.process("text/notification-lock-warning", ctx);

            assertThat(body)
                .contains("you have no time entries")
                .contains("locked in 2 days")
                .doesNotContain("Logged:")
                .doesNotContain("Missing:");
        }

        private Context baseContext(boolean nothingLogged) {
            Context ctx = new Context(Locale.GERMAN);
            ctx.setVariable("name", "Test");
            ctx.setVariable("dayName", "Thursday");
            ctx.setVariable("date", "05.06.2025");
            ctx.setVariable("daysUntilLock", 2);
            ctx.setVariable("nothingLogged", nothingLogged);
            return ctx;
        }

        private ITemplateEngine realTextTemplateEngine() {
            ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
            resolver.setResolvablePatterns(Set.of("text/*"));
            resolver.setPrefix("/mail/");
            resolver.setSuffix(".txt");
            resolver.setTemplateMode(TemplateMode.TEXT);
            resolver.setCharacterEncoding("UTF-8");
            SpringTemplateEngine templateEngine = new SpringTemplateEngine();
            templateEngine.addTemplateResolver(resolver);
            return templateEngine;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubDayService(UserLocalId userLocalId, LocalDate warningDate, Duration overtime) {
        final TimeEntryDay day = mock(TimeEntryDay.class);
        when(day.overtime()).thenReturn(overtime);
        // worked/should are only read once a warning is actually sent (negative overtime);
        // stub leniently so the skip-path tests don't trip strict-stubbing checks.
        lenient().when(day.workDuration()).thenReturn(WorkDuration.ZERO);
        lenient().when(day.shouldWorkingHours()).thenReturn(new ShouldWorkingHours(Duration.ofHours(8)));
        when(timeEntryDayService.getTimeEntryDays(eq(warningDate), eq(warningDate.plusDays(1)), eq(userLocalId)))
            .thenReturn(List.of(day));
    }

    /** Variant with explicit worked/should durations so the email figures can be asserted. */
    private void stubDayService(UserLocalId userLocalId, LocalDate warningDate, Duration worked, Duration should) {
        final TimeEntryDay day = mock(TimeEntryDay.class);
        when(day.overtime()).thenReturn(worked.minus(should));
        when(day.workDuration()).thenReturn(new WorkDuration(worked));
        when(day.shouldWorkingHours()).thenReturn(new ShouldWorkingHours(should));
        when(timeEntryDayService.getTimeEntryDays(eq(warningDate), eq(warningDate.plusDays(1)), eq(userLocalId)))
            .thenReturn(List.of(day));
    }

    private static User user(long localId) {
        return user(localId, "email@example.org");
    }

    private static User user(long localId, String email) {
        return new User(compositeFor(localId), "Test", "User", new EMailAddress(email), Set.of());
    }

    private static UserIdComposite compositeFor(long localId) {
        return new UserIdComposite(new UserId("uuid-" + localId), new UserLocalId(localId));
    }

    private static UserSettings settings(boolean notificationsEnabled, int reminderDays) {
        UserSettings s = mock(UserSettings.class);
        when(s.notificationsEnabled()).thenReturn(notificationsEnabled);
        if (notificationsEnabled) {
            when(s.reminderDaysBeforeLock()).thenReturn(reminderDays);
        }
        return s;
    }
}
