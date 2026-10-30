package es.codexquota.mobile;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;

/** Network-constrained read work. Credit redemption is never scheduled here. */
public final class RefreshJob extends JobService {
    private final Set<JobParameters> active = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public boolean onStartJob(final JobParameters parameters) {
        active.add(parameters);
        long generation = parameters.getExtras().getLong(QuotaRepository.RETRY_GENERATION, -1L);
        QuotaRepository.get(this).refreshFromJob(parameters.getJobId(), generation,
                message -> main.post(() -> {
                    if (active.remove(parameters)) {
                        // The repository schedules its own bounded, Retry-After-aware
                        // one-shot retry. Asking Android to retry too would duplicate work.
                        jobFinished(parameters, false);
                    }
                }));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        active.remove(parameters);
        return true; // Android stopped this read (for example because connectivity changed).
    }
}
