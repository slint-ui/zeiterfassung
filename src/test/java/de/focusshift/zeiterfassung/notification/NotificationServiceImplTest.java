package de.focusshift.zeiterfassung.notification;

import de.focusshift.zeiterfassung.email.EMailService;
import de.focusshift.zeiterfassung.settings.LockTimeEntriesSettings;
import de.focusshift.zeiterfassung.tenancy.tenant.TenantContextRunner;
import de.focusshift.zeiterfassung.tenancy.user.EMailAddress;
import de.focusshift.zeiterfassung.timeentry.ShouldWorkingHours;
import de.focusshift.zeiterfassung.timeentry.TimeEntry;
import de.focusshift.zeiterfassung.timeentry.TimeEntryDay;
import de.focusshift.zeiterfassung.timeentry.TimeEntryDayService;
import de.focusshift.zeiterfassung.timeentry.TimeEntryId;
import de.focusshift.zeiterfassung.timeentry.TimeEntryLockService;
import de.focusshift.zeiterfassung.timeentry.TimeEntryWeek;
import de.focusshift.zeiterfassung.timeentry.TimeEntryWeekPage;
import de.focusshift.zeiterfassung.user.UserIdComposite;
import de.focusshift.zeiterfassung.user.UserId;
import de.focusshift.zeiterfassung.user.UserSettings;
import de.focusshift.zeiterfassung.user.UserSettingsService;
import de.focusshift.zeiterfassung.usermanagement.User;
import de.focusshift.zeiterfassung.usermanagement.UserLocalId;
import de.focusshift.zeiterfassung.usermanagement.UserManagementService;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
        when(tenantContextRunner.runForEachActiveTenant(any(Runnable.class)))
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

            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 5),
                ShouldWorkingHours.ZERO, List.of(), List.of());

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

            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 5),
                ShouldWorkingHours.EIGHT, List.of(mock(Object.class)), List.of());

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

            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 5),
                ShouldWorkingHours.EIGHT, List.of(), List.of(entry(false)));

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

            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 5),
                ShouldWorkingHours.EIGHT, List.of(), List.of());
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

            stubDayService(new UserLocalId(1L), LocalDate.of(2025, 6, 4),
                ShouldWorkingHours.EIGHT, List.of(), List.of());
            stubDayService(new UserLocalId(2L), LocalDate.of(2025, 6, 2),
                ShouldWorkingHours.EIGHT, List.of(), List.of());
            when(mailTemplateEngine.process(any(String.class), any(IContext.class))).thenReturn("body");

            sut.sendLockWarnings();

            ArgumentCaptor<Context> ctxCaptor = ArgumentCaptor.forClass(Context.class);
            verify(mailTemplateEngine, times(2)).process(any(String.class), ctxCaptor.capture());
            assertThat(ctxCaptor.getAllValues())
                .extracting(ctx -> ctx.getVariable("daysUntilLock"))
                .containsExactlyInAnyOrder(3, 1);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubDayService(UserLocalId userLocalId, LocalDate date,
                                 ShouldWorkingHours shouldWork, List<?> absences, List<TimeEntry> entries) {
        TimeEntryDay day = mock(TimeEntryDay.class);
        when(day.date()).thenReturn(date);
        when(day.shouldWorkingHours()).thenReturn(shouldWork);
        lenient().when(day.absences()).thenReturn((List) absences);
        lenient().when(day.timeEntries()).thenReturn(entries);

        TimeEntryWeek week = mock(TimeEntryWeek.class);
        when(week.days()).thenReturn(List.of(day));
        TimeEntryWeekPage page = new TimeEntryWeekPage(week, 0L);
        when(timeEntryDayService.getEntryWeekPage(eq(userLocalId), anyInt(), anyInt())).thenReturn(page);
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

    private static TimeEntry entry(boolean isBreak) {
        return new TimeEntry(new TimeEntryId(1L), compositeFor(1L), "",
            ZonedDateTime.now(ZoneOffset.UTC), ZonedDateTime.now(ZoneOffset.UTC).plusHours(1),
            isBreak, null, null);
    }
}
