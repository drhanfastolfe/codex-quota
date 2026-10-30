package es.codexquota.mobile.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class ResetCredits {
    public final long availableCount;
    public final List<ResetCredit> credits;
    public final String detailsError;

    public ResetCredits(long j, List<ResetCredit> list) {
        this(j, list, null);
    }

    public ResetCredits(long j, List<ResetCredit> list, String str) {
        this.availableCount = Math.max(0L, j);
        this.credits = list == null ? null : Collections.unmodifiableList(new ArrayList(list));
        this.detailsError = str;
    }

    public List<ResetCredit> availableByExpiry(long j) {
        ArrayList arrayList = new ArrayList();
        List<ResetCredit> list = this.credits;
        if (list != null) {
            for (ResetCredit resetCredit : list) {
                if (resetCredit.isAvailable(j)) {
                    arrayList.add(resetCredit);
                }
            }
        }
        Collections.sort(arrayList, new Comparator() {
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return ResetCredits.lambda$availableByExpiry$0((ResetCredit) obj, (ResetCredit) obj2);
            }
        });
        return Collections.unmodifiableList(arrayList);
    }

    static int lambda$availableByExpiry$0(ResetCredit resetCredit, ResetCredit resetCredit2) {
        int iCompareNullable = compareNullable(resetCredit.expiresAtMillis, resetCredit2.expiresAtMillis);
        if (iCompareNullable != 0) {
            return iCompareNullable;
        }
        int iCompareNullable2 = compareNullable(resetCredit.grantedAtMillis, resetCredit2.grantedAtMillis);
        if (iCompareNullable2 != 0) {
            return iCompareNullable2;
        }
        return (resetCredit.id == null ? "" : resetCredit.id).compareTo(resetCredit2.id != null ? resetCredit2.id : "");
    }

    public ResetCredit earliestForRedemption(long j) {
        List<ResetCredit> listAvailableByExpiry = availableByExpiry(j);
        if (this.availableCount <= 0 || this.credits == null || this.detailsError != null || listAvailableByExpiry.size() < this.availableCount || listAvailableByExpiry.isEmpty()) {
            return null;
        }
        ResetCredit resetCredit = listAvailableByExpiry.get(0);
        if (resetCredit.id == null || resetCredit.id.trim().isEmpty()) {
            return null;
        }
        return resetCredit;
    }

    /**
     * Remove a server-confirmed consumed credit without inventing a fresh quota.
     * Null means the credit set must be fetched again. A complete set that already
     * omitted this ID has already accounted for it and must not be decremented twice.
     */
    public ResetCredits afterConfirmedConsumption(String creditId, long snapshotTimeMillis) {
        if (creditId == null || creditId.trim().isEmpty() || this.credits == null
                || this.detailsError != null || snapshotTimeMillis <= 0
                || availableByExpiry(snapshotTimeMillis).size() < this.availableCount) {
            return null;
        }
        List<ResetCredit> remaining = new ArrayList<>();
        boolean wasAvailable = false;
        for (ResetCredit credit : this.credits) {
            if (creditId.equals(credit.id)) {
                wasAvailable |= credit.isAvailable(snapshotTimeMillis);
            } else {
                remaining.add(credit);
            }
        }
        long count = wasAvailable ? Math.max(0L, this.availableCount - 1) : this.availableCount;
        return new ResetCredits(count, remaining);
    }

    public long displayAvailableCount(long j, long j2) {
        if (this.credits != null && this.detailsError == null && availableByExpiry(j2).size() == this.availableCount) {
            return availableByExpiry(j).size();
        }
        return this.availableCount;
    }

    public long missingDetailCount(long j) {
        return Math.max(0L, this.availableCount - ((long) availableByExpiry(j).size()));
    }

    private static int compareNullable(Long l, Long l2) {
        if (l == null) {
            return l2 == null ? 0 : 1;
        }
        if (l2 == null) {
            return -1;
        }
        return Long.compare(l.longValue(), l2.longValue());
    }
}
