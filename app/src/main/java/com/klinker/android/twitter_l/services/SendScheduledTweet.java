package com.klinker.android.twitter_l.services;
/*
 * Copyright 2014 Luke Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Vibrator;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.util.Patterns;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.Job;
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.Lifetime;
import com.firebase.jobdispatcher.SimpleJobService;
import com.firebase.jobdispatcher.Trigger;
import com.klinker.android.twitter_l.R;
import com.klinker.android.twitter_l.activities.MainActivity;
import com.klinker.android.twitter_l.activities.compose.RetryCompose;
import com.klinker.android.twitter_l.data.ScheduledTweet;
import com.klinker.android.twitter_l.data.sq_lite.QueuedDataSource;
import com.klinker.android.twitter_l.settings.AppSettings;
import com.klinker.android.twitter_l.utils.NotificationChannelUtil;
import com.klinker.android.twitter_l.utils.Utils;
import com.klinker.android.twitter_l.utils.api_helper.TwitLongerHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.regex.Matcher;

import twitter4j.Twitter;
import twitter4j.UserList;

public class SendScheduledTweet extends SimpleJobService {

    public static final String JOB_TAG = "send-scheduled-tweet";

    public static void scheduleNextRun(Context context) {
        FirebaseJobDispatcher dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(context));

        ArrayList<ScheduledTweet> tweets = QueuedDataSource.getInstance(context).getScheduledTweets();
        Collections.sort(tweets, new Comparator<ScheduledTweet>() {
            public int compare(ScheduledTweet result1, ScheduledTweet result2) {
                return Long.compare(result1.time, result2.time);
            }
        });

        if (tweets.size() == 0) {
            dispatcher.cancel(JOB_TAG);
        } else {
            ScheduledTweet s = tweets.get(0);
            int tweetInSeconds = (int) (s.time - new Date().getTime()) / 1000;

            if (tweetInSeconds <= 0) {
                return;
            }

            Job myJob = dispatcher.newJobBuilder()
                    .setService(SendScheduledTweet.class)
                    .setTag(JOB_TAG)
                    .setRecurring(false)
                    .setLifetime(Lifetime.FOREVER)
                    .setTrigger(Trigger.executionWindow(tweetInSeconds, tweetInSeconds + 60)) // within 1 min
                    .setReplaceCurrent(true)
                    .build();

            dispatcher.mustSchedule(myJob);
        }
    }

    @Override
    public int onRunJob(JobParameters parameters) {
        Log.v("talon_scheduled_tweet", "started service");

        ArrayList<ScheduledTweet> tweets = QueuedDataSource.getInstance(this).getScheduledTweets();
        if (tweets.size() != 0) {
            ScheduledTweet s = tweets.get(0);
            final Context context = this;
            final AppSettings settings = AppSettings.getInstance(context);

            sendingNotification();
            boolean sent = sendTweet(settings, context, s.text, settings.currentAccount);

            if (sent) {
                finishedTweetingNotification();
                QueuedDataSource.getInstance(context).deleteScheduledTweet(s.alarmId);
            } else {
                makeFailedNotification(s.text, settings);
            }
        }

        scheduleNextRun(this);
        return 0;
    }

    public boolean sendTweet(AppSettings settings, Context context, String message, int account) {
        try {
            Twitter twitter;
            if (account == settings.currentAccount) {
                twitter = Utils.getTwitter(context, settings);
            } else {
                twitter = Utils.getSecondTwitter(context);
            }

            int size = getCount(message);

            Log.v("talon_queued", "sending: " + message);

            if (size > AppSettings.getInstance(this).tweetCharacterCount && settings.twitlonger) {
                // twitlonger goes here
                TwitLongerHelper helper = new TwitLongerHelper(message, twitter, context);

                return helper.createPost() != 0;
            } else if (size <= AppSettings.getInstance(this).tweetCharacterCount) {
                twitter4j.StatusUpdate reply = new twitter4j.StatusUpdate(message);
                twitter.updateStatus(reply);
            } else {
                return false;
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public int getCount(String text) {
        if (!text.contains("http")) { // no links, normal tweet
            return text.length();
        } else {
            int count = text.length();
            Matcher m = Patterns.WEB_URL.matcher(text);

            while(m.find()) {
                String url = m.group();
                count -= url.length(); // take out the length of the url
                count += 23; // add 23 for the shortened url
            }

            return count;
        }
    }

    public void sendingNotification() {
        // first we will make a notification to let the user know we are tweeting
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this, NotificationChannelUtil.SENDING_SCHEDULED_MESSAGE_CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_icon)
                        .setContentTitle(getResources().getString(R.string.sending_tweet))
                        .setOngoing(true)
                        .setProgress(100, 0, true);

        Intent resultIntent = new Intent(this, MainActivity.class);

        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        resultIntent,
                        0
                );

        mBuilder.setContentIntent(resultPendingIntent);

        startForeground(6, mBuilder.build());
    }

    public void makeFailedNotification(String text, AppSettings settings) {
        try {
            NotificationCompat.Builder mBuilder =
                    new NotificationCompat.Builder(this, NotificationChannelUtil.FAILED_TWEETS_CHANNEL)
                            .setSmallIcon(R.drawable.ic_stat_icon)
                            .setContentTitle(getResources().getString(R.string.tweet_failed))
                            .setContentText(getResources().getString(R.string.tap_to_retry));

            Intent resultIntent = new Intent(this, RetryCompose.class);
            QueuedDataSource.getInstance(this).createDraft(text, settings.currentAccount);
            resultIntent.setAction(Intent.ACTION_SEND);
            resultIntent.setType("text/plain");
            resultIntent.putExtra(Intent.EXTRA_TEXT, text);
            resultIntent.putExtra("failed_notification", true);

            PendingIntent resultPendingIntent =
                    PendingIntent.getActivity(
                            this,
                            0,
                            resultIntent,
                            0
                    );

            mBuilder.setContentIntent(resultPendingIntent);
            NotificationManager mNotificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.notify(5, mBuilder.build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void finishedTweetingNotification() {
        try {
            NotificationCompat.Builder mBuilder =
                    new NotificationCompat.Builder(this, NotificationChannelUtil.SENDING_SCHEDULED_MESSAGE_CHANNEL)
                            .setSmallIcon(R.drawable.ic_stat_icon)
                            .setContentTitle(getResources().getString(R.string.tweet_success))
                            .setOngoing(false)
                            .setTicker(getResources().getString(R.string.tweet_success));

            if (AppSettings.getInstance(this).vibrate) {
                Log.v("talon_vibrate", "vibrate on compose");
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                long[] pattern = { 0, 50, 500 };
                v.vibrate(pattern, -1);
            }

            stopForeground(true);

            NotificationManager mNotificationManager =
                    (NotificationManager) MainActivity.sContext.getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.notify(6, mBuilder.build());
            // cancel it immediately, the ticker will just go off
            mNotificationManager.cancel(6);
        } catch (Exception e) {
            // not attached to activity
        }
    }
}
