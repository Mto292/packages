// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.Listener;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride;
import com.google.android.exoplayer2.trackselection.TrackSelectionParameters;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONObject;

import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class VideoPlayer {
    private static final String FORMAT_SS = "ss";
    private static final String FORMAT_DASH = "dash";
    private static final String FORMAT_HLS = "hls";
    private static final String FORMAT_OTHER = "other";

    private ExoPlayer exoPlayer;

    private Surface surface;

    private final TextureRegistry.SurfaceTextureEntry textureEntry;

    private QueuingEventSink eventSink;

    private final EventChannel eventChannel;

    private static final String USER_AGENT = "User-Agent";

    @VisibleForTesting
    boolean isInitialized = false;

    private final VideoPlayerOptions options;

    private DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory();

    VideoPlayer(Context context, EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry, String dataSource, String formatHint, @NonNull Map<String, String> httpHeaders, VideoPlayerOptions options) {
        this.eventChannel = eventChannel;
        this.textureEntry = textureEntry;
        this.options = options;

        ExoPlayer.Builder builder = new ExoPlayer.Builder(context);
        builder.setRenderersFactory(new DefaultRenderersFactory(context.getApplicationContext()).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON));

        ExoPlayer exoPlayer = builder.build();

        Uri uri = Uri.parse(dataSource);
        buildHttpDataSourceFactory(httpHeaders);
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);

        MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint);

        TrackSelectionParameters trackSelectionParameters = TrackSelectionParameters.getDefaults(context);
        exoPlayer.setTrackSelectionParameters(trackSelectionParameters);
        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();

        setUpVideoPlayer(exoPlayer, new QueuingEventSink());
    }

    // Constructor used to directly test members of this class.
    @VisibleForTesting
    VideoPlayer(ExoPlayer exoPlayer, EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry, VideoPlayerOptions options, QueuingEventSink eventSink, DefaultHttpDataSource.Factory httpDataSourceFactory) {
        this.eventChannel = eventChannel;
        this.textureEntry = textureEntry;
        this.options = options;
        this.httpDataSourceFactory = httpDataSourceFactory;

        setUpVideoPlayer(exoPlayer, eventSink);
    }

    @VisibleForTesting
    public void buildHttpDataSourceFactory(@NonNull Map<String, String> httpHeaders) {
        final boolean httpHeadersNotEmpty = !httpHeaders.isEmpty();
        final String userAgent = httpHeadersNotEmpty && httpHeaders.containsKey(USER_AGENT) ? httpHeaders.get(USER_AGENT) : "ExoPlayer";

        httpDataSourceFactory.setUserAgent(userAgent).setAllowCrossProtocolRedirects(true);

        if (httpHeadersNotEmpty) {
            httpDataSourceFactory.setDefaultRequestProperties(httpHeaders);
        }
    }

    private MediaSource buildMediaSource(Uri uri, DataSource.Factory mediaDataSourceFactory, String formatHint) {
        int type;
        if (formatHint == null) {
            type = Util.inferContentType(uri);
        } else {
            switch (formatHint) {
                case FORMAT_SS:
                    type = C.CONTENT_TYPE_SS;
                    break;
                case FORMAT_DASH:
                    type = C.CONTENT_TYPE_DASH;
                    break;
                case FORMAT_HLS:
                    type = C.CONTENT_TYPE_HLS;
                    break;
                case FORMAT_OTHER:
                    type = C.CONTENT_TYPE_OTHER;
                    break;
                default:
                    type = -1;
                    break;
            }
        }
        switch (type) {
            case C.CONTENT_TYPE_SS:
                return new SsMediaSource.Factory(new DefaultSsChunkSource.Factory(mediaDataSourceFactory), mediaDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_DASH:
                return new DashMediaSource.Factory(new DefaultDashChunkSource.Factory(mediaDataSourceFactory), mediaDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(mediaDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    private void setUpVideoPlayer(ExoPlayer exoPlayer, QueuingEventSink eventSink) {
        this.exoPlayer = exoPlayer;
        this.eventSink = eventSink;

        eventChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object o, EventChannel.EventSink sink) {
                eventSink.setDelegate(sink);
            }

            @Override
            public void onCancel(Object o) {
                eventSink.setDelegate(null);
            }
        });

        surface = new Surface(textureEntry.surfaceTexture());
        exoPlayer.setVideoSurface(surface);
        setAudioAttributes(exoPlayer, options.mixWithOthers);

        exoPlayer.addListener(new Listener() {
            private boolean isBuffering = false;

            @Override
            public void onCues(CueGroup cueGroup) {
                Player.Listener.super.onCues(cueGroup);
                if (cueGroup.cues.size() > 0) {
                    String txt = cueGroup.cues.get(0).text.toString();
                    System.out.println("SubTitle: " + txt);
                    Map<String, Object> event = new HashMap<>();
                    event.put("event", "cueUpdate");
                    event.put("cue", txt);
                    eventSink.success(event);
                }
            }

            public void setBuffering(boolean buffering) {
                if (isBuffering != buffering) {
                    isBuffering = buffering;
                    Map<String, Object> event = new HashMap<>();
                    event.put("event", isBuffering ? "bufferingStart" : "bufferingEnd");
                    eventSink.success(event);
                }
            }

            @Override
            public void onPlaybackStateChanged(final int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    setBuffering(true);
                    sendBufferingUpdate();
                } else if (playbackState == Player.STATE_READY) {
                    if (!isInitialized) {
                        isInitialized = true;
                        sendInitialized();
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    Map<String, Object> event = new HashMap<>();
                    event.put("event", "completed");
                    eventSink.success(event);
                }

                if (playbackState != Player.STATE_BUFFERING) {
                    setBuffering(false);
                }
            }

            @Override
            public void onPlayerError(@NonNull final PlaybackException error) {
                setBuffering(false);
                if (eventSink != null) {
                    eventSink.error("VideoError", "Video player had error " + error, null);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (eventSink != null) {
                    Map<String, Object> event = new HashMap<>();
                    event.put("event", "isPlayingStateUpdate");
                    event.put("isPlaying", isPlaying);
                    eventSink.success(event);
                }
            }
        });
    }

    void sendBufferingUpdate() {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "bufferingUpdate");
        List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
        // iOS supports a list of buffered ranges, so here is a list with a single range.
        event.put("values", Collections.singletonList(range));
        eventSink.success(event);
    }

    private static void setAudioAttributes(ExoPlayer exoPlayer, boolean isMixMode) {
        exoPlayer.setAudioAttributes(new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), !isMixMode);
    }

    void play() {
        exoPlayer.setPlayWhenReady(true);
    }

    void pause() {
        exoPlayer.setPlayWhenReady(false);
    }

    void setLooping(boolean value) {
        exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
    }

    void setVolume(double value) {
        float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
        exoPlayer.setVolume(bracketedValue);
    }

    void setPlaybackSpeed(double value) {
        // We do not need to consider pitch and skipSilence for now as we do not handle them and
        // therefore never diverge from the default values.
        final PlaybackParameters playbackParameters = new PlaybackParameters(((float) value));

        exoPlayer.setPlaybackParameters(playbackParameters);
    }

    void seekTo(int location) {
        exoPlayer.seekTo(location);
    }

    long getPosition() {
        return exoPlayer.getCurrentPosition();
    }

    public static final ImmutableList<Integer> SUPPORTED_TRACK_TYPES = ImmutableList.of(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT);

    private static final String[] TRACK_TYPE_STRINGS = {"Video", "Audio", "Text"};

    String getSupportTracks() {
        Tracks tracks = exoPlayer.getCurrentTracks();
        JSONObject obj = new JSONObject();
        String jsonStr = "";
        try {
            for (int i = 0; i < SUPPORTED_TRACK_TYPES.size(); i++) {
                JSONArray array = new JSONArray();
                @C.TrackType int trackType = SUPPORTED_TRACK_TYPES.get(i);
                for (Tracks.Group trackGroup : tracks.getGroups()) {
                    if (trackGroup.getType() == trackType) {
                        final Format format = trackGroup.getMediaTrackGroup().getFormat(0);
                        JSONObject formatObj = new JSONObject();
                        formatObj.put("id", format.id);
                        formatObj.put("label", format.label);
                        formatObj.put("language", format.language);
                        array.put(formatObj);
                    }
                }
                obj.put(TRACK_TYPE_STRINGS[trackType - 1], array);
                jsonStr = obj.toString();
            }
            jsonStr = obj.toString();
        } catch (Exception e) {
            return jsonStr;
        }
        return jsonStr;
    }


    void setTextTrack(String language) {
        try {
            exoPlayer.setTrackSelectionParameters(
                    exoPlayer
                            .getTrackSelectionParameters()
                            .buildUpon()
                            .setPreferredTextLanguage("tr")
                            .build());


/*            Tracks tracks = exoPlayer.getCurrentTracks();
            Tracks.Group selectedTrack = null;
            for (int i = 0; i < SUPPORTED_TRACK_TYPES.size(); i++) {
                for (Tracks.Group trackGroup : tracks.getGroups()) {
                    for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {

                    }
                    if (trackGroup.getType() == C.TRACK_TYPE_TEXT && Objects.equals(trackGroup.getMediaTrackGroup().getFormat(0).language, language)) {
                        selectedTrack = trackGroup;
                    }
                }
            }

            builder.setTrackTypeDisabled(3, false);
            builder.clearOverridesOfType(3);

            TrackGroup mediaTrackGroup = selectedTrack.getMediaTrackGroup();
            int trackIndex = mediaTrackGroup.trackIndex;
            Map<TrackGroup, TrackSelectionOverride> overrides = new HashMap<>();
            overrides.put(
                    selectedTrack,
                    new TrackSelectionOverride(selectedTrack, ImmutableList.of(trackIndex)));

            Map<TrackGroup, TrackSelectionOverride> overrides = trackSelectionDialog.getOverrides(trackType);
            for (TrackSelectionOverride override : overrides.values()) {
                builder.addOverride(override);
            }*/

        } catch (Exception e) {
        }
    }

    @SuppressWarnings("SuspiciousNameCombination")
    @VisibleForTesting
    void sendInitialized() {
        if (isInitialized) {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "initialized");
            event.put("duration", exoPlayer.getDuration());

            if (exoPlayer.getVideoFormat() != null) {
                Format videoFormat = exoPlayer.getVideoFormat();
                int width = videoFormat.width;
                int height = videoFormat.height;
                int rotationDegrees = videoFormat.rotationDegrees;
                // Switch the width/height if video was taken in portrait mode
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    width = exoPlayer.getVideoFormat().height;
                    height = exoPlayer.getVideoFormat().width;
                }
                event.put("width", width);
                event.put("height", height);

                // Rotating the video with ExoPlayer does not seem to be possible with a Surface,
                // so inform the Flutter code that the widget needs to be rotated to prevent
                // upside-down playback for videos with rotationDegrees of 180 (other orientations work
                // correctly without correction).
                if (rotationDegrees == 180) {
                    event.put("rotationCorrection", rotationDegrees);
                }
            }

            eventSink.success(event);
        }
    }

    void dispose() {
        if (isInitialized) {
            exoPlayer.stop();
        }
        textureEntry.release();
        eventChannel.setStreamHandler(null);
        if (surface != null) {
            surface.release();
        }
        if (exoPlayer != null) {
            exoPlayer.release();
        }
    }
}
