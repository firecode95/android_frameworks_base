/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.policy.impl;

import dalvik.system.DexClassLoader;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.security.KeyStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.text.TextUtils;
import android.util.Log;
import com.android.internal.R;
import com.android.internal.widget.LinearLayoutWithDefaultTouchRecepient;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockPatternView.Cell;

import java.util.List;

// For the finger keyguard
import android.os.Handler;
import android.os.Vibrator;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemProperties;

import com.authentec.amjni.AM_STATUS;
import com.authentec.amjni.AuthLog;
import com.authentec.amjni.TSM;
import com.authentec.TrueSuiteMobile.GfxEngineRelayService;


/**
 * This is the screen that shows the 9 circle unlock widget and instructs
 * the user how to unlock their device, or make an emergency call.
 */
class FingerUnlockScreen extends LinearLayoutWithDefaultTouchRecepient
        implements KeyguardScreen,
        GfxEngineRelayService.Receiver {

    private static final boolean DEBUG = true;
    private static final String TAG = "FingerUnlockScreen";

    // how long we stay awake once the user is ready to enter a pattern
    private static final int UNLOCK_FINGER_WAKE_INTERVAL_MS = 7000;

    private int mFailedPatternAttemptsSinceLastTimeout = 0;
    private int mTotalFailedPatternAttempts = 0;
    private CountDownTimer mCountdownTimer = null;
    public String msg = "";
    public boolean delay_sw = false;

    private CountDownTimer mCountdownTimerToast = null;
    private LockPatternUtils mLockPatternUtils;
    private KeyguardUpdateMonitor mUpdateMonitor;
    private KeyguardScreenCallback mCallback;

    /**
     * whether there is a fallback option available when the pattern is forgotten.
     */
    private boolean mEnableFallback;

    private KeyguardStatusViewManager mKeyguardStatusViewManager;
    private LockPatternView mLockPatternView;

    private static boolean m_bVerifyied;

    // Do we still hold a wakelock?
    // Initialize this value to "true" since the screen is off at the very beginning.
    private static boolean m_bPaused = true;

    // The key guard would be put into lockout state every four bad swipes.
    private static boolean m_bAttemptLockout;

    // A flag to indicate if you are currently connected to the GfxEngine or not.
    private boolean m_bGfxEngineAttached;

    // A flag to indicate that there is a delayed #cancel command to send.
    private boolean m_bSendDelayedCancel;

    // Make sure that you do not double-cancel.
    private boolean m_bCancelHasBeenSent;

    // A flag to indicate that there is a successive power on to the previous power off.
    // If the previous #cancel command has not terminate the verify runner yet, set this
    // flag to let the verify runner start again.
    private boolean m_bStartAgain;

    // Below four are related with tactile feedback...
    // A flag to indicate if tactile feedback is supported.
    private boolean mTactileFeedbackEnabled = false;
    // Vibrator pattern for creating a tactile bump
    private static final long[] DEFAULT_VIBE_PATTERN = {0, 1, 40, 41};
    // Vibrator for creating tactile feedback
    private Vibrator vibe;
    // Vibration pattern, either default or customized
    private long[] mVibePattern;


    /**
     * Keeps track of the last time we poked the wake lock during dispatching
     * of the touch event, initalized to something gauranteed to make us
     * poke it when the user starts drawing the pattern.
     * @see #dispatchTouchEvent(android.view.MotionEvent)
     */
    private long mLastPokeTime = -UNLOCK_FINGER_WAKE_INTERVAL_MS;

    /**
     * Useful for clearing out the wrong pattern after a delay
     */
    private Runnable mCancelPatternRunnable = new Runnable() {
        public void run() {
            mLockPatternView.clearPattern();
        }
    };

    private final OnClickListener mForgotPatternClick = new OnClickListener() {
        public void onClick(View v) {
            mCallback.forgotPattern(true);
        }
    };

    private Button mForgotPatternButton;
    private int mCreationOrientation;

    static Thread mExecutionThread = null;
    private Thread mUiThread;
    private boolean mbFeedbackDelivered = false;
    private VerifyRunner mVerifyRunner = new VerifyRunner();
    private Context m_Context;

    /** High level access to the power manager for WakeLocks */
    private PowerManager mPM;

    /**
     * Used to keep the device awake while the keyguard is showing, i.e for
     * calls to {@link #pokeWakelock()}
     * Note: Unlike the mWakeLock in KeyguardViewMediator (which has the
     *       ACQUIRE_CAUSES_WAKEUP flag), this one doesn't actually turn
     *       on the illumination. Instead, they cause the illumination to
     *       remain on once it turns on.
     */
    private PowerManager.WakeLock mWakeLock;

    /**
     * Does not turn on screen, used to keep the device awake until the
     * fingerprint verification is ready. The KeyguardViewMediator only
     * poke the wake lock for 5 seconds, which is not enough for the
     * initialization of the fingerprint verification.
     */
    private PowerManager.WakeLock mHandOffWakeLock;

    private int mWakelockSequence;

    // used for handler messages
    private static final int TIMEOUT = 1;

    enum FooterMode {
        Normal,
        ForgotLockPattern,
        VerifyUnlocked
    }

    private void hideForgotPatternButton() {
        mForgotPatternButton.setVisibility(View.GONE);
    }

    private void showForgotPatternButton() {
        mForgotPatternButton.setVisibility(View.VISIBLE);
    }

    private void updateFooter(FooterMode mode) {
        switch (mode) {
            case Normal:
                if (DEBUG) Log.d(TAG, "mode normal");
                hideForgotPatternButton();
                break;
            case ForgotLockPattern:
                if (DEBUG) Log.d(TAG, "mode ForgotLockFinger");
                showForgotPatternButton();
                break;
            case VerifyUnlocked:
                if (DEBUG) Log.d(TAG, "mode VerifyUnlocked");
                hideForgotPatternButton();
        }
    }

    /**
     * @param context The context.
     * @param configuration
     * @param lockPatternUtils Used to lookup lock pattern settings.
     * @param updateMonitor Used to lookup state affecting keyguard.
     * @param callback Used to notify the manager when we're done, etc.
     * @param totalFailedAttempts The current number of failed attempts.
     * @param enableFallback True if a backup unlock option is available when the user has forgotten
     *        their pattern (e.g they have a google account so we can show them the account based
     *        backup option).
     */
    FingerUnlockScreen(Context context,
                 Configuration configuration, LockPatternUtils lockPatternUtils,
                 KeyguardUpdateMonitor updateMonitor,
                 KeyguardScreenCallback callback,
                 int totalFailedAttempts) {
        super(context);

        m_Context = context;

        /* goToSleep of PowerManagerService class would cancel all of the wake locks. */
        mPM = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPM.newWakeLock(
                PowerManager.FULL_WAKE_LOCK,
                "FpKeyguard");
        mWakeLock.setReferenceCounted(false);
        mHandOffWakeLock = mPM.newWakeLock(
                PowerManager.FULL_WAKE_LOCK,
                "FpKeyguardHandOff");
        mHandOffWakeLock.setReferenceCounted(false);

        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = updateMonitor;
        mCallback = callback;
        mTotalFailedPatternAttempts = totalFailedAttempts;
        mFailedPatternAttemptsSinceLastTimeout =
            totalFailedAttempts % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;

        if (DEBUG) Log.d(TAG,
            "UnlockScreen() ctor: totalFailedAttempts="
                 + totalFailedAttempts + ", mFailedPat...="
                 + mFailedPatternAttemptsSinceLastTimeout
                 );

        m_bVerifyied = false;
        m_bAttemptLockout = false;

        m_bGfxEngineAttached = false;
        m_bSendDelayedCancel = false;
        m_bCancelHasBeenSent = false;
        m_bStartAgain = false;

        mTactileFeedbackEnabled = mLockPatternUtils.isTactileFeedbackEnabled();
        if (mTactileFeedbackEnabled) {
            if (DEBUG) Log.d(TAG, "Create a vibrator");
            vibe = new Vibrator();
        }

        //TODO
        runOnUiThread(new Runnable() {
            public void run() {
                mKeyguardStatusViewManager.setInstructionText("Initializing fingerprint...");
                mKeyguardStatusViewManager.updateStatusLines(true);
                }
        });

        mCreationOrientation = configuration.orientation;

        LayoutInflater inflater = LayoutInflater.from(context);

        if (mCreationOrientation != Configuration.ORIENTATION_LANDSCAPE) {
            Log.d(TAG, "portrait mode");
            inflater.inflate(R.layout.keyguard_screen_finger_portrait, this, true);
        } else {
            Log.d(TAG, "landscape mode");
            inflater.inflate(R.layout.keyguard_screen_finger_landscape, this, true);
        }
        LockScreen.setBackground(context, (ViewGroup) findViewById(R.id.root));
        mKeyguardStatusViewManager = new KeyguardStatusViewManager(this, mUpdateMonitor,
                mLockPatternUtils, mCallback, true);

        mLockPatternView = (LockPatternView) findViewById(R.id.lockPattern);

        mForgotPatternButton = (Button) findViewById(R.id.forgotPatternButton);
        mForgotPatternButton.setText(R.string.lockscreen_forgot_finger_button_text);
        mForgotPatternButton.setOnClickListener(mForgotPatternClick);

        // make it so unhandled touch events within the unlock screen go to the
        // lock pattern view.
        setDefaultTouchRecepient(mLockPatternView);


        // stealth mode will be the same for the life of this screen
        //mLockPatternView.setInStealthMode(!mLockPatternUtils.isVisiblePatternEnabled());

        // vibrate mode will be the same for the life of this screen
        //mLockPatternView.setTactileFeedbackEnabled(mLockPatternUtils.isTactileFeedbackEnabled());

        GfxEngineRelayService.setLocalReceiver(this);

        // assume normal footer mode for now
        updateFooter(FooterMode.Normal);

        setFocusableInTouchMode(true);
    }

    public void setEnableFallback(boolean state) {
        if (DEBUG) Log.d(TAG, "setEnableFallback(" + state + ")");
        mEnableFallback = state;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // as long as the user is entering a pattern (i.e sending a touch
        // event that was handled by this screen), keep poking the
        // wake lock so that the screen will stay on.
        final boolean result = super.dispatchTouchEvent(ev);
        if (result &&
                ((SystemClock.elapsedRealtime() - mLastPokeTime)
                        >  (UNLOCK_FINGER_WAKE_INTERVAL_MS - 100))) {
            mLastPokeTime = SystemClock.elapsedRealtime();
        }
        return result;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (LockPatternKeyguardView.DEBUG_CONFIGURATION) {
            Log.v(TAG, "***** FINGERPRINT ATTACHED TO WINDOW");
            Log.v(TAG, "Cur orient=" + mCreationOrientation
                    + ", new config=" + getResources().getConfiguration());
        }
        if (getResources().getConfiguration().orientation != mCreationOrientation) {
            mCallback.recreateMe(getResources().getConfiguration());
        }
    }


    /** {@inheritDoc} */
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (LockPatternKeyguardView.DEBUG_CONFIGURATION) {
            Log.v(TAG, "***** FINGERPRINT CONFIGURATION CHANGED");
            Log.v(TAG, "Cur orient=" + mCreationOrientation
                    + ", new config=" + getResources().getConfiguration());
        }
        if (newConfig.orientation != mCreationOrientation) {
            if (DEBUG) Log.d(TAG,"Orientation changed, recreateMe");
            mCallback.recreateMe(newConfig);
        }
    }

    /** {@inheritDoc} */
    public void onKeyboardChange(boolean isKeyboardOpen) {}

    /** {@inheritDoc} */
    public boolean needsInput() {
        return false;
    }

    /** {@inheritDoc} */
    public void onPause() {
        if (DEBUG) Log.d(TAG,"onPause");

        if (mWakeLock.isHeld()) {
            /* Must release since it is a screen lock. */
            mHandler.removeMessages(TIMEOUT);
            mWakeLock.release();
        }

        if (mHandOffWakeLock.isHeld()) {
            /* Must release since it is a screen lock. */
            mHandOffWakeLock.release();
        }

        m_bVerifyied = false;
        m_bPaused = true;
        m_bStartAgain = false;
        if (mCountdownTimer != null) {
            mCountdownTimer.cancel();
            mCountdownTimer = null;
        }
        if (mCountdownTimerToast != null) {
            mCountdownTimerToast.cancel();
            mCountdownTimerToast = null;
        }
        if (mExecutionThread != null && mExecutionThread.isAlive()) {
            if (!m_bGfxEngineAttached) {
                if (DEBUG) Log.d(TAG,"onPause send cancel delayed");
                m_bSendDelayedCancel = true;
            } else {
                if (DEBUG) Log.d(TAG,"onPause send cancel");
                if (!m_bCancelHasBeenSent)
                {
                    GfxEngineRelayService.queueEvent("#cancel");
                    m_bCancelHasBeenSent = true;
                }
            }
        }
        mKeyguardStatusViewManager.onPause();
    }

    /** {@inheritDoc} */
    public void onResume() {
        // reset status
        mKeyguardStatusViewManager.onResume();

        if ((mExecutionThread!=null)&&(mExecutionThread.isAlive())) {
            if (DEBUG) Log.d(TAG,"resumeViews: Thread in execution");
            return;
        }

        if (!mLockPatternUtils.savedFingerExists()) {
            if (DEBUG) Log.d(TAG,"resumeViews: No saved finger");
            // By design, this situation should never happen.
            // If finger lock is in use, we should only allow the finger settings menu
            // to delete all enrolled fingers. Other applications, like TSMDemo, should
            // not get access to the database.
            //
            // Simply disable the finger lock and exit.
            mLockPatternUtils.setLockFingerEnabled(false);
            return;
        }

        if (DEBUG) Log.d(TAG,"resumeViews: m_bVerifyied=" + m_bVerifyied);

        // show "forgot pattern?" button if we have an alternate authentication method
        if (mCallback.doesFallbackUnlockScreenExist()) {
            showForgotPatternButton();
        } else {
            hideForgotPatternButton();
        }

        // if the user is currently locked out, enforce it.
        long deadline = mLockPatternUtils.getLockoutAttemptDeadline();
        if (deadline != 0) {
            if (DEBUG) Log.d(TAG,"resumeViews: In lockout state");
            handleAttemptLockout(deadline);
        } else {
            // The onFinish() method of the CountDownTimer object would not be
            // called when the screen is off. So we need to reset the m_bAttemptLockout
            // if the lockout has finished.
            m_bAttemptLockout = false;
        }

        // the footer depends on how many total attempts the user has failed
        if (mCallback.isVerifyUnlockOnly()) {
            updateFooter(FooterMode.VerifyUnlocked);
        } else if (mEnableFallback &&
                (mTotalFailedPatternAttempts >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT)) {
            updateFooter(FooterMode.ForgotLockPattern);
        } else {
            updateFooter(FooterMode.Normal);
        }

        // Launch a TSM Verify thread when the screen is turned on.
        if (mPM.isScreenOn()) {

        if (DEBUG) Log.d(TAG,"onScreenOn()");

        m_bPaused = false;
        if (m_bSendDelayedCancel) {
            /* If "cancel" has not been sent out, simply ignore it. */
            m_bSendDelayedCancel = false;
        }

        /* Thread in execution, no need to create & start again. */
        if ((mExecutionThread!=null)&&(mExecutionThread.isAlive())) {
            if (DEBUG) Log.d(TAG,"onScreenOn: Thread in execution");
            if (m_bCancelHasBeenSent) {
                /* If "cancel" has been sent, set this flag to indicate the verify runner to start again. */
                m_bStartAgain = true;
            }
            return;
        }

        if (mLockPatternUtils.isLockFingerEnabled()&&!m_bVerifyied) {
            /**
             * acquire the handoff lock that will keep the cpu running. this will
             * be released once the fingerprint keyguard has set the verification up
             * and poked the mWakelock of itself (not the one of the KeyguradViewMediator).
             */
            mHandOffWakeLock.acquire();

            // Create a thread for verification.
            mExecutionThread = new Thread(mVerifyRunner);

            // Only start the verification when the screen is not in lockout state.
            if (!m_bAttemptLockout)
            {
                mExecutionThread.start();
            }
            else
            {
                // Release the wakelock early if the screen is in lockout state now.
                pokeWakelock(1000);
            }
        }
    }
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        if (mExecutionThread != null && mExecutionThread.isAlive()) {
            if (!m_bGfxEngineAttached) {
                if (DEBUG) Log.d(TAG,"cleanUp send cancel delayed");
                m_bSendDelayedCancel = true;
            } else {
                if (DEBUG) Log.d(TAG,"cleanUp send cancel");
                if (!m_bCancelHasBeenSent)
                {
                    GfxEngineRelayService.queueEvent("#cancel");
                    m_bCancelHasBeenSent = true;
                }
            }
        }

        // must make sure that the verify runner has terminated.
        while (mExecutionThread != null && mExecutionThread.isAlive()) {
            try {
                // Set a flag to indicate the verify runner to terminate itself.
                m_bPaused = true;
                mUiThread.sleep(50);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        mUpdateMonitor.removeCallback(this);
        mLockPatternUtils = null;
        mUpdateMonitor = null;
        mCallback = null;
        //mLockPatternView.setOnPatternListener(null);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            // when timeout dialog closes we want to update our state
            //onResume();
            if (DEBUG) Log.d(TAG,"onWindowFocusChanged");
            onResume();

            // Start the verification if screen is on.
            if (mPM.isScreenOn()) {
                if ( (!m_bAttemptLockout) && (!m_bPaused) &&
                     (mExecutionThread != null) && (!(mExecutionThread.isAlive())) )
                {
                    if (DEBUG) Log.d(TAG,"Screen is on, start LAP verification");
                    /**
                     * acquire the handoff lock that will keep the cpu running. this will
                     * be released once the fingerprint keyguard has set the verification up
                     * and poked the mWakelock of itself (not the one of the KeyguradViewMediator).
                     */
                    mHandOffWakeLock.acquire();

                    if (mExecutionThread.getState() == Thread.State.TERMINATED)
                    {
                        // If the thread state is TERMINATED, it cannot be start() again.
                        // Create a thread for verification.
                        mExecutionThread = new Thread(mVerifyRunner);
                        mExecutionThread.start();
                    }
                    else
                    {
                        if (DEBUG) Log.d(TAG,"Verify thread exists, just start it");
                        mExecutionThread.start();
                    }
                }
            }
        }
    }

    public final void runOnUiThread(Runnable action) {
        if (Thread.currentThread() != mUiThread) {
            mHandler .post(action);
        } else {
            action.run();
        }
    }

    private void pokeWakelock(int holdMs) {
        synchronized (this) {
            if (DEBUG) Log.d(TAG, "pokeWakelock(" + holdMs + ")");
            mWakeLock.acquire();
            mHandler.removeMessages(TIMEOUT);
            mWakelockSequence++;
            Message msg = mHandler.obtainMessage(TIMEOUT, mWakelockSequence, 0);
            mHandler.sendMessageDelayed(msg, holdMs);
        }

        if (mHandOffWakeLock.isHeld()) {
           /**
            * The fingerprint keyguard has been set up, and has poked the keyguard
            * main wake lock. It's time to release the handoff wake lock.
            */
           mHandOffWakeLock.release();
        }
    }

    /**
     * This handler will be associated with the policy thread, which will also
     * be the UI thread of the keyguard.  Since the apis of the policy, and therefore
     * this class, can be called by other threads, any action that directly
     * interacts with the keyguard ui should be posted to this handler, rather
     * than called directly.
     */
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case TIMEOUT:
                    handleTimeout(msg.arg1);
                    return ;
            }
        }
    };

    /**
     * Handles the message sent by {@link #pokeWakelock}
     * @param seq used to determine if anything has changed since the message
     *   was sent.
     * @see #TIMEOUT
     */
    private void handleTimeout(int seq) {
        synchronized (this) {
            if (DEBUG) Log.d(TAG, "handleTimeout");
            if (seq == mWakelockSequence) {
                mWakeLock.release();
            }
        }
    }

    /**
      * Provides a Runnable class to handle the finger
      * verification
      */
    private class VerifyRunner implements Runnable {
        public void run() {
            int iResult = 0;
            boolean bRetryAfterLockout = false;

            m_bVerifyied = false;

            // A trick to dismiss the timeout dialog.
            mCallback.isVerifyUnlockOnly();

            try {
                while (true) {
                    if (m_bPaused) {
                        if (DEBUG) Log.d(TAG,"VerifyRunner: paused before the verify() call.");
                        /* Do not call the verify interface if the "cancel" comes before it starts. */
                        if (AM_STATUS.eAM_STATUS_OK == iResult) {
                            /* The result should never be eAM_STATUS_OK. */
                            iResult = AM_STATUS.eAM_STATUS_USER_CANCELED;
                        }
                        break;
                    }

                    iResult = TSM.LAP(m_Context).waitForUI().verify().exec();
                    if (DEBUG) Log.d(TAG,"Verify result=" + iResult);
                    if (AM_STATUS.eAM_STATUS_CREDENTIAL_LOCKED == iResult) {
                        AuthLog.error(TAG, "Credential locked!");
                        runOnUiThread(new Runnable() {
                            public void run() {
                                msg = getContext().getString(R.string.keyguard_finger_failed_to_unlock);
                                msghandlerdelayed(msg);
                            }
                        });
                    } else if (AM_STATUS.eAM_STATUS_USER_CANCELED == iResult) {
                        if (m_bStartAgain) {
                            AuthLog.error(TAG, "Cancel OK, continue because of the successive launch.");
                            m_bStartAgain = false;
                            m_bPaused = false;
                        } else {
                            break;
                        }
                    } else {
                        // Terminate the current thread for all other cases.
                        break;
                    }

                    if (m_bPaused) {
                        /* Break immediatly without the sleep. */
                        if (DEBUG) Log.d(TAG,"VerifyRunner: paused after the verify() call.");
                        break;
                    }

                    // Give other tasks a chance.
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            switch (iResult)
            {
                case AM_STATUS.eAM_STATUS_OK:
                    m_bVerifyied = true;
                    if (DEBUG) Log.d(TAG,"keyguardDone, m_bVerifyied=" + m_bVerifyied);
                    mCallback.keyguardDone(true);
                    mHandler.removeMessages(TIMEOUT);
                    mWakeLock.release();
                    break;

                case AM_STATUS.eAM_STATUS_USER_CANCELED:
                    // Power off.
                    AuthLog.error(TAG, "Simulating device lock.\nYou may not cancel!");
                    break;

                case AM_STATUS.eAM_STATUS_CREDENTIAL_LOCKED:
                    // When m_bPaused becomes true.
                    bRetryAfterLockout = true;
                    break;

                case AM_STATUS.eAM_STATUS_NO_STORED_CREDENTIAL:
                    // Should never happen.
                    if (DEBUG) Log.d(TAG,"No stored credential");
                    bRetryAfterLockout = true;
                    break;

                case AM_STATUS.eAM_STATUS_LIBRARY_NOT_AVAILABLE:
                    AuthLog.error(TAG, "Library failed to load... cannot proceed!");
                    bRetryAfterLockout = true;
                    break;

                case AM_STATUS.eAM_STATUS_UI_TIMEOUT:
                    AuthLog.error(TAG, "UI timeout!");
                    runOnUiThread(new Runnable() {
                        public void run() {
                            msg = getContext().getString(R.string.keyguard_finger_ui_timeout);
                            msghandlerdelayed(msg);
                        }
                    });
                    bRetryAfterLockout = true;
                    break;

                case AM_STATUS.eAM_STATUS_UNKNOWN_ERROR:
                    AuthLog.error(TAG, "Unkown error!");
                    bRetryAfterLockout = true;
                    break;

                default:
                    AuthLog.error(TAG, "Other results: " + iResult);
                    bRetryAfterLockout = true;
                    break;
            }

            if (!m_bPaused && bRetryAfterLockout) {
                if (DEBUG) Log.d(TAG,"Error happens, retry after lock out");
                runOnUiThread(new Runnable() {
                    public void run() {
                        pokeWakelock(1000);
                        long deadline = mLockPatternUtils.setLockoutAttemptDeadline();
                        handleAttemptLockout(deadline);
                    }
                });
            }
        }
    }

    private void handleAttemptLockout(long elapsedRealtimeDeadline) {
        // Put the key guard into lockout state.
        m_bAttemptLockout = true;
        // Cancel the Verify thread.
        if (mExecutionThread != null && mExecutionThread.isAlive()) {
            if (!m_bGfxEngineAttached) {
                if (DEBUG) Log.d(TAG,"Lockout send cancel delayed");
                m_bSendDelayedCancel = true;
            } else {
                if (DEBUG) Log.d(TAG,"Lockout send cancel");
                if (!m_bCancelHasBeenSent)
                {
                    GfxEngineRelayService.queueEvent("#cancel");
                    m_bCancelHasBeenSent = true;
                }
            }
        }

        long elapsedRealtime = SystemClock.elapsedRealtime();
        mCountdownTimer = new CountDownTimer(elapsedRealtimeDeadline - elapsedRealtime, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) (millisUntilFinished / 1000);
                mKeyguardStatusViewManager.setInstructionText(getContext().getString(
                        R.string.lockscreen_too_many_failed_attempts_countdown,
                        secondsRemaining));
                mKeyguardStatusViewManager.updateStatusLines(true);
            }

            @Override
            public void onFinish() {
                // Put the key guard out of lockout state.
                if (DEBUG) Log.d(TAG,"handleAttemptLockout: onFinish");
                m_bAttemptLockout = false;
                mKeyguardStatusViewManager.setInstructionText(getContext().getString(
                        R.string.lockscreen_pattern_instructions));
                mKeyguardStatusViewManager.updateStatusLines(true);
                // TODO mUnlockIcon.setVisibility(View.VISIBLE);
                mFailedPatternAttemptsSinceLastTimeout = 0;
                if (mEnableFallback) {
                    updateFooter(FooterMode.ForgotLockPattern);
                } else {
                    updateFooter(FooterMode.Normal);
                }

                // Show the "Screen off" status
                if (m_bPaused) {
                    msg = getContext().getString(R.string.keyguard_finger_screen_off);
                    msghandlerdelayed(msg);
                } else {
                    mKeyguardStatusViewManager.setInstructionText("");
                    mKeyguardStatusViewManager.updateStatusLines(true);
                }

                // Start the verification if the finger key guard is holding the wakelock.
                if ( (!m_bPaused) && (mExecutionThread != null) && (!(mExecutionThread.isAlive())) ) {
                    /**
                     * acquire the handoff lock that will keep the cpu running. this will
                     * be released once the fingerprint keyguard has set the verification up
                     * and poked the mWakelock of itself (not the one of the KeyguradViewMediator).
                     */
                    mHandOffWakeLock.acquire();
                    if (mExecutionThread.getState() == Thread.State.TERMINATED) {
                        // If the thread state is TERMINATED, it cannot be start() again.
                        // Create a thread for verification.
                        mExecutionThread = new Thread(mVerifyRunner);
                        mExecutionThread.start();
                    } else {
                        if (DEBUG) Log.d(TAG,"Verify thread exists, just start it");
                        mExecutionThread.start();
                    }
                }
            }
        }.start();
    }

    /* handleShow() is called when the GfxEngine sends "show <target>" */
    private void handleShow(String target)
    {
        if (DEBUG) Log.w(TAG, "'show' target: " + target);

        // Has the object paused?
        if (m_bPaused)
        {
            if (DEBUG) Log.d(TAG,"handleShow: paused");
            return;
        }

        if (m_bAttemptLockout)
        {
            if (DEBUG) Log.d(TAG,"handleShow: Locked out");
            return;
        }

        /* if the target is C1-C10 or D1-D10, ignore the command */
        if (target.matches("[CD][1-9]0?$")) return;

        /* if the target is please_swipe, show the UI command to the user: */
        if (target.equals("please_swipe")) {
            /* update the UI */
            runOnUiThread(new Runnable() {
                public void run() {
                    msg = getContext().getString(R.string.keyguard_finger_please_swipe);
                    msghandler(msg);
                    // How long we stay awake once the system is ready for a user to enter a pattern.
                    pokeWakelock(UNLOCK_FINGER_WAKE_INTERVAL_MS);
                }
            });
            return;
        }

        if (target.equals("swipe_good")) {
               runOnUiThread(new Runnable() {
                public void run() {
                    msg = getContext().getString(R.string.keyguard_finger_match);
                    msghandler(msg);
                }
            });
            return;
        }
        /* we'll be asked to show swipe_bad if the finger does not verify. */
        /* this could be following a usage feedback message, in which case */
        /* we've already displayed the feedback, so we don't want to worry */
        /* about an additional message.                                    */
        if (target.equals("swipe_bad")) {
            // Update the total failed attempts.
            mTotalFailedPatternAttempts++;
            mFailedPatternAttemptsSinceLastTimeout++;

            if (!mbFeedbackDelivered) {
                runOnUiThread(new Runnable() {
                    public void run() {
                    msg = getContext().getString(R.string.keyguard_finger_not_match);
                    msghandlerdelayed(msg);
                    mCallback.reportFailedUnlockAttempt();
                    if (mFailedPatternAttemptsSinceLastTimeout >=
                            LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) {
                        pokeWakelock(1000);
                        long deadline = mLockPatternUtils.setLockoutAttemptDeadline();
                        handleAttemptLockout(deadline);
                    }
                    }
                });
            } else {
                runOnUiThread(new Runnable() {
                    public void run() {
                        mCallback.reportFailedUnlockAttempt();
                        if (mFailedPatternAttemptsSinceLastTimeout >=
                                LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) {
                            pokeWakelock(1000);
                            long deadline = mLockPatternUtils.setLockoutAttemptDeadline();
                            handleAttemptLockout(deadline);
                        }
                    }
                });
            }

            mbFeedbackDelivered = false;
            return;
        }

        /* if the target is any of our feedback messages, provide a toast... */
        if (target.equals("swipe_too_fast")) {
            mbFeedbackDelivered = true;
            runOnUiThread(new Runnable() {
                public void run() {
                    msg = getContext().getString(R.string.keyguard_finger_swipe_too_fast);
                    msghandlerdelayed(msg);
                }
            });
            return;
        }
        if (target.equals("swipe_too_slow")) {
            mbFeedbackDelivered = true;
            runOnUiThread(new Runnable() {
                public void run() {
                    msg = getContext().getString(R.string.keyguard_finger_swipe_too_slow);
                    msghandlerdelayed(msg);
                }
            });
            return;
        }
        if (target.equals("swipe_too_short")) {
            mbFeedbackDelivered = true;
            runOnUiThread(new Runnable() {
                public void run() {
                    msg = getContext().getString(R.string.keyguard_finger_swipe_too_short);
                    msghandlerdelayed(msg);
                }
            });
            return;
        }
        if (target.equals("swipe_too_skewed")) {
            mbFeedbackDelivered = true;
            runOnUiThread(new Runnable() {
                public void run() {
                    msg = getContext().getString(R.string.keyguard_finger_swipe_too_skewed);
                    msghandlerdelayed(msg);
                 }
            });
            return;
        }
        if (target.equals("swipe_too_far_left")) {
            mbFeedbackDelivered = true;
            runOnUiThread(new Runnable() {
                public void run() {
                    msg = getContext().getString(R.string.keyguard_finger_swipe_too_far_left);
                    msghandlerdelayed(msg);
                }
            });
            return;
        }
        if (target.equals("swipe_too_far_right")) {
            mbFeedbackDelivered = true;
            runOnUiThread(new Runnable() {
                public void run() {
                    msg = getContext().getString(R.string.keyguard_finger_swipe_too_far_right);
                    msghandlerdelayed(msg);
                }
            });
            return;
        }

        /* if we get here, we did not recognize the target... */
        if (DEBUG) Log.w(TAG, "Unhandled 'show' target: " + target);
    }

    /* handleHide() is called when the GfxEngine sends "hide <target>" */
    private void handleHide(String target)
    {
        // Has the object paused?
        if (m_bPaused)
        {
            if (DEBUG) Log.d(TAG,"handleHide: paused");
            return;
        }

        /* if the target is C1-C10 or D1-D10, ignore the command */
        if (target.matches("[CD][1-9]0?$")) return;

        /* if the target is please_swipe, remove the user prompt */
        if (target.equals("please_swipe"))
        {
            runOnUiThread(new Runnable() {
                public void run() {
                    msg = getContext().getString(R.string.keyguard_finger_please_swipe);
                    msghandler(msg);
                    /**
                     * acquire the handoff lock that will keep the cpu running. this will
                     * be released once the fingerprint keyguard has set the next verification
                     * up and poked the mWakelock of itself (not the one of the KeyguradViewMediator).
                     */
                    mHandOffWakeLock.acquire();

                    if (mTactileFeedbackEnabled) {
                        // Generate tactile feedback
                        if (DEBUG) Log.d(TAG,"Finger on vibration");
                        vibe.vibrate(mVibePattern, -1);
                    }
                }
            });
            return;
        }

        /* if the target is any of our feedback messages, nothing to hide... */

        /* if we get here, we did not recognize the target... */
        if (DEBUG) Log.w(TAG, "Unhandled 'hide' target: " + target);
    }

    /* receiveCommand() is called by a package-local component of the   */
    /* relay server, any time a command is received from the GfxEngine. */
    public void receiveCommand(String command, String args) {
        /* process the command: */

        /*  for "screen" without parameters, the GfxEngine is done with us...
         *  we expect to receive exactly two screen commands during our
         *  lifetime:
         *      command=="screen", args=="<something here>"
         *  and
         *      command=="screen", args==""
         *  The first pattern will be the very first command we receive, and
         *  the second will be the very last command we receive. The argument
         *  in the first pattern will be indicative of the reason the system
         *  is presenting a UI; if our activity/service is intended to manage
         *  multiple screen types, we can use this to distinguish which one to
         *  present.
         */
        if (command.equals("screen") && ((args == null) || (args.length() == 0))) {
            // in this case we received "screen" without any arguments... if
            // our activity had been launched by the GfxEngine, we would key on
            // this to terminate. in this demo, our activity is around before
            // the GfxEngine, and we trigger it rather than it triggering us,
            // so we can safely ignore this command.

            // we just received "screen" with no arguments... the connection is breaking.
            if (DEBUG) Log.w(TAG, "receiveCommand: the connection is breaking");
            m_bGfxEngineAttached = false;
            m_bCancelHasBeenSent = false;
            return;
        }
        else if (command.equals("screen")) {
            // we don't need to do anything here unless we care about the name
            // of the screen being instantiated (in which case we are interested
            // in the value of args)

            // we're attached to the GfxEngine, manage delayed cancelation.
            if (DEBUG) Log.w(TAG, "receiveCommand: attatched to the GfxEngine");
            m_bGfxEngineAttached = true;

            /*if (initfp = true) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            mKeyguardStatusViewManager.setInstructionText(getContext().getString(
                            R.string.keyguard_finger_please_swipe));
                            mKeyguardStatusViewManager.updateStatusLines(true);
                        }
                    });
               initfp = false;
            }*/
            if ((m_bSendDelayedCancel) && (!m_bCancelHasBeenSent)) {
                if (DEBUG) Log.w(TAG, "receiveCommand: cancel delayed");
                if (mExecutionThread != null && mExecutionThread.isAlive()) {
                    if (DEBUG) Log.w(TAG, "receiveCommand: send delayed #cancel");
                    GfxEngineRelayService.queueEvent("#cancel");
                    m_bCancelHasBeenSent = true;
                    m_bSendDelayedCancel = false;
                }
            }
            return;
        }

        /*  if the command is "show" or "hide", we're being asked to show or
         *  hide some named UI element. In some cases, the UI elements may not
         *  actually exist, in which case the command can probably be ignored.
         *  based on the name of the element to be shown / hidden, we might
         *  make different choices. An element may exist to tell the user to do
         *  something: the element 'please_swipe' is used to inform the user
         *  that the system is waiting for their finger to be swiped IF the
         *  element is being shown. However, if the element is being hidden,
         *  the system is acknowledging that the user has started their swipe.
         *  It is normal to start a timer when "show please_swipe" is seen, and
         *  if the timer expires before "hide please_swipe" occurs, it's normal
         *  to send back a '#timeout' event.
         *  Other elements exist for the purpose of providing the user with
         *  usage feedback; these elements will be shown but not hidden. It is
         *  this activity's responsibility to decide when/if they should be
         *  taken down from the display. Often these types of feedback will be
         *  presented through an Android 'toast'. Examples of these feedback
         *  messages include: swipe_good, swipe_too_fast, swipe_too_slow,
         *  sensor_dirty, swipe_too_short, swipe_too_skewed,
         *  swipe_too_far_right, swipe_too_far_left.
         *  Generally it is believed that these element names explain the reason
         *  they will be shown, but there are some caveats to be aware of.
         *
         *  swipe_too_far_[right|left] may be inaccurate. That is, the
         *  left message may be shown when right is appropriate, and the reverse
         *  is true. The issue here is that the sensor can be mounted upside
         *  down, and the user could swipe in the opposite direction versus
         *  what the hardware design intends. As long as the swipe is in the
         *  center of the sensor, neither of those possibilities will have a
         *  negative effect on the operation, however they can cause the
         *  confusion. It is recommended that the same response be used for
         *  each of these conditions, and that the response merely tell the
         *  user two swipe in the center of the sensor.
         *
         *  Some of these messages will accompany another message immediately
         *  following: swipe_bad. It is tempting to simply ignore the swipe_bad
         *  message if the more direct message is being presented, however the
         *  swipe_bad message will also be sent (without other feedback) in the
         *  case of a good swipe failing to correctly verify against the
         *  database; the swipe_bad element is the only no-match notification.
         *
         *  Other elements that could be shown or hidden include C1 through C10
         *  and D1 through D10. These elements are only useful in the event
         *  that our UI includes a set of fingers and wishes to highlight
         *  certain fingers for some reason. If the UI does not include such a
         *  requirement, these elements can be ignored.
         *
         */
        if (command.equals("show")) {
            handleShow(args);
            return;
        }

        if (command.equals("hide")) {
            handleHide(args);
            return;
        }

        if (command.equals("settext")) {
            // This can generally be ignored. For UIs that get invoked on
            // behalf of multiple different applications, we expect to receive
            // a "settext appname <app name here>" command, in case we want to
            // tell the user which application is needing their fingerprint in
            // order to return a credential from the database.
            return;
        }

        if (DEBUG) Log.w(TAG, "Unhandled command: " + command);
    }
    public void msghandler(final String msg)
    {
      mKeyguardStatusViewManager.setInstructionText(msg);
      mKeyguardStatusViewManager.updateStatusLines(true);
    }

    public void msghandlerdelayed(final String msg)
    {
      mKeyguardStatusViewManager.setInstructionText(msg);
      mKeyguardStatusViewManager.updateStatusLines(true);
          /* try {
              Thread.sleep(1000);
              } catch (InterruptedException e) {
              }
*/
    }
    public void onMusicChanged() {
        // refreshPlayingTitle();
    }
}
