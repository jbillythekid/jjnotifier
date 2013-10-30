package com.atlassian.jira.ext.jabbernotifier.listener;


import com.atlassian.crowd.embedded.api.Group;
import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.config.properties.APKeys;
import com.atlassian.jira.event.issue.AbstractIssueEventListener;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.event.type.EventTypeManager;
import com.atlassian.jira.ext.jabbernotifier.transport.IMTransport;
import com.atlassian.jira.ext.jabbernotifier.transport.IMTransport.IMStatus;
import com.atlassian.jira.ext.jabbernotifier.transport.JabberServerConnectionException;
import com.atlassian.jira.ext.jabbernotifier.transport.JabberTransport;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.changehistory.ChangeHistoryManager;
import com.atlassian.jira.issue.changehistory.ChangeHistory;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.comments.CommentPermissionManager;
import com.atlassian.jira.issue.priority.Priority;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.groups.GroupManager;
import com.atlassian.jira.user.UserPropertyManager;
import com.atlassian.jira.user.UserUtils;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowException;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.velocity.VelocityManager;
import com.opensymphony.module.propertyset.PropertySet;
import org.apache.log4j.Logger;
import org.apache.log4j.NDC;
import org.apache.velocity.exception.VelocityException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JabberNotifierListener extends AbstractIssueEventListener {
    private static final Logger log = Logger.getLogger(JabberNotifierListener.class);

    private static final String DISABLE_JABBER_NOTIFICATIONS_PROPERTY = "disable.jabber.notifications";
    private static final boolean JABBER_NOTIFICATIONS_DISABLED = "true".equalsIgnoreCase(System.getProperty(DISABLE_JABBER_NOTIFICATIONS_PROPERTY, "false"));

    static final String PARAM_SPLIT_REGEX = "(?<!\\\\),"; // split on an unescaped comma. The regexp is a zero-width negative lookbehind assertion.

    static final String IM_ID_PROPERTY = "User's Jabber ID Property";

    static final String TRIGGER_EVENTS = "Events triggering message (default: all events)";
    static final String USERS_TO_NOTIFY = "Users to notify";
    static final String NOTIFIABLE_STATUSES = "Notifiable Statuses (Default: ONLINE,AWAY)";
    private static final String PROJECT_KEYS = "Only notify for issues in projects with these keys (default: all projects)";
    static final String PRIORITIES = "Only notify for issues with these priorities (default: all priorities)";
    static final String WORKFLOW_NAME_REGEXP = "Only notify for issues in workflow (regex on name; default: all workflows)";
    static final String REQUIRED_GROUPS = "Only notify for events generated by user in group (default: any group)";
    static final String IGNORED_GROUPS = "Ignore events generated by users in any of these groups (default: none)";
    static final String IGNORED_ME = "Ignore events generated by my own changes true/false (default: true)";

    private Set<Long> triggerEventIDs;
    protected Set<Long> priorityIDs;
    private List<String> usersToNotify = new ArrayList<String>();
    protected EnumSet<IMStatus> notifiableStatuses;
    protected Set<String> projectKeys;
    private Pattern workflowNameRegex;
    private Set<Group> requiredGroups;
    private Set<Group> ignoredGroups;
    private boolean ignoreMyEvents = true;

    private String imIDProperty = null;

    protected IssueManager issueManager;
    protected PermissionManager permissionManager;
    protected WorkflowManager workflowManager;
    private VelocityManager velocityManager;
    private CommentPermissionManager commentPermissionManager;
    private EventTypeManager eventTypeManager;
    private ConstantsManager constantsManager;
    private GroupManager groupManager;
    private UserPropertyManager userPropertyManager = (UserPropertyManager) ComponentManager.getInstance().getUserPropertyManager();

    protected IMTransport transport;

    private final String tpl = "templates/im/notify.vm";

    final String ASSIGNEE_MATCH_TOKEN = "assignee";
    final String WATCHERS_MATCH_TOKEN = "watchers";

    public JabberNotifierListener() {
        this(ComponentManager.getInstance().getIssueManager(),
                ComponentManager.getInstance().getPermissionManager(),
                ComponentManager.getInstance().getWorkflowManager(),
                ComponentManager.getInstance().getEventTypeManager(),
                ComponentManager.getInstance().getConstantsManager(),
                ComponentManager.getComponentInstanceOfType(GroupManager.class));
    }

    public JabberNotifierListener(IssueManager issueManager, PermissionManager permissionManager, WorkflowManager workflowManager,
                                  EventTypeManager eventTypeManager, ConstantsManager constantsManager,GroupManager groupManager) {
        this.issueManager = issueManager;
        this.permissionManager = permissionManager;
        this.workflowManager = workflowManager;
        this.eventTypeManager = eventTypeManager;
        this.constantsManager = constantsManager;
        this.groupManager = groupManager;
        this.transport = new JabberTransport();
        this.notifiableStatuses = EnumSet.of(IMStatus.ONLINE, IMStatus.AWAY);
    }

    @Override
    public String[] getAcceptedParams() {
        List<String> params = new ArrayList<String>();
        params.addAll(Arrays.asList(transport.getAcceptedParams()));
        params.addAll(Arrays.asList(getAcceptedListenerParams()));
        return params.toArray(new String[params.size()]);
    }

    @Override
    public void init(Map params) {
        velocityManager = ComponentManager.getComponentInstanceOfType(VelocityManager.class);
        commentPermissionManager = ComponentManager.getComponentInstanceOfType(CommentPermissionManager.class);

        if (JABBER_NOTIFICATIONS_DISABLED) {
            log.warn("Jabber notifications are disabled.");
            return;
        }

        // Replace our placeholder transport with the real thing.
        /**
         * Init is called before we set the parameters up skipping
         */
       /* if (params == null || params.isEmpty()) {
            return;
        }*/
        this.transport = JabberTransport.create(params);
        try {
            transport.connect();
        } catch (JabberServerConnectionException e) {
            if (log.isDebugEnabled())
                log.debug(e.getMessage(), e);
            else
                log.error(e.getMessage());
        }
        initListener(params);
    }

    @Override
    public String toString() {
        return "JabberNotifierListener[" + (imIDProperty != null ? "imUser=" + imIDProperty : "") +
                (workflowNameRegex != null ? " regex=" + workflowNameRegex : "") +
                (notifiableStatuses != null ? " notifiableStatuses=" + notifiableStatuses : "") +
                (triggerEventIDs != null ? " eventIDs=" + triggerEventIDs : "") +
                (projectKeys != null ? " projectKeys=" + projectKeys : "") +
                (requiredGroups != null ? " requiredGroups=" + requiredGroups : "") +
                (ignoredGroups != null ? " ignoredGroups=" + ignoredGroups : "") +
                (priorityIDs != null ? " priorityIDs=" + priorityIDs : "") +
                (usersToNotify != null ? " usersToNotify=" + usersToNotify : "") +
                (" JABBER_NOTIFICATIONS_DISABLED=" + JABBER_NOTIFICATIONS_DISABLED + "") +
                "]";
    }

    /**
     * Initialise listener.
     *
     * @param params Parameters entered my administrator. If a param was left blank
     *               we rely on the caller not to pass it through.
     */
    protected void initListener(Map params) {
        if (JABBER_NOTIFICATIONS_DISABLED) {
            if (log.isInfoEnabled())
                log.info("Jabber notifications are disabled.");
            return;
        }

        if (params.containsKey(IM_ID_PROPERTY)) {
            imIDProperty = (String) params.get(IM_ID_PROPERTY);
        }
        if (params.containsKey(WORKFLOW_NAME_REGEXP)) {
            String regexStr = (String) params.get(WORKFLOW_NAME_REGEXP);
            workflowNameRegex = Pattern.compile(regexStr);
        }
        if (params.containsKey(NOTIFIABLE_STATUSES)) {
            String statusesStr = (" " + params.get(NOTIFIABLE_STATUSES)).trim()
                    .toUpperCase();
            EnumSet<IMStatus> statuses = stringToEnumSet(IMStatus.class,
                    statusesStr, "\\b");
            if (statuses.size() != 0) {
                notifiableStatuses = statuses;
            }
        }
        if (params.containsKey(TRIGGER_EVENTS)) {
            String[] eventStrs = ((String) params.get(TRIGGER_EVENTS)).split(PARAM_SPLIT_REGEX); // any
            // unescaped
            // comma
            if (eventStrs.length > 0) triggerEventIDs = new HashSet<Long>();
            for (String id : eventStrs) {
                id = id.trim();
                Long eventId = null;
                try {
                    eventId = Long.parseLong(id);
                } catch (NumberFormatException nfe) {
                    for (Object o : eventTypeManager.getEventTypes()) {
                        EventType e = (EventType) o;
                        if (id.equals(e.getName())) eventId = e.getId();
                    }
                    if (eventId == null)
                        log.error("Jabber listener configured to trigger on event '" + id + "', which is not a valid event ID or name.");
                }
                triggerEventIDs.add(eventId);
            }
        }
        if (params.containsKey(PROJECT_KEYS)) {
            String[] projectKeyStrs = ((String) params.get(PROJECT_KEYS)).split(PARAM_SPLIT_REGEX);
            if (projectKeyStrs.length > 0) projectKeys = new HashSet<String>(projectKeyStrs.length);
            for (String projectStr : projectKeyStrs) {
                projectKeys.add(projectStr.trim());
            }
        }
        if (params.containsKey(REQUIRED_GROUPS)) {
            String[] requiredGroupStrs = ((String) params.get(REQUIRED_GROUPS)).split(PARAM_SPLIT_REGEX);
            if (requiredGroupStrs.length > 0) requiredGroups = new HashSet<Group>(requiredGroupStrs.length);
            for (String groupStr : requiredGroupStrs) {
                groupStr = groupStr.trim();
                Group group = groupManager.getGroupObject(groupStr);
                if (group != null)
                    requiredGroups.add(group);
                else
                    log.error("Unknown required group '" + groupStr + "' in " + this);
            }
        }
        if (params.containsKey(IGNORED_GROUPS)) {
            String[] ignoredGroupsStr = ((String) params.get(IGNORED_GROUPS)).split(PARAM_SPLIT_REGEX);
            if (ignoredGroupsStr.length > 0) ignoredGroups = new HashSet<Group>(ignoredGroupsStr.length);
            for (String groupStr : ignoredGroupsStr) {
                groupStr = groupStr.trim();
                Group group = groupManager.getGroupObject(groupStr);
                if (group != null)
                    ignoredGroups.add(group);
                else
                    log.error("Unknown ignored group '" + groupStr + "' in " + this);
            }
        }

        if (params.containsKey(IGNORED_ME)) {
            // true by default
            ignoreMyEvents = !"false".equals(params.get(IGNORED_ME));
        }

        if (params.containsKey(PRIORITIES)) {
            String[] priorityStrs = ((String) params.get(PRIORITIES)).split(PARAM_SPLIT_REGEX); // any
            // unescaped
            // comma
            if (priorityStrs.length > 0) priorityIDs = new HashSet<Long>();
            for (String id : priorityStrs) {
                id = id.trim();
                Long priorityId = null;
                try {
                    priorityId = Long.parseLong(id);
                } catch (NumberFormatException nfe) {
                    for (Object o : constantsManager.getPriorityObjects()) {
                        Priority p = (Priority) o;
                        if (id.equals(p.getName())) priorityId = Long.parseLong(p.getId());
                    }
                    if (priorityId == null)
                        log.error("Jabber listener configured to trigger on priority '" + id + "', which is not a valid priority ID or name.");
                }
                priorityIDs.add(priorityId);
            }
        }

        if (params.containsKey(USERS_TO_NOTIFY)) {
            String[] usersToNotifyStr = ((String) params.get(USERS_TO_NOTIFY)).split(PARAM_SPLIT_REGEX);
            for (String user : usersToNotifyStr) {
                user = user.trim();
                usersToNotify.add(user);
            }
        }
        if (params.size() > 0 && imIDProperty == null && usersToNotify.size() == 0) {
            log.warn("No static JIDs specified and no JIRA IM property specified; no-one will be notified from this listener.");
        }

        log.info("Initialized listener " + this);
        // No error handling, as this method is only called when the listener is
        // first triggered (not when it is configured)
        // when no user feedback is possible. Listeners suck..
    }

    public String[] getAcceptedListenerParams() {
        return new String[]
                {
                        USERS_TO_NOTIFY, IM_ID_PROPERTY, NOTIFIABLE_STATUSES, TRIGGER_EVENTS, PROJECT_KEYS, PRIORITIES, WORKFLOW_NAME_REGEXP, REQUIRED_GROUPS, IGNORED_GROUPS, IGNORED_ME
                };
    }

    Set<Long> getTriggerEventIDs() {
        return triggerEventIDs;
    }

    @Override
    public void workflowEvent(IssueEvent event) {
        if (JABBER_NOTIFICATIONS_DISABLED) {
            if (log.isInfoEnabled())
                log.info("Jabber notifications are disabled.");
            return;
        }

        final String eventKey = "Evnt:" + event.getUser() + "->" + event.getEventTypeId() + "@" + (event.getIssue() != null ? event.getIssue().getKey() : "");
        NDC.push(eventKey); // Add "(%x)" to your log4j.properties patterns to
        // see this.
        try {
            if (meetsTriggerConditions(event)) {
                Set<Recipient> recipients = getRecipients(usersToNotify, event.getIssue());
                //changed
                log.debug("Event matched conditions. Notifying " + recipients.size() + " users. " + usersToNotify +"");
                for (Recipient recipient : recipients) {
                    if (event.getUser() != null && event.getUser().getName() != null && recipient.getUser() != null) {
                        if (event.getUser().equals(recipient.getUser())) {
                            if (ignoreMyEvents)
                                continue;
                        }
                    }

                    if (hasPermission(event, recipient)) {
                        final IMStatus imStatus = transport.getContactStatus(recipient.getJabberId());
                        if (isStatusNotifiable(imStatus)) {
                            log.debug("\tNotifying " + recipient);
                            String msg = getTemplatedMsg(event, recipient.getUser());
                            transport.sendMessage(recipient.getJabberId(), msg);
                        } else {
                            log.debug("\tUser " + recipient + " is unavailable (status " + imStatus + ")");
                        }
                    } else {
                        log.info("\tUser " + recipient + " does not have permission to see (comment on) event " + event);
                    }
                }
            }
        } catch (JabberServerConnectionException e) {
            if (log.isDebugEnabled())
                log.debug(e.getMessage(), e);
            else
                log.error(e.getMessage());
        } finally {
            NDC.pop();
        }
    }

    /**
     * @return Whether the recipient has permission to see the triggered event.
     */
    private boolean hasPermission(IssueEvent event, Recipient recipient) {
        return event.getComment() == null || recipient != null && commentPermissionManager.hasBrowsePermission(event.getUser(), event.getComment());
    }

    protected boolean meetsTriggerConditions(IssueEvent event) {
        if (JABBER_NOTIFICATIONS_DISABLED) {
            if (log.isDebugEnabled())
                log.debug("Jabber notifications are disabled.");
            return false;
        }

        if (triggerEventIDs != null && !triggerEventIDs.contains(event.getEventTypeId())) return false;
        if (triggerEventIDs != null) log.debug("Event matches eventIds " + triggerEventIDs);
        if (projectKeys != null) {
            if (!projectKeys.contains(event.getIssue().getProjectObject().getKey())) {
                log.debug("Event not from projects " + projectKeys);
                return false;
            } else
                log.debug("Event from project(s) " + projectKeys);
        }

        if ("workflow".equals(event.getParams().get("eventsource"))) {
            try {
                if (workflowNameRegex != null) {
                    Issue issue = event.getIssue();
                    JiraWorkflow workflow = workflowManager.getWorkflow(issue);
                    Matcher m = workflowNameRegex.matcher(workflow.getName());
                    if (!m.matches()) {
                        log.debug("Issue " + issue + "'s workflow name does not match regexp " + workflowNameRegex + "; not notifying anyone.");
                        return false;
                    }
                    log.debug("Issue " + issue + " in workflow with regexp " + workflowNameRegex);
                }
            } catch (WorkflowException e) {
                log.error("Problem getting workflow for issue " + event.getIssue() + ": " + e, e);
                return false;
            }
        }
        if (priorityIDs != null) {
            boolean matchesPriority = false;
            Issue issue = event.getIssue();
            Priority issuePriority = issue.getPriorityObject();
            if (issuePriority != null) {
                for (Long priorityId : priorityIDs) {
                    matchesPriority |= priorityId.toString().equals(issuePriority.getId());
                }
                if (!matchesPriority) {
                    log.debug("Issue priority " + issuePriority.getId() + " not in " + priorityIDs + "; not notifying.");
                    return false;
                } else {
                    log.debug("Issue matched priority " + priorityIDs);
                }
            } else {
                log.debug("Issue has no priority; not matching " + priorityIDs);
            }
        }
        User currentUser = event.getUser();
        if (currentUser != null) { // ie. not an anonymous comment/transition/whatever
            if (requiredGroups != null) {
                boolean inRequiredGroup = false;
                for (Group group : requiredGroups) {
                    inRequiredGroup |= groupManager.isUserInGroup(currentUser, group);
                }
                if (!inRequiredGroup) {
                    log.debug("User " + currentUser + " not in any of the required groups " + requiredGroups);
                    return false;
                } else {
                    log.debug("User " + currentUser + " in all required groups");
                }
            }
            if (ignoredGroups != null) {
                for (Group group : ignoredGroups) {
                    if (groupManager.isUserInGroup(currentUser, group)) {
                        log.debug("User " + currentUser + " in ignored group " + group + "; not notifying.");
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Given an issue, returns a list of watchers
     *
     * @param issue The current issue (used to determine who's in role
     *              'watchers').
     * @return A list of watchers of type User.
     */
    protected List<User> getWatchers(Issue issue) {
        List<User> myUsers = new ArrayList<User>();
        if (issue != null) {
            try {
                myUsers = issueManager.getWatchers(issue);

            } catch (Exception e) {
                log.debug("Failed to determine watchers from issue: " + e.toString());
            }
        }
        return myUsers;
    }

    /**
     * Given a user-entered list of strings denoting recipients, calculate an
     * actual list of IM recipients.
     *
     * @param users A list of usernames and/or user roles like "assignee".
     * @param issue The current issue (used to determine who's in role
     *              'assignee').
     * @return A set of Recipients.
     */
    private Set<Recipient> getRecipients(List<String> users, Issue issue) {
        Set<Recipient> recipients = new HashSet<Recipient>(users.size());
        for (String userStr : users) {
            if (ASSIGNEE_MATCH_TOKEN.equals(userStr)) {
                String jid = getJIDForUser(issue.getAssignee());
                if (jid != null) recipients.add(new Recipient(jid, issue.getAssignee()));
            } else if (WATCHERS_MATCH_TOKEN.equals(userStr)) {
                for (User watcherUser : getWatchers(issue)) {
                    String jid = getJIDForUser(watcherUser);
                    if (jid != null) {
                        log.debug("Adding Watcher to Receipients: " + jid);
                        recipients.add(new Recipient(jid, issue.getAssignee()));
                    }
                }
            } else if (UserUtils.userExists(userStr)) {
                final User user = UserUtils.getUser(userStr);
                String jid = getJIDForUser(user);
                if (jid != null) {
                    recipients.add(new Recipient(jid, user));
                } else {
                    log.error("Couldn't find user " + userStr);
                }

            } else if (userStr.indexOf("@") > -1) {
                recipients.add(new Recipient(userStr, null));
            } else {
                log.warn("Unknown Jabber recipient: " + userStr + "; ignoring.");
            }
        }
        return recipients;
    }

    /**
     * Look up a user's jabber ID. Currently looks in a JIRA user property (can
     * be set by admins).
     *
     * @param user User object.
     * @return Eg. "joe@chat.atlassian.com", or null if the passed user is null
     *         or has no jabber ID set.
     */
    protected String getJIDForUser(User user) {
        if (user == null) return null;
        if (getImIDProperty() == null) return null;
        String prop = "jira.meta." + getImIDProperty();
        PropertySet propertySet = userPropertyManager.getPropertySet(user);
        if (propertySet.exists(prop)) {
            return propertySet.getString(prop);
        } else
            return null;
    }

    protected boolean isStatusNotifiable(IMStatus status) {
        return notifiableStatuses.contains(status);
    }

    @Override
    public boolean isInternal() {
        return false;
    }

    @Override
    public boolean isUnique() {
        return false;
    }

    @Override
    public String getDescription() {
        return "Sends notifications about issue updates via XMPP/Jabber. Parameters are as follows. Note that commas can be escaped with \\." +
                " Note that any parameter with a 'default' can be left blank to have that default take effect.<ul>\n" +
                "<li><b>XMPP Server, Login, Password, Port</b> - The Jabber server to communicate through, and the " +
                "Jabber user to connect as (notifications will appear to come from this user). Do not include '@..' in XMPP Login. Leave the port blank to use the default." +
                "<li><b>" + USERS_TO_NOTIFY + "</b> - Comma-separated list of which users to notify when the listener's conditions all match. Values can be:<ul>" +
                "    <li>A straightforward jabber ID, eg. jefft@chat.atlassian.com" +
                "    <li>A JIRA username, in which case the user's Jabber ID is looked up in a User Property (whose key is set with the parameter below)." +
                "    <li>An issue role. Currently only '" + ASSIGNEE_MATCH_TOKEN + "' and '" + WATCHERS_MATCH_TOKEN + "' is recognised, and evaluates to the jabber " +
                "ID associated (via a property - see next parameter) with the issue current assignee." + "" +
                "</ul>" +
                "<li><b>" + IM_ID_PROPERTY + "</b> - The listener determines the jabber ID of JIRA users by looking for the " +
                "User Property with this name. The User Property for each user can be set for each user by administrators." +
                "<li><b>" + NOTIFIABLE_STATUSES + "</b> - Notify jabber users if their IM client is in one of these statuses." +
                "<li><b>" + TRIGGER_EVENTS + "</b> - comma-separated list of names or IDs of JIRA events to <b>trigger</b> on. Valid events are:" +
                "<table border=1>" +
                "<tr><th>Event ID</th><th>Event name</th></tr>" +
                getEventsTable() +
                "</table>" +
                "<li><b>" + PROJECT_KEYS + "</b> - Comma-separated list of project keys (the project key is eg. the 'ABC' in issue key ABC-123). " +
                "Only events on issues in these projects will trigger a notification." +
                "<li><b>" + PRIORITIES + "</b> - Comma-separated list of priorities (IDs or names). " +
                "Only events on issues in these priorities will trigger a notification. Currently defined priorities are:" +
                "<table border=1" +
                "<tr><th>Priority ID</th><th>Priority name</th></tr>" +
                getPrioritiesTable() +
                "</table>" +
                "<li><b>" + WORKFLOW_NAME_REGEXP + "</b> - Only trigger for actions on issues in a JIRA workflow whose name matches this regular expression. " +
                "If left blank, any workflow matches. The intention here is that workflow names form a series ('Support Workflow v1', 'Support Workflow v2', ..)" +
                "so the regexp 'Support Workflow v.*' would match any in the series." +
                "<li><b>" + REQUIRED_GROUPS + "</b> - Only trigger if the user performing the action is in one of these groups. " +
                "Eg. only trigger if user is in 'customers' group." +
                "<li><b>" + IGNORED_GROUPS + "</b> - Only trigger if the user performing the action <em>isn't</em> in one of these groups." +
                "For instance if staff are in the 'staff' group and we're not interested in staff-generated events (only customer-triggered events), " +
                "then add 'staff' here." +
                "<li><b>" + IGNORED_ME + "</b> - Only trigger if the user performing the action <em>isn't</em> the user being notified. Avoids people being notified on their own changes.";
    }

    private String getEventsTable() {
        StringBuffer buf = new StringBuffer();
        for (Object o : ComponentManager.getInstance().getEventTypeManager().getEventTypes()) {
            EventType e = (EventType) o;
            buf.append("<tr><td>" + e.getId() + "</td><td>" + e.getName() + "</td></tr>");
        }
        return buf.toString();
    }

    private String getPrioritiesTable() {
        StringBuffer buf = new StringBuffer();
        for (Object o : ComponentManager.getInstance().getConstantsManager().getPriorityObjects()) {
            Priority p = (Priority) o;
            buf.append("<tr><td>" + p.getId() + "</td><td>" + p.getName() + "</td></tr>");
        }
        return buf.toString();
    }

    /**
     * Gets the instant message's text.
     *
     * @param event     JIRA trigger event.
     * @param recipient Recipient of the IM. Possibly null.
     * @return The message text.
     */
    protected String getTemplatedMsg(IssueEvent event, User recipient) {
        Issue issue = event.getIssue();
        String result = "";
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("issue", issue);
        params.put("event", event);
        final EventType eventType = ComponentManager.getInstance().getEventTypeManager().getEventType(event.getEventTypeId());
        String desc = eventType != null ? eventType.getTranslatedName(recipient) : null;
        params.put("desc", desc);
        params.put("comment", event.getComment());
        String base_url = ComponentManager.getInstance()
                .getApplicationProperties().getString(APKeys.JIRA_BASEURL);
        params.put("base_url", base_url);
        try {
            result = velocityManager.getBody("", tpl, params);
        } catch (VelocityException e) {
            log.error("Error rendering Jabber notification", e);
        }
        return result;
    }

    protected <E extends Enum<E>> EnumSet<E> stringToEnumSet(Class<E> clazz, String str, String regex) {
        EnumSet<E> result = EnumSet.noneOf(clazz);
        result.clear();
        String[] values = str.split(regex);
        for (String value : values) {
            try {
                result.add(Enum.valueOf(clazz, value.trim()));
            } catch (IllegalArgumentException e) {
                continue;
            }
        }
        return result;
    }

    List<String> getUsersToNotify() {
        return usersToNotify;
    }

    EnumSet<IMStatus> getNotifiableStatuses() {
        return notifiableStatuses;
    }

    Pattern getWorkflowNameRegex() {
        return workflowNameRegex;
    }

    Set<Group> getRequiredGroups() {
        return requiredGroups;
    }

    Set<Group> getIgnoredGroups() {
        return ignoredGroups;
    }

    String getImIDProperty() {
        return imIDProperty;
    }

    /**
     * Recipient of a Jabber IM ping.
     */
    private class Recipient {
        private final String jabberId;
        private final User user;

        /**
         * @param jabberId Non-null Jabber ID, eg. "jefft@chat.atlassian.com"
         * @param user     Possibly null User object.
         */
        public Recipient(String jabberId, User user) {
            this.jabberId = jabberId;
            this.user = user;
        }

        public String getJabberId() {
            return jabberId;
        }

        /**
         * @return User receiving the IM, or null if unavailable.
         */
        public User getUser() {
            return user;
        }

        @Override
        public String toString() {
            return jabberId;
        }
    }
}
