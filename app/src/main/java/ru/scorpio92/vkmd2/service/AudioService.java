package ru.scorpio92.vkmd2.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.RemoteControlClient;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v7.app.NotificationCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.widget.RemoteViews;

import com.squareup.picasso.Picasso;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.Timer;

import ru.scorpio92.vkmd2.R;
import ru.scorpio92.vkmd2.data.entity.Track;
import ru.scorpio92.vkmd2.data.repository.db.TrackProvider;
import ru.scorpio92.vkmd2.data.repository.db.base.ITrackProvider;
import ru.scorpio92.vkmd2.presentation.view.activity.MusicActivity;
import ru.scorpio92.vkmd2.receiver.HeadsetPlugReceiver;
import ru.scorpio92.vkmd2.receiver.LockScreenReceiver;
import ru.scorpio92.vkmd2.tools.Logger;
import ru.scorpio92.vkmd2.tools.VkmdUtils;


public class AudioService extends Service implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener,
        AudioManager.OnAudioFocusChangeListener {

    private final String LOG_TAG = this.getClass().getSimpleName();

    //ACTION command from Activity//////////////////////////////////////////////////////////////////
    public final static String SERVICE_ACTION = "ACTION";

    public enum ACTION {
        GET_INFO,
        LOOP_FEATURE,
        RANDOM_FEATURE,
        PLAY,
        PAUSE,
        PLAY_OR_PAUSE,
        STOP,
        NEXT,
        PREV,
        SEEK_TO
    }

    public final static String AUDIO_PROVIDER = "OFFLINE_MODE";
    public final static String AUDIO_TRACK_ID_PARAM = "TRACK_ID";
    public final static String AUDIO_SEEK_PARAM = "SEEK_TO";
    ////////////////////////////////////////////////////////////////////////////////////////////////


    //Event from Service to Activity////////////////////////////////////////////////////////////////
    public final static String SERVICE_BROADCAST = "VKMD2.AUDIO_SERVICE.BROADCAST";
    public final static String SERVICE_EVENT = "EVENT";

    public enum EVENT {
        PROVIDE_INFO,
        LOOP_ENABLED,
        LOOP_DISABLED,
        RANDOM_ENABLED,
        RANDOM_DISABLED,
        PREPARE_FOR_PLAY, //AUDIO_TRACK_NAME_PARAM, AUDIO_TRACK_ARTIST_PARAM, AUDIO_TRACK_DURATION_PARAM
        START_PLAY,
        PROGRESS_UPDATE, //AUDIO_TRACK_PROGRESS_PARAM
        PAUSE,
        STOP_SERVICE,
        ERROR
    }

    public final static String AUDIO_TRACK_POSITION_PARAM = "TRACK_POSITION";
    public final static String AUDIO_TRACK_NAME_PARAM = "TRACK_NAME";
    public final static String AUDIO_TRACK_ARTIST_PARAM = "TRACK_ARTIST";
    public final static String AUDIO_TRACK_DURATION_PARAM = "TRACK_DURATION";
    public final static String AUDIO_TRACK_IMAGE_URL_PARAM = "TRACK_IMAGE";
    public final static String AUDIO_TRACK_PROGRESS_PARAM = "TRACK_PROGRESS";
    public final static String AUDIO_TRACK_IS_PLAY_PARAM = "TRACK_IS_PLAY";
    ////////////////////////////////////////////////////////////////////////////////////////////////

    ////////////////////////////////////////////////////////////////////////////////////////////////
    private int startId;

    private ITrackProvider trackProvider;

    private MediaPlayer mediaPlayer;
    private Track currentTrack;
    private int currentTrackPosition = -1;
    private Timer timer;
    private boolean loopIsEnabled;
    private boolean randomIsEnabled;
    private boolean wasError;

    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private HeadsetPlugReceiver headsetPlugReceiver;

    private RemoteControlClient remoteControlClient;

    private final static int NOTIFICATION_ID = 6661;
    public final static String NOTIFICATION_ACTION_PREV = "VKMD2.AUDIO_SERVICE.PREV";
    public final static String NOTIFICATION_ACTION_PLAY_PAUSE = "VKMD2.AUDIO_SERVICE.PLAY_PAUSE";
    public final static String NOTIFICATION_ACTION_NEXT = "VKMD2.AUDIO_SERVICE.NEXT";
    public final static String NOTIFICATION_ACTION_STOP = "VKMD2.AUDIO_SERVICE.STOP";
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.log(LOG_TAG, "onCreate");

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        this.startId = startId;
        Logger.log(LOG_TAG, "onStartCommand, startId: " + startId);
        if (intent != null) {
            ACTION action = Enum.valueOf(ACTION.class, intent.getStringExtra(SERVICE_ACTION));

            Logger.log(LOG_TAG, "onStartCommand, SERVICE_ACTION: " + action.name());

            try {
                handleAction(action, intent);
            } catch (Exception e) {
                Logger.error(e);
            }
        }
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.log(LOG_TAG, "onDestroy");
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        Logger.log(LOG_TAG, "onCompletion");
        if (!wasError && !loopIsEnabled) {
            try {
                handleAction(ACTION.NEXT, null);
            } catch (Exception e) {
                Logger.error(e);
            }
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Logger.log(LOG_TAG, "what: " + what);
        Logger.log(LOG_TAG, "extra: " + extra);
        switch (what) {
            case -38:
            case 1:
                wasError = true;
                sendBroadcastToActivity(EVENT.ERROR);
                mediaPlayer.stop();
                mediaPlayer.reset();
                break;
        }
        return false;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mp.start();
        timer.schedule(new TimerTask(), 0, 1000);
        sendBroadcastToActivity(EVENT.START_PLAY);
        sentNotificationInForeground();
    }

    @Override
    public void onAudioFocusChange(int focusChange) {

    }

    private void handleAction(ACTION action, Intent intent) throws IOException, NullPointerException {
        switch (action) {

            case GET_INFO:
                if (startId > 1) {
                    sendBroadcastToActivity(EVENT.PROVIDE_INFO);
                } else {
                    finish();
                }
                break;

            case LOOP_FEATURE:
                loopIsEnabled = !loopIsEnabled;
                mediaPlayer.setLooping(loopIsEnabled);
                sendBroadcastToActivity(loopIsEnabled ? EVENT.LOOP_ENABLED : EVENT.LOOP_DISABLED);
                break;

            case RANDOM_FEATURE:
                randomIsEnabled = !randomIsEnabled;
                sendBroadcastToActivity(randomIsEnabled ? EVENT.RANDOM_ENABLED : EVENT.RANDOM_DISABLED);
                break;

            case PLAY:
                initTrackProvider(intent.getStringExtra(AUDIO_PROVIDER));

                registerHeadsetPlugReceiver();
                registerPhoneStateListener();
                registerRemoteClient();

                new Thread(() -> {
                    try {
                        Thread.sleep(500);

                        String trackId = intent.getStringExtra(AUDIO_TRACK_ID_PARAM);
                        if (trackId != null) {
                            Logger.log("trackId: " + trackId);
                            Track track = trackProvider.getTrackByTrackId(trackId);
                            if (track == null)
                                throw new NullPointerException();

                            if (currentTrackPosition != track.getId()) {
                                currentTrack = track;
                                currentTrackPosition = track.getId();
                                Logger.log("PLAY, currentTrackPosition: " + currentTrackPosition);
                                preparePlayerForPlay();
                            }
                        } else {
                            ///mediaPlayer.start();
                            sendBroadcastToActivity(EVENT.ERROR);
                        }
                        //sentNotificationInForeground();
                    } catch (Exception e) {
                        Logger.error(e);
                        sendBroadcastToActivity(EVENT.ERROR);
                    }
                }).start();
                break;

            case NEXT:
                if (randomIsEnabled) {
                    int tracksCount = trackProvider.getTracksCount();
                    currentTrackPosition = new Random().nextInt(tracksCount) + 1;
                } else {
                    currentTrackPosition++;
                }

                Track nextTrack = trackProvider.getTrackByPosition(currentTrackPosition);
                if (nextTrack != null) {
                    this.currentTrack = nextTrack;
                    preparePlayerForPlay();
                } else {
                    currentTrackPosition--;
                    mediaPlayer.pause();

                    sendBroadcastToActivity(EVENT.PAUSE);
                }

                sentNotificationInForeground();
                break;

            case PREV:
                currentTrackPosition--;
                Track prevTrack = trackProvider.getTrackByPosition(currentTrackPosition);
                if (prevTrack != null) {
                    this.currentTrack = prevTrack;
                    preparePlayerForPlay();
                } else {
                    currentTrackPosition++;
                    mediaPlayer.pause();

                    sendBroadcastToActivity(EVENT.PAUSE);
                }
                sentNotificationInForeground();
                break;

            case PAUSE:
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    sendBroadcastToActivity(EVENT.PAUSE);
                }

                sentNotificationInForeground();
                break;

            case PLAY_OR_PAUSE:
                if (mediaPlayer != null) {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                        sendBroadcastToActivity(EVENT.PAUSE);
                    } else {
                        mediaPlayer.start();
                        sendBroadcastToActivity(EVENT.START_PLAY);
                    }
                }
                sentNotificationInForeground();
                break;

            case STOP:
                finish();
                break;

            case SEEK_TO:
                if (mediaPlayer != null) {
                    int position = intent.getIntExtra(AUDIO_SEEK_PARAM, 0);
                    mediaPlayer.seekTo(position);
                }
                break;
        }
    }

    private void initTrackProvider(String provider) {
        if(provider == null)
            return;

        //if (trackProvider == null)
            trackProvider = new TrackProvider(Enum.valueOf(TrackProvider.PROVIDER.class, provider));
    }

    private void stop() {
        sendBroadcastToActivity(EVENT.STOP_SERVICE);

        if (timer != null) {
            timer.cancel();
        }

        if (mediaPlayer != null)
            mediaPlayer.release();

        if (headsetPlugReceiver != null) {
            unregisterReceiver(headsetPlugReceiver);
            headsetPlugReceiver = null;
        }

        phoneStateListener = null;
    }

    private void preparePlayerForPlay() throws IOException, NullPointerException {
        if (timer != null) {
            timer.cancel();
        }
        timer = new Timer();

        wasError = false;

        mediaPlayer.reset();
        if (currentTrack.isSaved()) {
            if (new File(currentTrack.getSavedPath()).exists())
                mediaPlayer.setDataSource(currentTrack.getSavedPath());
            else
                mediaPlayer.setDataSource(VkmdUtils.decode(currentTrack.getUrlAudio(), Integer.valueOf(currentTrack.getUserId())));
        } else {
            mediaPlayer.setDataSource(VkmdUtils.decode(currentTrack.getUrlAudio(), Integer.valueOf(currentTrack.getUserId())));
        }
        mediaPlayer.prepareAsync();

        sendBroadcastToActivity(EVENT.PREPARE_FOR_PLAY);
    }

    private void sendBroadcastToActivity(EVENT event) {
        try {
            Intent intent = new Intent(SERVICE_BROADCAST);
            intent.putExtra(SERVICE_EVENT, event.name());
            switch (event) {
                case PROVIDE_INFO:
                    if(currentTrack != null) {
                        intent.putExtra(AUDIO_TRACK_ID_PARAM, currentTrack.getTrackId());
                        intent.putExtra(AUDIO_TRACK_POSITION_PARAM, currentTrackPosition);
                        intent.putExtra(AUDIO_TRACK_NAME_PARAM, currentTrack.getName());
                        intent.putExtra(AUDIO_TRACK_ARTIST_PARAM, currentTrack.getArtist());
                        intent.putExtra(AUDIO_TRACK_DURATION_PARAM, currentTrack.getDuration());
                        intent.putExtra(AUDIO_TRACK_IMAGE_URL_PARAM, currentTrack.getUrlImage());
                        intent.putExtra(AUDIO_TRACK_PROGRESS_PARAM, mediaPlayer.getCurrentPosition());
                        intent.putExtra(AUDIO_TRACK_IS_PLAY_PARAM, mediaPlayer.isPlaying());
                    }
                    break;
                case PREPARE_FOR_PLAY:
                    intent.putExtra(AUDIO_TRACK_ID_PARAM, currentTrack.getTrackId());
                    intent.putExtra(AUDIO_TRACK_POSITION_PARAM, currentTrackPosition);
                    intent.putExtra(AUDIO_TRACK_NAME_PARAM, currentTrack.getName());
                    intent.putExtra(AUDIO_TRACK_ARTIST_PARAM, currentTrack.getArtist());
                    intent.putExtra(AUDIO_TRACK_DURATION_PARAM, currentTrack.getDuration());
                    intent.putExtra(AUDIO_TRACK_IMAGE_URL_PARAM, currentTrack.getUrlImage());
                    break;
                case STOP_SERVICE:
                case START_PLAY:
                case PAUSE:
                case LOOP_ENABLED:
                case LOOP_DISABLED:
                case ERROR:
                    break;
                case PROGRESS_UPDATE:
                    intent.putExtra(AUDIO_TRACK_PROGRESS_PARAM, mediaPlayer.getCurrentPosition());
                    break;
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        } catch (Exception e) {
            Logger.error(e);
        }
    }

    private void registerPhoneStateListener() {
        if (phoneStateListener == null) {
            //регистрируем слушатель для регистрации событий телефонных звонков
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String incomingNumber) {
                    try {
                        switch (state) {
                            case TelephonyManager.CALL_STATE_RINGING:
                            case TelephonyManager.CALL_STATE_OFFHOOK:
                                if (mediaPlayer != null) {
                                    mediaPlayer.pause();
                                    sentNotificationInForeground();
                                }
                                break;
                        }
                    } catch (Exception e) {
                        Logger.error(e);
                    }
                    super.onCallStateChanged(state, incomingNumber);
                }
            };
            if (telephonyManager == null) {
                telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                if (telephonyManager != null) {
                    telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
                }
            }
        }
    }

    private void registerHeadsetPlugReceiver() {
        if (headsetPlugReceiver == null) {
            headsetPlugReceiver = new HeadsetPlugReceiver();
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("android.intent.action.HEADSET_PLUG");
            registerReceiver(headsetPlugReceiver, intentFilter);
        }
    }

    private void registerRemoteClient() {
        if (remoteControlClient == null) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                remoteControlClient = null;
                return;
            }
            ComponentName remoteComponentName = new ComponentName(getApplicationContext(), LockScreenReceiver.class);
            try {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                // Request audio focus for playback
                int result = audioManager.requestAudioFocus(
                        this,
                        // Use the music stream.
                        AudioManager.STREAM_MUSIC,
                        // Request permanent focus.
                        AudioManager.AUDIOFOCUS_GAIN);

                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    audioManager.registerMediaButtonEventReceiver(remoteComponentName);
                    Logger.log(LOG_TAG, "AudioManager.AUDIOFOCUS_REQUEST_GRANTED ok");
                    // Start playback.
                } else {
                    Logger.log(LOG_TAG, "AudioManager.AUDIOFOCUS_REQUEST_GRANTED fail");
                    remoteControlClient = null;
                    return;
                }
                //audioManager.registerMediaButtonEventReceiver(remoteComponentName);
                Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                mediaButtonIntent.setComponent(remoteComponentName);
                PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, 0);
                remoteControlClient = new RemoteControlClient(mediaPendingIntent);
                audioManager.registerRemoteControlClient(remoteControlClient);

                remoteControlClient.setTransportControlFlags(
                        RemoteControlClient.FLAG_KEY_MEDIA_PLAY |
                                RemoteControlClient.FLAG_KEY_MEDIA_PAUSE |
                                RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE |
                                RemoteControlClient.FLAG_KEY_MEDIA_STOP |
                                RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS |
                                RemoteControlClient.FLAG_KEY_MEDIA_NEXT);
                Logger.log(LOG_TAG, "registerRemoteClient ok");
            } catch (Exception e) {
                Logger.error(e);
            }
        }
    }

    private void sentNotificationInForeground() {
        try {
            if (currentTrack != null) {
                String title = currentTrack.getArtist() + " - " + currentTrack.getName();

                updateLockscreenMetadata(title);

                Intent notificationIntent = new Intent(this, MusicActivity.class);

                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
                PendingIntent pendingIntentPlayPause = PendingIntent.getBroadcast(this, 0, new Intent().setAction(NOTIFICATION_ACTION_PLAY_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT);
                PendingIntent pendingIntentStop = PendingIntent.getBroadcast(this, 0, new Intent().setAction(NOTIFICATION_ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT);
                PendingIntent pendingIntentPrev = PendingIntent.getBroadcast(this, 0, new Intent().setAction(NOTIFICATION_ACTION_PREV), PendingIntent.FLAG_UPDATE_CURRENT);
                PendingIntent pendingIntentNext = PendingIntent.getBroadcast(this, 0, new Intent().setAction(NOTIFICATION_ACTION_NEXT), PendingIntent.FLAG_UPDATE_CURRENT);

                NotificationCompat.Builder builder = new NotificationCompat.Builder(this);

                RemoteViews contentView = new RemoteViews(getPackageName(), R.layout.audio_service_notification);

                contentView.setTextViewText(R.id.trackName, currentTrack.getArtist() + " - " + currentTrack.getName());

                contentView.setOnClickPendingIntent(R.id.prev, pendingIntentPrev);

                contentView.setOnClickPendingIntent(R.id.pp, pendingIntentPlayPause);
                if (mediaPlayer != null) {
                    if (!mediaPlayer.isPlaying()) {
                        contentView.setImageViewResource(R.id.pp, R.mipmap.play);
                        setRemoteClientPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
                    } else {
                        contentView.setImageViewResource(R.id.pp, R.mipmap.pause);
                        setRemoteClientPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
                    }
                }

                contentView.setOnClickPendingIntent(R.id.next, pendingIntentNext);

                contentView.setOnClickPendingIntent(R.id.close, pendingIntentStop);

                builder.setContentIntent(pendingIntent);
                builder.setContent(contentView);
                builder.setCustomBigContentView(contentView);
                builder.setSmallIcon(R.mipmap.ic_music_note_black_24dp);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    MediaSessionCompat mMediaSession = new MediaSessionCompat(getApplicationContext(), "mMediaSessionTag");
                    builder.setStyle(new NotificationCompat.MediaStyle().setMediaSession(mMediaSession.getSessionToken()));
                    builder.setVisibility(Notification.VISIBILITY_PUBLIC);
                }
                builder.setPriority(Notification.PRIORITY_MAX);

                Notification notification = builder.build();

                String imageUrl = currentTrack.getUrlImage();
                if (imageUrl != null && !imageUrl.isEmpty()) {
                    Picasso.with(this)
                            .load(imageUrl)
                            .into(contentView, R.id.image, NOTIFICATION_ID, notification);
                }

                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Logger.error(e);
        }
    }

    private void updateLockscreenMetadata(String tittle) {
        if (remoteControlClient != null) {
            RemoteControlClient.MetadataEditor metadataEditor = remoteControlClient.editMetadata(true);
            metadataEditor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, tittle);
            metadataEditor.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, null);
            metadataEditor.apply();
        }
    }

    private void setRemoteClientPlaybackState(int playbackState) {
        if (remoteControlClient != null) {
            remoteControlClient.setPlaybackState(playbackState);
        }
    }

    private void finish() {
        Logger.log(LOG_TAG, "finish");
        stop();
        stopForeground(true);
        stopSelf();
    }

    class TimerTask extends java.util.TimerTask {

        @Override
        public void run() {

            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            int currentPosition = mediaPlayer.getCurrentPosition();
                            if (currentPosition > 0) {
                                sendBroadcastToActivity(EVENT.PROGRESS_UPDATE);
                            }
                        } catch (Exception e) {
                            Logger.error(e);
                        }
                    });
                }
            }
        }
    }
}