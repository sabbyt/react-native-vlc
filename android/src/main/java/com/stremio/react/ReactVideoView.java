package com.stremio.react;

import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.AndroidUtil;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

// This originally extended ScalableVideoView, which extends TextureView
// Now we extend SurfaceView (https://github.com/crosswalk-project/crosswalk-website/wiki/Android-SurfaceView-vs-TextureView)
public class ReactVideoView extends SurfaceView implements IVLCVout.Callback, MediaPlayer.EventListener, LifecycleEventListener {

    public enum Events {
        EVENT_LOAD_START("onVideoLoadStart"),
        EVENT_LOAD("onVideoLoad"),
        EVENT_ERROR("onVideoError"),
        EVENT_PROGRESS("onVideoProgress"),
        EVENT_SEEK("onVideoSeek"),
        EVENT_END("onVideoEnd"),
        EVENT_STALLED("onPlaybackStalled"),
        EVENT_RESUME("onPlaybackResume");
        private final String mName;

        Events(final String name) {
            mName = name;
        }

        @Override
        public String toString() {
            return mName;
        }
    }

    private static final String TAG = "RCTVLC";

    public static final String EVENT_PROP_DURATION = "duration";
    //public static final String EVENT_PROP_PLAYABLE_DURATION = "playableDuration";
    public static final String EVENT_PROP_CURRENT_TIME = "currentTime";
    public static final String EVENT_PROP_SEEK_TIME = "seekTime";
    public static final String EVENT_PROP_WIDTH = "width";
    public static final String EVENT_PROP_HEIGHT = "height";

    public static final String EVENT_PROP_ERROR = "error";
    public static final String EVENT_PROP_WHAT = "what";
    public static final String EVENT_PROP_EXTRA = "extra";

    public static final String EVENT_PROP_BUFFERING_PROG = "progress";

    private ThemedReactContext mThemedReactContext;
    private RCTEventEmitter mEventEmitter;

    private String mSrcUriString = null;
    private boolean mPaused = false;
    private float mVolume = 1.0f;

    private LibVLC libvlc;
    private MediaPlayer mMediaPlayer = null;
    private int mVideoWidth;
    private int mVideoHeight;

    public ReactVideoView(ThemedReactContext themedReactContext) {
        super(themedReactContext);

        mThemedReactContext = themedReactContext;
        mEventEmitter = themedReactContext.getJSModule(RCTEventEmitter.class);
        themedReactContext.addLifecycleEventListener(this);

        createPlayer();
    }

    private void createPlayer() {
        if (mMediaPlayer != null) return;

        try {
            // Create LibVLC
            ArrayList<String> options = new ArrayList<String>();
            //options.add("--subsdec-encoding <encoding>");
            //options.add("--aout=opensles");
            options.add("--audio-time-stretch"); // time stretching
            options.add("-vvv"); // verbosity
            options.add("--http-reconnect");
            options.add("--network-caching="+(8*1000));
            libvlc = new LibVLC(options);
            this.getHolder().setKeepScreenOn(true);

            // Create media player
            mMediaPlayer = new MediaPlayer(libvlc);
            mMediaPlayer.setEventListener(this);

            // Set up video output
            final IVLCVout vout = mMediaPlayer.getVLCVout();
            vout.setVideoView(this);
            vout.addCallback(this);
            vout.attachViews();
        } catch (Exception e) {
            // TODO onError
        }
    }

    private void releasePlayer() {
        if (libvlc == null) return;
        mMediaPlayer.stop();
        final IVLCVout vout = mMediaPlayer.getVLCVout();
        vout.removeCallback(this);
        vout.detachViews();
        libvlc.release();
        libvlc = null;
    }

   private void setSize(int width, int height) {
        mVideoWidth = width;
        mVideoHeight = height;
        if (mVideoWidth * mVideoHeight <= 1)
            return;

        /*
        // get screen size
        int w = getWindow().getDecorView().getWidth();
        int h = getWindow().getDecorView().getHeight();

        // getWindow().getDecorView() doesn't always take orientation into
        // account, we have to correct the values
        boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        if (w > h && isPortrait || w < h && !isPortrait) {
            int i = w;
            w = h;
            h = i;
        }

        float videoAR = (float) mVideoWidth / (float) mVideoHeight;
        float screenAR = (float) w / (float) h;

        if (screenAR < videoAR)
            h = (int) (w / videoAR);
        else
            w = (int) (h * videoAR);
        */

        // force surface buffer size
        this.getHolder().setFixedSize(mVideoWidth, mVideoHeight);

        // set display size
        /*
        LayoutParams lp = this.getLayoutParams();
        lp.width = w;
        lp.height = h;
        this.setLayoutParams(lp);
        this.invalidate();
        */
    }

    @Override
    public void onNewLayout(IVLCVout vout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        if (width * height == 0)
            return;

        // store video size
        mVideoWidth = width;
        mVideoHeight = height;
        setSize(mVideoWidth, mVideoHeight);
    }

    public void setSrc(final String uriString) {

        mSrcUriString = uriString;

        createPlayer();

        Media m = new Media(libvlc, Uri.parse(uriString));
        mMediaPlayer.setMedia(m);
        //mMediaPlayer.play(); // Maybe it's better to call that only through updateModifiers()

        WritableMap src = Arguments.createMap();
        src.putString(ReactVideoViewManager.PROP_SRC_URI, uriString);
        WritableMap event = Arguments.createMap();
        event.putMap(ReactVideoViewManager.PROP_SRC, src);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_LOAD_START.toString(), event);
    }

    public void setPausedModifier(final boolean paused) {
        mPaused = paused;

        if (mPaused) {
            mMediaPlayer.pause();
        } else {
            mMediaPlayer.play();
        }
    }

    public void setVolumeModifier(final float volume) {
        mVolume = volume;
        mMediaPlayer.setVolume((int) volume * 200);
    }

    public void applyModifiers() {
        setPausedModifier(mPaused);
    }

    public void seekTo(int msec) {
        WritableMap event = Arguments.createMap();
        event.putDouble(EVENT_PROP_CURRENT_TIME, mMediaPlayer.getTime() / 1000.0);
        event.putDouble(EVENT_PROP_SEEK_TIME, msec / 1000.0);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_SEEK.toString(), event);

        mMediaPlayer.setTime(msec);
    }

    @Override
    public void onHardwareAccelerationError(IVLCVout vout) {
        // Handle errors with hardware acceleration
        WritableMap error = Arguments.createMap();
        error.putString(EVENT_PROP_WHAT, "Error with hardware acceleration");
        WritableMap event = Arguments.createMap();
        event.putMap(EVENT_PROP_ERROR, error);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_ERROR.toString(), event);
    }


    @Override
    public void onEvent(MediaPlayer.Event ev) {
        WritableMap event = Arguments.createMap();

        switch(ev.type) {
            case MediaPlayer.Event.EndReached:
                mEventEmitter.receiveEvent(getId(), Events.EVENT_END.toString(), null);
                releasePlayer();
                break;
            case MediaPlayer.Event.EncounteredError:
                WritableMap error = Arguments.createMap();
                error.putString(EVENT_PROP_WHAT, "MediaPlayer.Event.EncounteredError");
                // TODO: more info
                event.putMap(EVENT_PROP_ERROR, error);
                mEventEmitter.receiveEvent(getId(), Events.EVENT_ERROR.toString(), event);
                releasePlayer();
                break;
            case MediaPlayer.Event.Buffering:
                int buffering = ev.getBuffering();
                event.putInt(EVENT_PROP_BUFFERING_PROG, buffering);
                mEventEmitter.receiveEvent(getId(), buffering == 100 ? Events.EVENT_RESUME.toString() : Events.EVENT_STALLED.toString(), event);
                break;
            case MediaPlayer.Event.Playing:
                this.getHolder().setKeepScreenOn(true);                
                break;
            case MediaPlayer.Event.Paused:
                this.getHolder().setKeepScreenOn(false);
                break;
            case MediaPlayer.Event.Stopped:
                this.getHolder().setKeepScreenOn(false);
                break;
            case MediaPlayer.Event.Opening:
                event.putDouble(EVENT_PROP_DURATION, mMediaPlayer.getLength() / 1000.0);
                event.putDouble(EVENT_PROP_CURRENT_TIME, mMediaPlayer.getTime() / 1000.0);

                mEventEmitter.receiveEvent(getId(), Events.EVENT_LOAD.toString(), event);

                applyModifiers();
                break;
            case MediaPlayer.Event.TimeChanged:
                event.putDouble(EVENT_PROP_CURRENT_TIME, mMediaPlayer.getTime() / 1000.0);
                mEventEmitter.receiveEvent(getId(), Events.EVENT_PROGRESS.toString(), event);
                break;
            default:
                break;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setSrc(mSrcUriString);
    }

    @Override
    public void onHostPause() {
        if (mMediaPlayer != null) {
            mMediaPlayer.pause();
        }
    }

    @Override
    public void onHostResume() {
    }

    @Override
    public void onHostDestroy() {
    }
}
