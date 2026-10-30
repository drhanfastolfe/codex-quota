package es.codexquota.mobile.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class AlertPolicy {
    private static final long CYCLE_TOLERANCE_MILLIS = 120000;
    public static final int EXPIRING_RESET = 2;
    public static final int LOW_QUOTA = 1;
    public static final long MAX_SNAPSHOT_AGE_MILLIS = 300000;
    private static final int RECOVERY_MARGIN_PERCENT = 5;

    public static final class Config {
        public final boolean expiryEnabled;
        public final int expiryHours;
        public final boolean lowEnabled;
        public final int lowThresholdPercent;

        public Config(boolean z, int i, boolean z2, int i2) {
            this.lowEnabled = z;
            this.expiryEnabled = z2;
            this.lowThresholdPercent = AlertPolicy.clampThresholdPercent(i);
            this.expiryHours = AlertPolicy.clampExpiryHours(i2);
        }
    }

    private static final class LowState {
        boolean delivered;
        Long resetAt;
        int thresholdPercent;

        LowState(Long l, boolean z, int i) {
            this.resetAt = l;
            this.delivered = z;
            this.thresholdPercent = i;
        }

        LowState copy() {
            return new LowState(this.resetAt, this.delivered, this.thresholdPercent);
        }
    }

    public static final class State {
        public long lastEvaluatedAt;
        private final Map<String, LowState> windows = new HashMap();
        private final Map<String, Long> resets = new LinkedHashMap();

        public String toJson() {
            try {
                JSONObject jSONObjectPut = new JSONObject().put("schema", 1).put("evaluated_at", this.lastEvaluatedAt);
                JSONArray jSONArray = new JSONArray();
                JSONArray jSONArray2 = new JSONArray();
                for (Map.Entry<String, LowState> entry : this.windows.entrySet()) {
                    jSONArray.put(new JSONObject().put("key", entry.getKey()).put("reset_at", entry.getValue().resetAt).put("delivered", entry.getValue().delivered).put("threshold_percent", entry.getValue().thresholdPercent));
                }
                for (Map.Entry<String, Long> entry2 : this.resets.entrySet()) {
                    jSONArray2.put(new JSONObject().put("key", entry2.getKey()).put("expires_at", entry2.getValue()));
                }
                return jSONObjectPut.put("windows", jSONArray).put("resets", jSONArray2).toString();
            } catch (Exception unused) {
                throw new IllegalStateException("No se pudieron guardar los avisos.");
            }
        }

        public static State fromJson(String str) {
            State state = new State();
            if (str == null || str.isEmpty()) {
                return state;
            }
            try {
                JSONObject jSONObject = new JSONObject(str);
                if (jSONObject.optInt("schema") != 1) {
                    return state;
                }
                state.lastEvaluatedAt = jSONObject.optLong("evaluated_at");
                JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("windows");
                JSONArray jSONArrayOptJSONArray2 = jSONObject.optJSONArray("resets");
                if (jSONArrayOptJSONArray != null) {
                    for (int i = 0; i < jSONArrayOptJSONArray.length() && i < 128; i++) {
                        JSONObject jSONObjectOptJSONObject = jSONArrayOptJSONArray.optJSONObject(i);
                        if (jSONObjectOptJSONObject != null && !jSONObjectOptJSONObject.optString("key").isEmpty()) {
                            state.windows.put(jSONObjectOptJSONObject.optString("key"), new LowState((!jSONObjectOptJSONObject.has("reset_at") || jSONObjectOptJSONObject.isNull("reset_at")) ? null : Long.valueOf(jSONObjectOptJSONObject.optLong("reset_at")), jSONObjectOptJSONObject.optBoolean("delivered"), savedThreshold(jSONObjectOptJSONObject)));
                        }
                    }
                }
                if (jSONArrayOptJSONArray2 != null) {
                    for (int i2 = 0; i2 < jSONArrayOptJSONArray2.length() && i2 < 512; i2++) {
                        JSONObject jSONObjectOptJSONObject2 = jSONArrayOptJSONArray2.optJSONObject(i2);
                        if (jSONObjectOptJSONObject2 != null && !jSONObjectOptJSONObject2.optString("key").isEmpty()) {
                            state.resets.put(jSONObjectOptJSONObject2.optString("key"), Long.valueOf(jSONObjectOptJSONObject2.optLong("expires_at")));
                        }
                    }
                }
                return state;
            } catch (Exception unused) {
                return new State();
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public State copy() {
            State state = new State();
            state.lastEvaluatedAt = this.lastEvaluatedAt;
            for (Map.Entry<String, LowState> entry : this.windows.entrySet()) {
                state.windows.put(entry.getKey(), entry.getValue().copy());
            }
            state.resets.putAll(this.resets);
            return state;
        }

        public State rearmLowQuota() {
            State stateCopy = copy();
            Iterator<LowState> it = stateCopy.windows.values().iterator();
            while (it.hasNext()) {
                it.next().delivered = false;
            }
            return stateCopy;
        }

        public State rearmExpiringResets() {
            State stateCopy = copy();
            stateCopy.resets.clear();
            return stateCopy;
        }

        public boolean expiryDelivered(ResetCredit credit, int hours) {
            return resets.containsKey(resetKey(credit, hours));
        }

        private static int savedThreshold(JSONObject jSONObject) {
            int iOptInt = jSONObject.optInt("threshold_percent", 0);
            if (iOptInt < 1 || iOptInt > 100) {
                return 0;
            }
            return iOptInt;
        }
    }

    public static final class Event {
        public final ResetCredit credit;
        private final String key;
        public final int kind;
        public final QuotaWindow window;

        private Event(int i, String str, QuotaWindow quotaWindow, ResetCredit resetCredit) {
            this.kind = i;
            this.key = str;
            this.window = quotaWindow;
            this.credit = resetCredit;
        }
    }

    public static final class Evaluation {
        public final List<Event> events;
        public final boolean fresh;
        public final State state;

        private Evaluation(State state, List<Event> list, boolean z) {
            this.state = state;
            this.events = Collections.unmodifiableList(list);
            this.fresh = z;
        }

        public void markDelivered(int i) {
            for (Event event : this.events) {
                if (event.kind == i) {
                    if (i == 1) {
                        LowState lowState = (LowState) this.state.windows.get(event.key);
                        if (lowState != null) {
                            lowState.delivered = true;
                        }
                    } else {
                        this.state.resets.put(event.key, event.credit.expiresAtMillis);
                    }
                }
            }
            while (this.state.resets.size() > 512) {
                this.state.resets.remove(this.state.resets.keySet().iterator().next());
            }
        }
    }

    public static Evaluation evaluate(QuotaSnapshot quotaSnapshot, Config config, State state, final long j) {
        State stateCopy;
        boolean z;
        if (state != null) {
            stateCopy = state.copy();
        } else {
            stateCopy = new State();
        }
        ArrayList arrayList = new ArrayList();
        boolean z2 = false;
        if (quotaSnapshot == null || quotaSnapshot.fetchedAtMillis <= 0 || quotaSnapshot.fetchedAtMillis <= stateCopy.lastEvaluatedAt || quotaSnapshot.fetchedAtMillis > 30000 + j || j - quotaSnapshot.fetchedAtMillis > MAX_SNAPSHOT_AGE_MILLIS) {
            return new Evaluation(stateCopy, arrayList, z2);
        }
        stateCopy.lastEvaluatedAt = quotaSnapshot.fetchedAtMillis;
        HashSet hashSet = new HashSet();
        Iterator<QuotaWindow> it = quotaSnapshot.windows.iterator();
        while (true) {
            z = true;
            if (!it.hasNext()) {
                break;
            }
            QuotaWindow next = it.next();
            String strWindowKey = windowKey(next);
            if (hashSet.add(strWindowKey)) {
                LowState lowState = (LowState) stateCopy.windows.get(strWindowKey);
                z = (lowState == null || lowState.resetAt == null || next.resetAtMillis == null || Math.abs(lowState.resetAt.longValue() - next.resetAtMillis.longValue()) <= CYCLE_TOLERANCE_MILLIS) ? false : true;
                if (lowState == null || z) {
                    lowState = new LowState(next.resetAtMillis, false, config.lowThresholdPercent);
                    stateCopy.windows.put(strWindowKey, lowState);
                } else if (next.resetAtMillis != null) {
                    lowState.resetAt = next.resetAtMillis;
                }
                if (!config.lowEnabled || lowState.thresholdPercent != config.lowThresholdPercent) {
                    lowState.delivered = false;
                }
                lowState.thresholdPercent = config.lowThresholdPercent;
                double dRemainingPercent = next.remainingPercent();
                if (dRemainingPercent > config.lowThresholdPercent && dRemainingPercent >= Math.min(100, config.lowThresholdPercent + RECOVERY_MARGIN_PERCENT)) {
                    lowState.delivered = false;
                }
                if (config.lowEnabled && !lowState.delivered && dRemainingPercent <= config.lowThresholdPercent && (next.resetAtMillis == null || next.resetAtMillis.longValue() > j)) {
                    arrayList.add(new Event(1, strWindowKey, next, null));
                }
            }
        }
        stateCopy.windows.keySet().retainAll(hashSet);
        stateCopy.resets.entrySet().removeIf(new Predicate() { // from class: es.codexquota.mobile.core.AlertPolicy$$ExternalSyntheticLambda0
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return AlertPolicy.lambda$evaluate$0(j, (Map.Entry) obj);
            }
        });
        ResetCredits resetCredits = quotaSnapshot.resetCredits;
        if (config.expiryEnabled && resetCredits != null && resetCredits.availableCount > 0 && resetCredits.credits != null) {
            HashSet hashSet2 = new HashSet();
            for (ResetCredit resetCredit : resetCredits.availableByExpiry(j)) {
                if (resetCredit.id != null && !resetCredit.id.trim().isEmpty() && resetCredit.expiresAtMillis != null) {
                    long jLongValue = resetCredit.expiresAtMillis.longValue() - j;
                    if (jLongValue > 0 && jLongValue <= ((long) (config.expiryHours * 3600)) * 1000) {
                        String str = resetCredit.id + "|" + resetCredit.expiresAtMillis + "|" + config.expiryHours;
                        if (hashSet2.add(str) && !stateCopy.resets.containsKey(str)) {
                            arrayList.add(new Event(2, str, null, resetCredit));
                        }
                    }
                }
            }
        }
        return new Evaluation(stateCopy, arrayList, z);
    }

    static /* synthetic */ boolean lambda$evaluate$0(long j, Map.Entry entry) {
        return ((Long) entry.getValue()).longValue() <= j;
    }

    /** Evaluate only dates already stored on this phone, without treating quota as fresh. */
    public static Evaluation evaluateKnownExpiry(QuotaSnapshot snapshot, Config config,
                                                 State previous, long now) {
        State state = previous == null ? new State() : previous.copy();
        List<Event> events = new ArrayList<>();
        state.resets.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (config.expiryEnabled && snapshot != null && snapshot.resetCredits != null
                && snapshot.resetCredits.availableCount > 0 && snapshot.resetCredits.credits != null) {
            HashSet<String> seen = new HashSet<>();
            for (ResetCredit credit : snapshot.resetCredits.availableByExpiry(now)) {
                if (credit.id == null || credit.id.trim().isEmpty() || credit.expiresAtMillis == null) continue;
                long remaining = credit.expiresAtMillis - now;
                String key = resetKey(credit, config.expiryHours);
                if (remaining > 0 && remaining <= config.expiryHours * 3_600_000L
                        && seen.add(key) && !state.resets.containsKey(key)) {
                    events.add(new Event(EXPIRING_RESET, key, null, credit));
                }
            }
        }
        return new Evaluation(state, events, true);
    }

    private static String resetKey(ResetCredit credit, int hours) {
        return credit.id + "|" + credit.expiresAtMillis + "|" + hours;
    }

    private static String windowKey(QuotaWindow quotaWindow) {
        StringBuilder sb = new StringBuilder();
        sb.append(quotaWindow.limitId == null ? "" : quotaWindow.limitId);
        sb.append("|");
        sb.append(quotaWindow.windowSeconds != null ? quotaWindow.windowSeconds : "");
        sb.append("|");
        sb.append(quotaWindow.label);
        return sb.toString();
    }

    public static int clampThresholdPercent(int i) {
        return Math.max(1, Math.min(100, i));
    }

    public static int clampExpiryHours(int i) {
        return Math.max(1, Math.min(168, i));
    }

    private AlertPolicy() {
    }
}
