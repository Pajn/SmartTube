package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ErrorFixerControllerTest {
    private long mPositionMs;
    private long mDurationMs = 60_000;
    private int mRecoveryCount;
    private final PlaybackView mPlayer = (PlaybackView) Proxy.newProxyInstance(
            PlaybackView.class.getClassLoader(), new Class<?>[] { PlaybackView.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getPositionMs": return mPositionMs;
                    case "getDurationMs": return mDurationMs;
                    default: throw new AssertionError("Unexpected player call: " + method.getName());
                }
            });
    private final ErrorFixerController mController = new ErrorFixerController() {
        @Override
        public PlaybackView getPlayer() {
            return mPlayer;
        }

        @Override
        public void onLongBuffering() {
            mRecoveryCount++;
        }
    };

    @After
    public void tearDown() {
        mController.onEngineReleased();
    }

    @Test
    public void endThenSeekDoesNotRecoverFinishedVideo() {
        mController.onBuffering();
        mPositionMs = mDurationMs;
        mController.onPlayEnd();
        mController.onSeekEnd();
        assertRecoveryCountAfterTimeout(0);
    }

    @Test
    public void seekThenEndDoesNotRecoverFinishedVideo() {
        mPositionMs = mDurationMs;
        mController.onSeekEnd();
        mController.onPlayEnd();
        assertRecoveryCountAfterTimeout(0);
    }

    @Test
    public void endCancelsExistingWatchdog() {
        mController.onBuffering();
        mController.onPlayEnd();
        assertRecoveryCountAfterTimeout(0);
    }

    @Test
    public void seekingBackAfterEndStillDetectsStalls() {
        mPositionMs = mDurationMs;
        mController.onPlayEnd();
        mPositionMs = 10_000;
        mController.onSeekEnd();
        assertRecoveryCountAfterTimeout(1);
    }

    @Test
    public void unknownDurationStillDetectsStalls() {
        mDurationMs = -1;
        mController.onSeekEnd();
        assertRecoveryCountAfterTimeout(1);
    }

    @Test
    public void successfulSeekCancelsWatchdog() {
        mPositionMs = 10_000;
        mController.onSeekEnd();
        mController.onPlay();
        assertRecoveryCountAfterTimeout(0);
    }

    private void assertRecoveryCountAfterTimeout(int expected) {
        shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS);
        assertEquals(expected, mRecoveryCount);
    }
}
