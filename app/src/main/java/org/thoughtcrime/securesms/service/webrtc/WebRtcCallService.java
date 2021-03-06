package org.thoughtcrime.securesms.service.webrtc;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.TelephonyUtil;
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder;
import org.thoughtcrime.securesms.webrtc.UncaughtExceptionHandlerManager;
import org.thoughtcrime.securesms.webrtc.audio.BluetoothStateManager;
import org.thoughtcrime.securesms.webrtc.locks.LockManager;

import java.util.Objects;

/**
 * Provide a foreground service for {@link SignalCallManager} to leverage to run in the background when necessary. Also
 * provides devices listeners needed for during a call (i.e., bluetooth, power button).
 */
public final class WebRtcCallService extends Service implements BluetoothStateManager.BluetoothStateListener {

  private static final String TAG = Log.tag(WebRtcCallService.class);

  private static final String ACTION_UPDATE              = "UPDATE";
  private static final String ACTION_STOP                = "STOP";
  private static final String ACTION_DENY_CALL           = "DENY_CALL";
  private static final String ACTION_LOCAL_HANGUP        = "LOCAL_HANGUP";
  private static final String ACTION_WANTS_BLUETOOTH     = "WANTS_BLUETOOTH";
  private static final String ACTION_CHANGE_POWER_BUTTON = "CHANGE_POWER_BUTTON";

  private static final String EXTRA_UPDATE_TYPE  = "UPDATE_TYPE";
  private static final String EXTRA_RECIPIENT_ID = "RECIPIENT_ID";
  private static final String EXTRA_ENABLED      = "ENABLED";

  private SignalCallManager callManager;

  private WiredHeadsetStateReceiver       wiredHeadsetStateReceiver;
  private NetworkReceiver                 networkReceiver;
  private PowerButtonReceiver             powerButtonReceiver;
  private UncaughtExceptionHandlerManager uncaughtExceptionHandlerManager;
  private PhoneStateListener              hangUpRtcOnDeviceCallAnswered;
  private BluetoothStateManager           bluetoothStateManager;

  public static void update(@NonNull Context context, int type, @NonNull RecipientId recipientId) {
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(ACTION_UPDATE)
          .putExtra(EXTRA_UPDATE_TYPE, type)
          .putExtra(EXTRA_RECIPIENT_ID, recipientId);

    ContextCompat.startForegroundService(context, intent);
  }

  public static void stop(@NonNull Context context) {
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(ACTION_STOP);

    context.startService(intent);
  }

  public static @NonNull Intent denyCallIntent(@NonNull Context context) {
    return new Intent(context, WebRtcCallService.class).setAction(ACTION_DENY_CALL);
  }

  public static @NonNull Intent hangupIntent(@NonNull Context context) {
    return new Intent(context, WebRtcCallService.class).setAction(ACTION_LOCAL_HANGUP);
  }

  public static void setWantsBluetoothConnection(@NonNull Context context, boolean enabled) {
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(ACTION_WANTS_BLUETOOTH)
          .putExtra(EXTRA_ENABLED, enabled);

    context.startService(intent);
  }

  public static void changePowerButtonReceiver(@NonNull Context context, boolean register) {
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(ACTION_CHANGE_POWER_BUTTON)
          .putExtra(EXTRA_ENABLED, register);

    context.startService(intent);
  }

  @Override
  public void onCreate() {
    Log.v(TAG, "onCreate");
    super.onCreate();
    this.callManager                   = ApplicationDependencies.getSignalCallManager();
    this.bluetoothStateManager         = new BluetoothStateManager(this, this);
    this.hangUpRtcOnDeviceCallAnswered = new HangUpRtcOnPstnCallAnsweredListener();

    registerUncaughtExceptionHandler();
    registerWiredHeadsetStateReceiver();
    registerNetworkReceiver();

    TelephonyUtil.getManager(this)
                 .listen(hangUpRtcOnDeviceCallAnswered, PhoneStateListener.LISTEN_CALL_STATE);
  }

  @Override
  public void onDestroy() {
    Log.v(TAG, "onDestroy");
    super.onDestroy();

    if (uncaughtExceptionHandlerManager != null) {
      uncaughtExceptionHandlerManager.unregister();
    }

    if (bluetoothStateManager != null) {
      bluetoothStateManager.onDestroy();
    }

    if (wiredHeadsetStateReceiver != null) {
      unregisterReceiver(wiredHeadsetStateReceiver);
      wiredHeadsetStateReceiver = null;
    }

    unregisterNetworkReceiver();
    unregisterNetworkReceiver();

    TelephonyUtil.getManager(this)
                 .listen(hangUpRtcOnDeviceCallAnswered, PhoneStateListener.LISTEN_NONE);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || intent.getAction() == null) {
      return START_NOT_STICKY;
    }

    Log.i(TAG, "action: " + intent.getAction());

    switch (intent.getAction()) {
      case ACTION_UPDATE:
        setCallInProgressNotification(intent.getIntExtra(EXTRA_UPDATE_TYPE, 0),
                                      Objects.requireNonNull(intent.getParcelableExtra(EXTRA_RECIPIENT_ID)));
        return START_STICKY;
      case ACTION_WANTS_BLUETOOTH:
        if (bluetoothStateManager != null) {
          bluetoothStateManager.setWantsConnection(intent.getBooleanExtra(EXTRA_ENABLED, false));
        }
        return START_STICKY;
      case ACTION_CHANGE_POWER_BUTTON:
        if (intent.getBooleanExtra(EXTRA_ENABLED, false)) {
          registerPowerButtonReceiver();
        } else {
          unregisterPowerButtonReceiver();
        }
        return START_STICKY;
      case ACTION_STOP:
        stopSelf();
        stopForeground(true);
        return START_NOT_STICKY;
      case ACTION_DENY_CALL:
        callManager.denyCall();
        return START_NOT_STICKY;
      case ACTION_LOCAL_HANGUP:
        callManager.localHangup();
        return START_NOT_STICKY;
      default:
        throw new AssertionError("Unknown action: " + intent.getAction());
    }
  }

  public void setCallInProgressNotification(int type, @NonNull RecipientId id) {
    startForeground(CallNotificationBuilder.getNotificationId(type),
                    CallNotificationBuilder.getCallInProgressNotification(this, type, Recipient.resolved(id)));
  }

  private void registerUncaughtExceptionHandler() {
    uncaughtExceptionHandlerManager = new UncaughtExceptionHandlerManager();
    uncaughtExceptionHandlerManager.registerHandler(new ProximityLockRelease(callManager.getLockManager()));
  }

  private void registerWiredHeadsetStateReceiver() {
    wiredHeadsetStateReceiver = new WiredHeadsetStateReceiver();

    String action;

    if (Build.VERSION.SDK_INT >= 21) {
      action = AudioManager.ACTION_HEADSET_PLUG;
    } else {
      action = Intent.ACTION_HEADSET_PLUG;
    }

    registerReceiver(wiredHeadsetStateReceiver, new IntentFilter(action));
  }

  private void registerNetworkReceiver() {
    if (networkReceiver == null) {
      networkReceiver = new NetworkReceiver();

      registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }
  }

  private void unregisterNetworkReceiver() {
    if (networkReceiver != null) {
      unregisterReceiver(networkReceiver);

      networkReceiver = null;
    }
  }

  public void registerPowerButtonReceiver() {
    if (powerButtonReceiver == null) {
      powerButtonReceiver = new PowerButtonReceiver();

      registerReceiver(powerButtonReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }
  }

  public void unregisterPowerButtonReceiver() {
    if (powerButtonReceiver != null) {
      unregisterReceiver(powerButtonReceiver);

      powerButtonReceiver = null;
    }
  }

  @Override
  public @Nullable IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onBluetoothStateChanged(boolean isAvailable) {
    callManager.bluetoothChange(isAvailable);
  }

  private class HangUpRtcOnPstnCallAnsweredListener extends PhoneStateListener {
    @Override
    public void onCallStateChanged(int state, @NonNull String phoneNumber) {
      super.onCallStateChanged(state, phoneNumber);
      if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
        hangup();
        Log.i(TAG, "Device phone call ended Signal call.");
      }
    }

    private void hangup() {
      callManager.localHangup();
    }
  }

  private static class WiredHeadsetStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
      int state = intent.getIntExtra("state", -1);

      ApplicationDependencies.getSignalCallManager().wiredHeadsetChange(state != 0);
    }
  }

  private static class NetworkReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo         activeNetworkInfo   = connectivityManager.getActiveNetworkInfo();

      ApplicationDependencies.getSignalCallManager().networkChange(activeNetworkInfo != null && activeNetworkInfo.isConnected());
      ApplicationDependencies.getSignalCallManager().bandwidthModeUpdate();
    }
  }

  private static class PowerButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
      if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
        ApplicationDependencies.getSignalCallManager().screenOff();
      }
    }
  }

  private static class ProximityLockRelease implements Thread.UncaughtExceptionHandler {
    private final LockManager lockManager;

    private ProximityLockRelease(@NonNull LockManager lockManager) {
      this.lockManager = lockManager;
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
      Log.i(TAG, "Uncaught exception - releasing proximity lock", throwable);
      lockManager.updatePhoneState(LockManager.PhoneState.IDLE);
    }
  }
}
