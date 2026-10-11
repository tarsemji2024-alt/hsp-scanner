package com.rsn.scanner;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** इंटरनेट मिलते ही नया RSN डेटा Google Drive पर अपने आप बैकअप करता है (ऐप बंद हो तब भी चलता है)। */
public class SyncJobService extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            try {
                if (CloudBackup.isEnabled(this) && !CloudBackup.isHold(this)) {
                    CloudBackup.uploadLatest(this);
                }
            } catch (CloudBackup.NeedsAuth e) {
                retry = false; // अनुमति खत्म: ऐप खोलकर दोबारा जोड़ना होगा
            } catch (Exception e) {
                retry = true;  // नेटवर्क आदि की दिक्कत: बाद में दोबारा कोशिश
            }
            jobFinished(params, retry);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // रुक गया तो दोबारा शेड्यूल हो
    }
}
