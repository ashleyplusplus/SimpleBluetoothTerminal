package de.kai_morich.simple_bluetooth_terminal;


import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayDeque;
import java.util.Arrays;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener, SensorEventListener {

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;
    private SensorManager sensorManager;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
        sensorManager = (SensorManager) this.getContext().getSystemService(Context.SENSOR_SERVICE);
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        sensorManager.unregisterListener(this);
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY), SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true);
            menu.findItem(R.id.backgroundNotification).setEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                } else {
                    showNotificationSettings();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n');
            } else {
                String msg = new String(data);
                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if(spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
            }
        }
        receiveText.append(spn);
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(Arrays.equals(permissions, new String[]{Manifest.permission.POST_NOTIFICATIONS}) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled())
            showNotificationSettings();
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

    long lastUpdateTime = System.currentTimeMillis();
    public void setLightsAccel(SensorEvent event) {
        long currentTime = System.currentTimeMillis();
        if(currentTime - lastUpdateTime > 300) {
            lastUpdateTime = currentTime;
            float xdir = event.values[0];
            float ydir = event.values[1];
            float zdir = event.values[2];
            float sqrtaccel = (xdir * xdir + ydir * ydir + zdir * zdir) / (SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH);
            if (sqrtaccel >= 1.25 && sqrtaccel < 3) {
                send("1000000");
//                buttons[0].setChecked(true);
//                buttons[1].setChecked(false);
//                buttons[2].setChecked(false);
//                buttons[3].setChecked(false);
//                buttons[4].setChecked(false);
//                buttons[5].setChecked(false);
//                buttons[6].setChecked(false);
            } else if (sqrtaccel >= 3 && sqrtaccel < 6) {
                send("1100000");
//                buttons[0].setChecked(true);
//                buttons[1].setChecked(true);
//                buttons[2].setChecked(false);
//                buttons[3].setChecked(false);
//                buttons[4].setChecked(false);
//                buttons[5].setChecked(false);
//                buttons[6].setChecked(false);
            } else if (sqrtaccel >= 6 && sqrtaccel < 9) {
                send("1110000");
//                buttons[0].setChecked(true);
//                buttons[1].setChecked(true);
//                buttons[2].setChecked(true);
//                buttons[3].setChecked(false);
//                buttons[4].setChecked(false);
//                buttons[5].setChecked(false);
//                buttons[6].setChecked(false);
            } else if (sqrtaccel >= 9 && sqrtaccel < 12) {
                send("1111000");
//                buttons[0].setChecked(true);
//                buttons[1].setChecked(true);
//                buttons[2].setChecked(true);
//                buttons[3].setChecked(true);
//                buttons[4].setChecked(false);
//                buttons[5].setChecked(false);
//                buttons[6].setChecked(false);
            } else if (sqrtaccel >= 12 && sqrtaccel < 15) {
                send("1111100");
//                buttons[0].setChecked(true);
//                buttons[1].setChecked(true);
//                buttons[2].setChecked(true);
//                buttons[3].setChecked(true);
//                buttons[4].setChecked(true);
//                buttons[5].setChecked(false);
//                buttons[6].setChecked(false);
            } else if (sqrtaccel >= 15 && sqrtaccel < 18) {
                send("1111110");
//                buttons[0].setChecked(true);
//                buttons[1].setChecked(true);
//                buttons[2].setChecked(true);
//                buttons[3].setChecked(true);
//                buttons[4].setChecked(true);
//                buttons[5].setChecked(true);
//                buttons[6].setChecked(false);
            } else if (sqrtaccel >= 18) {
                send("1111111");
//                buttons[0].setChecked(true);
//                buttons[1].setChecked(true);
//                buttons[2].setChecked(true);
//                buttons[3].setChecked(true);
//                buttons[4].setChecked(true);
//                buttons[5].setChecked(true);
//                buttons[6].setChecked(true);
            }
        }
    }

    public void setLightsGyro(SensorEvent event) {
        float zdir = event.values[2];

        if (zdir >= 1.) {
            buttons[4].setChecked(false);
            buttons[5].setChecked(false);
            buttons[6].setChecked(false);

            if(buttons[1].isChecked()) {
                buttons[0].setChecked(true);
            }
            else if(buttons[2].isChecked()) {
                buttons[1].setChecked(true);
            }
            else {
                buttons[2].setChecked(true);
            }
        }
        else if (zdir <= -1) {
            buttons[2].setChecked(false);
            buttons[1].setChecked(false);
            buttons[0].setChecked(false);
            if(buttons[5].isChecked()) {
                buttons[6].setChecked(true);
            }
            else if(buttons[4].isChecked()) {
                buttons[5].setChecked(true);
            }
            else {
                buttons[4].setChecked(true);
            }
        }
    }

    public void setLightsProx(SensorEvent event) {
        if (event.values[0] < 5.0f) {
            buttons[0].setChecked(true);
            buttons[1].setChecked(true);
            buttons[2].setChecked(true);
            buttons[3].setChecked(true);
            buttons[4].setChecked(true);
            buttons[5].setChecked(true);
            buttons[6].setChecked(true);
        } else {
            buttons[0].setChecked(false);
            buttons[1].setChecked(false);
            buttons[2].setChecked(false);
            buttons[3].setChecked(false);
            buttons[4].setChecked(false);
            buttons[5].setChecked(false);
            buttons[6].setChecked(false);
        }
    }
    @Override
    public void onSensorChanged(SensorEvent event) {
        if(event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            setLightsAccel(event);
        }
        else if(event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            setLightsGyro(event);
        }
        else if(event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            setLightsProx(event);
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

}
