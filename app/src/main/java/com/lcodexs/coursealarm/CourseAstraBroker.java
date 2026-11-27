package com.lcodexs.coursealarm;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;

import com.astraisland.sdk.Capsule;
import com.astraisland.sdk.CapsuleTrailing;
import com.astraisland.sdk.DismissPolicy;
import com.astraisland.sdk.EndReason;
import com.astraisland.sdk.GenericCard;
import com.astraisland.sdk.IslandActivity;
import com.astraisland.sdk.IslandCallback;
import com.astraisland.sdk.IslandCard;
import com.astraisland.sdk.IslandClient;
import com.astraisland.sdk.IslandResult;
import com.astraisland.sdk.ProgressCard;
import com.astraisland.sdk.TextButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Runs only in CourseAlarm, after the provider checks the caller UID. */
final class CourseAstraBroker {
    private static final String TAG = "CourseAstraIsland";
    private static final String DND = "course_dnd_toggle";
    private static final Pattern ID = Pattern.compile(Pattern.quote(CourseAstraIsland.PREFIX) + "[A-Za-z0-9_-]{43}");
    private static final Map<String, Session> active = new HashMap<>();
    private static final Map<String, Long> pendingEnds = new HashMap<>();
    private static final Map<String, Long> dismissed = new HashMap<>();
    private static final Handler clock = new Handler(Looper.getMainLooper());
    private static IslandClient client;

    private record Course(String title, String metadata, long start, long end, long expires,
                          boolean showProgress, CourseBreaks breaks, CourseAstraStyle style, PendingIntent open, PendingIntent dnd,
                          boolean dndActive, boolean reserved, PendingIntent dismiss) { }

    private static final class Session {
        Course course;
        long updated, retryAt;
        boolean expanded;
        Session(Course course) { this.course = course; }
    }

    private static final Runnable TICK = CourseAstraBroker::tick;

    private CourseAstraBroker() { }

    static synchronized Bundle dispatch(Context context, String method, Bundle request) {
        String id = request == null ? null : request.getString("id");
        if (id == null || !ID.matcher(id).matches()) return result(CourseAstraIsland.UNAVAILABLE);
        try {
            if (CourseAstraIsland.END.equals(method)) {
                end(id);
                if (pendingEnds.containsKey(id)) ensureClient(context);
                schedule();
                return result(CourseAstraIsland.ACCEPTED);
            }
            long start = request.getLong("start"), end = request.getLong("end"), expires = request.getLong("expires");
            long now = System.currentTimeMillis();
            dismissed.values().removeIf(until -> until <= now);
            if (dismissed.containsKey(id)) return result(CourseAstraIsland.DISMISSED);
            if (start <= 0 || end <= start || end - start > 2L * 86400000L || expires < end
                    || expires - end > 600000L || expires <= now || expires - now > 3L * 86400000L)
                return result(CourseAstraIsland.UNAVAILABLE);
            CourseIslandWake.replace(context, request.getLongArray("islandDeadlines"), request.getString("validationToken"));
            String status = ensureClient(context);
            if (!CourseAstraIsland.ACCEPTED.equals(status) && !CourseAstraIsland.CONNECTING.equals(status))
                return result(status);
            String title = CourseAstraIsland.limit(request.getString("title", "课程"), 128);
            Course course = new Course(title.isBlank() ? "课程" : title, metadata(request, start, end),
                    start, end, expires, request.getBoolean("showProgress"), CourseBreaks.decode(start, end, request.getLongArray("breaksV1")),
                    CourseAstraStyle.fromId(request.getString("style")),
                    pending(request, "open", false), pending(request, "dnd", true),
                    request.getBoolean("dndActive"), request.getBoolean("reserved"), pending(request, "dismiss", false));
            Session previous = active.get(id);
            if (CourseAstraIsland.CONNECTING.equals(status)) {
                // READY can arrive just after this provider call. Keep the validated payload
                // until then, without requiring Calendar to start our process a second time.
                Session waiting = previous == null ? new Session(course) : previous;
                waiting.course = course; waiting.retryAt = course.expires();
                pendingEnds.remove(id); active.put(id, waiting); schedule();
                return result(CourseAstraIsland.CONNECTING);
            }
            boolean first = !client.listMine().contains(id);
            if (!first && previous != null && previous.course.equals(course) && now < nextUpdate(previous)
                    && previous.retryAt == 0) return result(CourseAstraIsland.ACCEPTED);
            IslandResult response = publish(id, course, now, first);
            if (!response.getAccepted()) {
                Log.w(TAG, "course activity rejected: " + response);
                return result(response == IslandResult.NOT_CONNECTED || response == IslandResult.RATE_LIMITED
                        || response == IslandResult.BUSY || response == IslandResult.QUOTA_EXCEEDED
                        ? CourseAstraIsland.CONNECTING : CourseAstraIsland.UNAVAILABLE);
            }
            pendingEnds.remove(id);
            Session session = previous == null ? new Session(course) : previous;
            session.course = course; session.updated = now; session.retryAt = 0; active.put(id, session);
            schedule();
            return result(CourseAstraIsland.ACCEPTED);
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "SDK call failed", error);
            return result(CourseAstraIsland.UNAVAILABLE);
        }
    }

    private static String ensureClient(Context context) {
        if (client == null || client.getState() == IslandClient.State.NOT_INSTALLED) {
            IslandClient next = new IslandClient(context.getApplicationContext());
            if (!next.isHostInstalled()) return CourseAstraIsland.MISSING;
            client = next;
            next.setCallback(new IslandCallback() {
                @Override public void onStateChanged(IslandClient.State state) {
                    if (state == IslandClient.State.READY) clock.post(() -> reconnected(next));
                }
                @Override public void onAction(String activityId, String actionId) {
                    if (DND.equals(actionId)) sendAction(activityId, false);
                }
                @Override public void onExpanded(String activityId) { expanded(activityId, true); }
                @Override public void onCollapsed(String activityId) { expanded(activityId, false); }
                @Override public void onDismissed(String activityId) {
                    synchronized (CourseAstraBroker.class) {
                        Session s = active.get(activityId);
                        if (client != next || s == null) return;
                        dismissed.put(activityId, s.course.expires());
                        sendAction(activityId, true);
                        end(activityId);
                        schedule();
                    }
                }
                @Override public void onEnded(String activityId, EndReason reason) {
                    synchronized (CourseAstraBroker.class) {
                        if (client != next) return;
                        if (pendingEnds.remove(activityId) == null && active.containsKey(activityId)) {
                            Session s = active.get(activityId);
                            // Both REMOVED (system) and EXPIRED can precede our deadline.
                            // The separate onDismissed callback is the only user dismissal.
                            if (System.currentTimeMillis() < s.course.expires()) s.retryAt = System.currentTimeMillis() + 1500;
                            else active.remove(activityId);
                        }
                        schedule();
                    }
                }
            });
            next.connect();
        }
        return client.isReady() ? CourseAstraIsland.ACCEPTED : client.getState() == IslandClient.State.REJECTED
                ? CourseAstraIsland.UNAVAILABLE : CourseAstraIsland.CONNECTING;
    }

    private static IslandResult publish(String id, Course course, long now, boolean first) {
        String subtitle = course.showProgress() ? course.breaks().timing(course.start(), course.end(), now)
                : CourseOccurrence.timing(course.start(), course.end(), now);
        String start = timeLabel(course.start()), end = timeLabel(course.end());
        String details = joinDetails(start + "–" + end, course.metadata());
        int progress = course.showProgress() && now >= course.start()
                ? CourseOccurrence.progress(course.start(), course.end(), now) : -1;
        float fraction = progress / 10000f;
        boolean activeDnd = course.dndActive() && now < course.end();
        String body = progress >= 0 ? course.metadata() : details;
        if (activeDnd) body = body.isEmpty() ? "免打扰已开启" : body + "\n免打扰已开启";
        TextButton button = activeDnd || course.dnd() == null || now >= course.end() || (course.reserved() && now >= course.start())
                ? null : new TextButton(DND, CourseDndClient.label(false, course.reserved(), now < course.start()));
        IslandCard card;
        if (progress >= 0) {
            ProgressCard.Builder builder = new ProgressCard.Builder(course.title()).setSubtitle(subtitle)
                    .setImage(CourseAstraGlyphs.TIMETABLE)
                    .setBody(body.isEmpty() ? null : body);
            configureProgress(builder, course.breaks(), course.start(), course.end(), now, start, end);
            if (button != null) builder.addButton(button);
            card = builder.build();
        } else {
            GenericCard.Builder builder = new GenericCard.Builder(course.title()).setSubtitle(subtitle)
                    .setImage(CourseAstraGlyphs.TIMETABLE).setBody(body);
            if (button != null) builder.addButton(button);
            card = builder.build();
        }
        Capsule capsule = new Capsule.Builder(CourseAstraGlyphs.badge(course.style(), course.start(), course.end(), now))
                .setLabel(CourseAstraIsland.limit(course.title(), 16)).setTrailing(progress >= 0 ? CapsuleTrailing.Companion.progress(fraction)
                        : CapsuleTrailing.Companion.text(subtitle)).build();
        // The Calendar-owned shade notification cannot be linked as a module-owned notification.
        IslandActivity activity = new IslandActivity.Builder(id, capsule, card)
                .setDismissPolicy(DismissPolicy.afterMillis(Math.max(1, course.expires() - now)))
                .setContentDescription(CourseAstraIsland.limit(course.title() + "，" + subtitle + "，" + details, 256))
                .setOpenIntent(course.open()).build();
        IslandResult response = first ? client.start(activity) : client.update(activity);
        Log.i(TAG, (first ? "start=" : "update=") + response + " id=" + id + " progress=" + progress);
        return response;
    }

    private static String metadata(Bundle request, long start, long end) {
        if (request.containsKey("room") || request.containsKey("teacher")) {
            return CourseAstraIsland.limit(joinDetails(request.getString("room", "").trim(),
                    request.getString("teacher", "").trim()), 256);
        }
        // An already running Calendar process may still send the old flat details string.
        String legacy = CourseAstraIsland.limit(request.getString("details", ""), 256);
        String range = timeLabel(start) + "–" + timeLabel(end);
        if (legacy.equals(range)) return "";
        String prefix = range + " · ";
        return legacy.startsWith(prefix) ? legacy.substring(prefix.length()) : legacy;
    }

    private static String timeLabel(long time) { return DateFormat.format("HH:mm", time).toString(); }

    static void configureProgress(ProgressCard.Builder builder, CourseBreaks breaks, long start, long end,
                                  long now, String startLabel, String endLabel) {
        builder.setProgress(breaks.fraction(start, end, now));
        if (breaks.staged()) builder.setStages(breaks.labels(startLabel, endLabel));
        else builder.setLabels(startLabel, endLabel);
    }

    private static String joinDetails(String first, String second) {
        return first.isEmpty() ? second : second.isEmpty() ? first : first + " · " + second;
    }

    private static synchronized void expanded(String id, boolean visible) {
        Session session = active.get(id);
        if (session == null || visible == session.expanded) return;
        session.expanded = visible;
        if (visible) session.updated = Math.min(session.updated, System.currentTimeMillis() - 4000);
        schedule();
    }

    private static long nextUpdate(Session session) {
        Course course = session.course;
        if (session.retryAt != 0) return Math.min(course.expires(), session.retryAt);
        long next = CourseOccurrence.nextIslandUpdate(course.start(), course.end(), course.expires(), session.updated,
                session.expanded && course.showProgress());
        return course.showProgress() ? Math.min(next, course.breaks().nextChange(session.updated)) : next;
    }

    private static synchronized void reconnected(IslandClient source) {
        if (client != source || !source.isReady()) return;
        long now = System.currentTimeMillis();
        for (Session s : active.values()) s.retryAt = now;
        tick();
    }

    static synchronized void onBoundary() {
        if (!active.isEmpty()) tick(); // Only sessions still in memory; never replay a saved course.
    }

    private static void schedule() {
        clock.removeCallbacks(TICK);
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (Session session : active.values()) next = Math.min(next, nextUpdate(session));
        if (client != null && client.isReady()) for (long until : pendingEnds.values())
            next = Math.min(next, Math.min(until, now + 15000L));
        else for (long until : pendingEnds.values()) next = Math.min(next, until);
        if (next != Long.MAX_VALUE) clock.postDelayed(TICK, Math.max(1, next - System.currentTimeMillis()));
    }

    private static synchronized void tick() {
        long now = System.currentTimeMillis();
        boolean ready = client != null && client.isReady();
        for (String id : new ArrayList<>(pendingEnds.keySet())) {
            if (now >= pendingEnds.get(id)) pendingEnds.remove(id);
            else if (ready) end(id);
        }
        for (Map.Entry<String, Session> entry : new ArrayList<>(active.entrySet())) {
            Session session = entry.getValue();
            if (now >= session.course.expires()) end(entry.getKey());
            else if (now >= nextUpdate(session)) {
                try {
                    boolean first = ready && session.retryAt != 0 && !client.listMine().contains(entry.getKey());
                    if (ready && publish(entry.getKey(), session.course, now, first).getAccepted()) {
                        session.updated = now; session.retryAt = 0;
                        continue;
                    }
                } catch (RuntimeException error) { Log.w(TAG, "course activity update failed", error); }
                session.retryAt = now + 15_000L;
            }
        }
        schedule();
    }

    private static void end(String id) {
        Session removed = active.remove(id); // Prevent echoing our END to Calendar as user dismissal.
        if (client != null && client.isReady()) try {
            IslandResult response = client.end(id);
            Log.i(TAG, "end=" + response + " id=" + id);
            if (response.getAccepted()) { pendingEnds.remove(id); return; }
        } catch (RuntimeException error) { Log.w(TAG, "course activity end failed", error); }
        long now = System.currentTimeMillis();
        pendingEnds.putIfAbsent(id, removed == null ? now + 60000L : Math.max(now + 60000L, removed.course.expires()));
    }

    private static PendingIntent pending(Bundle request, String key, boolean allowModule) {
        PendingIntent action = request.getParcelable(key);
        if (action == null) return null;
        String owner = action.getCreatorPackage();
        return "com.coloros.calendar".equals(owner) || (allowModule && CourseDndClient.PACKAGE.equals(owner)) ? action : null;
    }

    private static void sendAction(String id, boolean dismiss) {
        Session session;
        synchronized (CourseAstraBroker.class) { session = dismiss ? active.remove(id) : active.get(id); }
        if (session == null) return;
        PendingIntent action = dismiss ? session.course.dismiss() : session.course.dnd();
        if (action != null) try { action.send(); }
        catch (PendingIntent.CanceledException ignored) { }
    }

    private static Bundle result(String status) {
        Bundle result = new Bundle();
        result.putString("result", status);
        return result;
    }
}
