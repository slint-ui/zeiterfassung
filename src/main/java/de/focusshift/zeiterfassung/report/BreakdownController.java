package de.focusshift.zeiterfassung.report;

import de.focus_shift.launchpad.api.HasLaunchpad;
import de.focusshift.zeiterfassung.report.BreakdownService.ActivityTypeBreakdown;
import de.focusshift.zeiterfassung.report.BreakdownService.BreakdownResult;
import de.focusshift.zeiterfassung.report.BreakdownService.CustomerBreakdown;
import de.focusshift.zeiterfassung.report.BreakdownService.ProjectBreakdown;
import de.focusshift.zeiterfassung.report.BreakdownService.UserContribution;
import de.focusshift.zeiterfassung.search.HasUserSearch;
import de.focusshift.zeiterfassung.search.UserSearchViewHelper;
import de.focusshift.zeiterfassung.security.CurrentUser;
import de.focusshift.zeiterfassung.security.oidc.CurrentOidcUser;
import de.focusshift.zeiterfassung.tenancy.tenant.TenantContextHolder;
import de.focusshift.zeiterfassung.timeclock.HasTimeClock;
import de.focusshift.zeiterfassung.usermanagement.User;
import de.focusshift.zeiterfassung.usermanagement.UserLocalId;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static de.focusshift.zeiterfassung.search.UserSearchViewHelper.USER_SEARCH_QUERY_PARAM;
import static de.focusshift.zeiterfassung.web.HotwiredTurboConstants.TURBO_FRAME_HEADER;
import static java.util.stream.Collectors.toMap;

@Controller
@RequestMapping("/report/breakdown")
class BreakdownController implements HasTimeClock, HasLaunchpad, HasUserSearch {

    record UserContributionDto(String userName, String hours) {}

    record ProjectBreakdownDto(String projectName, String hours, int percent, List<UserContributionDto> byUser) {}

    record CustomerBreakdownDto(String customerName, String hours, int percent, List<ProjectBreakdownDto> projects) {}

    record ActivityTypeBreakdownDto(String name, String hours, int percent) {}

    record BreakdownDto(
        List<CustomerBreakdownDto> byCustomer,
        List<ActivityTypeBreakdownDto> byActivityType,
        String totalHours,
        boolean hasData
    ) {}

    private final BreakdownService breakdownService;
    private final ReportPermissionService reportPermissionService;
    private final ReportViewHelper reportViewHelper;
    private final UserSearchViewHelper userSearchViewHelper;
    private final TenantContextHolder tenantContextHolder;
    private final Clock clock;

    BreakdownController(BreakdownService breakdownService,
                        ReportPermissionService reportPermissionService,
                        ReportViewHelper reportViewHelper,
                        UserSearchViewHelper userSearchViewHelper,
                        TenantContextHolder tenantContextHolder,
                        Clock clock) {
        this.breakdownService = breakdownService;
        this.reportPermissionService = reportPermissionService;
        this.reportViewHelper = reportViewHelper;
        this.userSearchViewHelper = userSearchViewHelper;
        this.tenantContextHolder = tenantContextHolder;
        this.clock = clock;
    }

    @GetMapping
    ModelAndView breakdown(
        @RequestParam(required = false, defaultValue = "month") String preset,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to,
        @RequestParam(value = "user", required = false) List<Long> userLocalIdValues,
        @RequestParam(value = "everyone", required = false) String allUsersSelectedParam,
        Model model, @CurrentUser CurrentOidcUser currentUser
    ) {
        final LocalDate today = LocalDate.now(clock);
        final LocalDate[] range = resolveDateRange(preset, from, to, today);
        final LocalDate rangeFrom = range[0];
        final LocalDate rangeToInclusive = range[1];

        final boolean allUsersSelected = allUsersSelectedParam != null;
        final List<User> allUsers = reportPermissionService.findAllPermittedUsersForCurrentUser();
        final List<UserLocalId> selectedIds = resolveSelectedIds(allUsers, userLocalIdValues, allUsersSelected);
        final Map<UserLocalId, String> userNames = allUsers.stream().collect(toMap(User::userLocalId, User::fullName));

        final BreakdownResult result = breakdownService.breakdown(rangeFrom, rangeToInclusive.plusDays(1), selectedIds, userNames);

        model.addAttribute("breakdown", toDto(result));
        model.addAttribute("from", rangeFrom);
        model.addAttribute("to", rangeToInclusive);
        model.addAttribute("preset", preset);
        model.addAttribute("selectedUserIds", userLocalIdValues == null ? List.of() : userLocalIdValues);
        model.addAttribute("canViewAllUsers", reportPermissionService.currentUserHasPermissionForAllUsers());

        final String baseUrl = "custom".equals(preset)
            ? "/report/breakdown?preset=custom&from=" + rangeFrom + "&to=" + rangeToInclusive
            : "/report/breakdown?preset=" + preset;
        reportViewHelper.addUserFilterModelAttributes(model, allUsersSelected, allUsers, selectedIds, baseUrl);

        model.addAttribute("printUrl", buildPrintUrl(preset, rangeFrom, rangeToInclusive, userLocalIdValues, allUsersSelected));

        // Tab state
        model.addAttribute("weekAriaCurrent", "false");
        model.addAttribute("monthAriaCurrent", "false");
        model.addAttribute("breakdownAriaCurrent", "location");

        // Override the chart+entries section with breakdown content
        model.addAttribute("chartNavigationFragment", "reports/breakdown::chart-navigation");
        model.addAttribute("chartFragment", "reports/breakdown::empty");
        model.addAttribute("entriesFragment", "reports/breakdown::empty");
        model.addAttribute("overrideContentFragment", "reports/breakdown::content");

        return new ModelAndView("reports/user-report");
    }

    @GetMapping("/print")
    ModelAndView print(
        @RequestParam(required = false, defaultValue = "month") String preset,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to,
        @RequestParam(value = "user", required = false) List<Long> userLocalIdValues,
        @RequestParam(value = "everyone", required = false) String allUsersSelectedParam,
        @RequestParam(value = "showUsers", required = false, defaultValue = "false") boolean showUsers,
        @RequestParam(value = "showActivity", required = false, defaultValue = "true") boolean showActivity,
        @RequestParam(value = "customer", required = false) List<String> selectedCustomers,
        Model model, @CurrentUser CurrentOidcUser currentUser
    ) {
        final LocalDate today = LocalDate.now(clock);
        final LocalDate[] range = resolveDateRange(preset, from, to, today);
        final LocalDate rangeFrom = range[0];
        final LocalDate rangeToInclusive = range[1];

        final boolean allUsersSelected = allUsersSelectedParam != null;
        final List<User> allUsers = reportPermissionService.findAllPermittedUsersForCurrentUser();
        final List<UserLocalId> selectedIds = resolveSelectedIds(allUsers, userLocalIdValues, allUsersSelected);
        final Map<UserLocalId, String> userNames = allUsers.stream().collect(toMap(User::userLocalId, User::fullName));

        final BreakdownResult result = breakdownService.breakdown(rangeFrom, rangeToInclusive.plusDays(1), selectedIds, userNames);

        final String companyName = tenantContextHolder.getCurrentTenantId()
            .map(tid -> formatTenantId(tid.tenantId()))
            .orElse("Time Report");

        final BreakdownDto fullDto = toDto(result);
        final boolean filteringCustomers = selectedCustomers != null && !selectedCustomers.isEmpty();
        final BreakdownDto filteredDto = filteringCustomers
            ? new BreakdownDto(
                fullDto.byCustomer().stream()
                    .filter(c -> selectedCustomers.contains(c.customerName()))
                    .toList(),
                fullDto.byActivityType(),
                fullDto.totalHours(),
                fullDto.hasData()
              )
            : fullDto;

        model.addAttribute("breakdown", filteredDto);
        model.addAttribute("allCustomers", fullDto.byCustomer().stream().map(CustomerBreakdownDto::customerName).toList());
        model.addAttribute("selectedCustomers", selectedCustomers == null ? List.of() : selectedCustomers);
        model.addAttribute("from", rangeFrom);
        model.addAttribute("to", rangeToInclusive);
        model.addAttribute("preset", preset);
        model.addAttribute("userLocalIdValues", userLocalIdValues == null ? List.of() : userLocalIdValues);
        model.addAttribute("allUsersSelected", allUsersSelected);
        model.addAttribute("companyName", companyName);
        model.addAttribute("showUsers", showUsers);
        model.addAttribute("showActivity", showActivity);

        return new ModelAndView("reports/breakdown-print");
    }

    @GetMapping(params = USER_SEARCH_QUERY_PARAM, headers = TURBO_FRAME_HEADER)
    ModelAndView userSearchFragment(@RequestParam(USER_SEARCH_QUERY_PARAM) String query,
                                    @CurrentUser CurrentOidcUser currentUser, Model model) {
        return userSearchViewHelper.getSuggestionFragment(query, currentUser, model,
            suggestion -> "/report/breakdown?user=%s".formatted(suggestion.userLocalId().value())
        );
    }

    private LocalDate[] resolveDateRange(String preset, LocalDate from, LocalDate to, LocalDate today) {
        return switch (preset) {
            case "week" -> new LocalDate[]{today.with(DayOfWeek.MONDAY), today.with(DayOfWeek.MONDAY).plusDays(6)};
            case "last-week" -> {
                final LocalDate monday = today.with(DayOfWeek.MONDAY).minusWeeks(1);
                yield new LocalDate[]{monday, monday.plusDays(6)};
            }
            case "last-month" -> {
                final LocalDate first = today.minusMonths(1).withDayOfMonth(1);
                yield new LocalDate[]{first, first.withDayOfMonth(first.lengthOfMonth())};
            }
            case "last-30" -> new LocalDate[]{today.minusDays(29), today};
            case "custom" -> new LocalDate[]{
                from != null ? from : today.withDayOfMonth(1),
                to != null ? to : today
            };
            default -> new LocalDate[]{today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth())};
        };
    }

    private List<UserLocalId> resolveSelectedIds(List<User> allUsers, List<Long> userLocalIdValues, boolean allUsersSelected) {
        final List<UserLocalId> allPermittedIds = allUsers.stream().map(User::userLocalId).toList();
        if (allUsersSelected || userLocalIdValues == null || userLocalIdValues.isEmpty()) {
            return allPermittedIds;
        }
        return userLocalIdValues.stream()
            .map(UserLocalId::new)
            .filter(allPermittedIds::contains)
            .toList();
    }

    private String buildPrintUrl(String preset, LocalDate rangeFrom, LocalDate rangeToInclusive,
                                 List<Long> userLocalIdValues, boolean allUsersSelected) {
        final StringBuilder sb = new StringBuilder("/report/breakdown/print?preset=");
        sb.append(preset);
        if ("custom".equals(preset)) {
            sb.append("&from=").append(rangeFrom).append("&to=").append(rangeToInclusive);
        }
        if (allUsersSelected) {
            sb.append("&everyone=");
        } else if (userLocalIdValues != null) {
            for (Long id : userLocalIdValues) {
                sb.append("&user=").append(id);
            }
        }
        return sb.toString();
    }

    private static String formatTenantId(String tenantId) {
        return Arrays.stream(tenantId.split("[-_]"))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .collect(Collectors.joining(" "));
    }

    private BreakdownDto toDto(BreakdownResult result) {
        final Duration total = result.total();
        final String totalHours = formatDuration(total);

        final List<CustomerBreakdownDto> customers = result.byCustomer().stream()
            .map(c -> {
                final List<ProjectBreakdownDto> projects = c.projects().stream()
                    .map(p -> {
                        final List<UserContributionDto> byUser = p.byUser().stream()
                            .map(u -> new UserContributionDto(u.userName(), formatDuration(u.duration())))
                            .toList();
                        return new ProjectBreakdownDto(p.projectName(), formatDuration(p.duration()), percent(p.duration(), total), byUser);
                    })
                    .toList();
                return new CustomerBreakdownDto(c.customerName(), formatDuration(c.duration()), percent(c.duration(), total), projects);
            })
            .toList();

        final List<ActivityTypeBreakdownDto> activities = result.byActivityType().stream()
            .map(a -> new ActivityTypeBreakdownDto(a.name(), formatDuration(a.duration()), percent(a.duration(), total)))
            .toList();

        return new BreakdownDto(customers, activities, totalHours, !result.isEmpty());
    }

    private static int percent(Duration part, Duration total) {
        if (total.isZero()) return 0;
        return (int) Math.round(part.toMinutes() * 100.0 / total.toMinutes());
    }

    private static String formatDuration(Duration duration) {
        return "%02d:%02d".formatted(Math.abs(duration.toHours()), Math.abs(duration.toMinutesPart()));
    }
}
