package fr.redboxing.sti2d.stirecues;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.Nullable;
import com.google.android.material.snackbar.Snackbar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonObject;
import com.google.mediapipe.formats.proto.LandmarkProto;
import com.google.mediapipe.solutioncore.CameraInput;
import com.google.mediapipe.solutioncore.SolutionGlSurfaceView;
import com.google.mediapipe.solutions.hands.Hands;
import com.google.mediapipe.solutions.hands.HandsOptions;
import com.google.mediapipe.solutions.hands.HandsResult;
import fr.redboxing.sti2d.stirecues.databinding.ActivityMainBinding;
import io.github.controlwear.virtual.joystick.android.JoystickView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MainActivity extends AppCompatActivity {
    private static final UUID BT_MODULE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final static int REQUEST_ENABLE_BT = 1;

    private BluetoothAdapter adapter;
    private Set<BluetoothDevice> devices;
    private ConnectThread connectThread;

    private TextView textView;
    private Button openClampBtn;
    private Button closeClampBtn;
    private Button moveClampForwardBtn;
    private Button moveClampBackwardBtn;
    private Switch secretModeSwitch;
    private FrameLayout frameLayout;
    private JoystickView joystickView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.textView = this.findViewById(R.id.textView2);
        this.openClampBtn = this.findViewById(R.id.open_clamp_btn);
        this.closeClampBtn = this.findViewById(R.id.close_clamp_btn);
        this.moveClampForwardBtn = this.findViewById(R.id.move_clamp_forward);
        this.moveClampBackwardBtn = this.findViewById(R.id.move_clamp_backward);
        this.secretModeSwitch = this.findViewById(R.id.switch1);
        this.frameLayout = this.findViewById(R.id.frameLayout);
        this.joystickView = this.findViewById(R.id.joystick);


        HandsOptions handsOptions = HandsOptions.builder()
                .setModelComplexity(1)
                .setMaxNumHands(1)
                .setRunOnGpu(true)
                .build();

        Hands hands = new Hands(this, handsOptions);
        hands.setErrorListener((msg, e) -> Log.e("MainActivity", "MediaPipe Hands error:" + msg));

        CameraInput cameraInput = new CameraInput(this);
        cameraInput.setNewFrameListener(hands::send);

        SolutionGlSurfaceView<HandsResult> handsView = new SolutionGlSurfaceView<>(this, hands.getGlContext(), hands.getGlMajorVersion());
        handsView.setSolutionResultRenderer(new HandsResultGlRenderer());
        handsView.setRenderInputImage(true);

        hands.setResultListener(result -> {
            if(result.multiHandWorldLandmarks().size() > 0) {
                LandmarkProto.NormalizedLandmarkList landmarkList = result.multiHandLandmarks().get(0);
                Log.i("MainActivity", landmarkList.toString());

                JsonObject json = new JsonObject();
                json.addProperty("type", "MOVE_CLAMP");
                json.addProperty("pos", true);
                //MainActivity.this.connectThread.write(json);
            }
            handsView.setRenderData(result);
            handsView.requestRender();
        });

        BluetoothManager bluetoothManager = this.getSystemService(BluetoothManager.class);
        this.adapter = bluetoothManager.getAdapter();

        if(!this.adapter.isEnabled()) {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            this.startActivityForResult(intent, REQUEST_ENABLE_BT);
            return;
        }

        this.devices = this.adapter.getBondedDevices();
        this.devices.forEach(d -> {
            Log.e("MainActivity", "Name: " + d.getName() + ", MAC: " + d.getAddress());
        });
        Optional<BluetoothDevice> optional = this.devices.stream().filter(d -> d.getName().equals("TEEBot")).findFirst();
        if(!optional.isPresent()) {
            Log.e("MainActivity", "Bluetooth device is not linked !");
            Toast.makeText(this, "Vous devez appairer le robot dans les paramètres bluetooth !", Toast.LENGTH_LONG).show();
            return;
        }

        BluetoothDevice device = optional.get();

        this.textView.setText("Appareil: " + device.getAddress());

        this.connectThread = new ConnectThread(this, device);
        this.connectThread.start();

        this.openClampBtn.setOnClickListener(v -> {
            JsonObject json = new JsonObject();
            json.addProperty("type", "OPEN_CLOSE_CLAMP");
            json.addProperty("value", true);
            MainActivity.this.connectThread.write(json);
        });

        this.closeClampBtn.setOnClickListener(v -> {
            JsonObject json = new JsonObject();
            json.addProperty("type", "OPEN_CLOSE_CLAMP");
            json.addProperty("value", false);
            MainActivity.this.connectThread.write(json);
        });

        this.moveClampForwardBtn.setOnClickListener(v -> {
            JsonObject json = new JsonObject();
            json.addProperty("type", "MOVE_CLAMP");
            json.addProperty("value", "up");
            MainActivity.this.connectThread.write(json);
        });

        this.moveClampBackwardBtn.setOnClickListener(v -> {
            JsonObject json = new JsonObject();
            json.addProperty("type", "MOVE_CLAMP");
            json.addProperty("value", "down");
            MainActivity.this.connectThread.write(json);
        });

        this.secretModeSwitch.setOnClickListener(sw -> {
            Log.i("MainActivity", "aaaa");
            boolean status = this.secretModeSwitch.isChecked();
            if(status) {
                Log.i("MainActivity", "Enabling secret mode");
                handsView.post(() -> cameraInput.start(this, hands.getGlContext(), CameraInput.CameraFacing.BACK, handsView.getWidth(), handsView.getHeight()));
                this.frameLayout.removeAllViewsInLayout();
                this.frameLayout.addView(handsView);
                handsView.setVisibility(View.VISIBLE);
                frameLayout.requestLayout();
            } else {
                handsView.post(cameraInput::close);
                this.frameLayout.removeAllViewsInLayout();
                handsView.setVisibility(View.GONE);
                frameLayout.requestLayout();
            }
        });

        this.joystickView.setOnMoveListener((angle, strength) -> {
            JsonObject json = new JsonObject();
            json.addProperty("type", "MOVE_ROBOT");
            json.addProperty("angle", angle);
            json.addProperty("strength", strength);
            MainActivity.this.connectThread.write(json);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            Toast.makeText(this, "Vous devez activer le Bluetooth !", Toast.LENGTH_LONG).show();
        }
    }

    private static class ConnectThread extends Thread {
        private final MainActivity activity;
        private final BluetoothSocket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private long lastSent = 0;

        private final Queue<byte[]> queue = new ConcurrentLinkedQueue<>();

        public ConnectThread(MainActivity activity, BluetoothDevice device) {
            Log.e("ConnectThread", device.getName());

            this.activity = activity;

            try {
                this.socket = device.createInsecureRfcommSocketToServiceRecord(BT_MODULE_UUID);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            try {
                this.inputStream = this.socket.getInputStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            try {
                this.outputStream = this.socket.getOutputStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            Log.e("ConnectThread", "run");
           if(!this.socket.isConnected()) {
               try {
                   this.socket.connect();
                   this.activity.textView.setTextColor(Color.GREEN);
               } catch (IOException e) {
                   try {
                       this.socket.close();
                   } catch (IOException ex) {
                       throw new RuntimeException(ex);
                   }

                   Log.e("ConnectThread", "Failed to connect : " + e.getMessage());
                   this.activity.textView.setTextColor(Color.RED);
               }
           }
               while(true) {
                   if(this.socket.isConnected() && this.queue.size() > 0) {
                       this.write(this.queue.las());
                   }
               }

        }

        public void write(JsonObject json) {
            this.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }

        public void write(byte[] data) {
            if(this.socket.isConnected() && System.currentTimeMillis() - this.lastSent > 250) {
                try {
                    Log.e("MainActivity", "Sending " + new String(data));
                    this.lastSent = System.currentTimeMillis();
                    this.outputStream.write(data);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                this.queue.add(data);
            }
        }

        public void cancel() {
            try {
                this.socket.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}