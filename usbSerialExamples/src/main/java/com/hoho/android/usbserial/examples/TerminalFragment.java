package com.hoho.android.usbserial.examples;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;

public class TerminalFragment extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission { Unknown, Requested, Granted, Denied }

    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;

    private int deviceId, portNum, baudRate;
    private boolean withIoManager;

    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;
    private TextView receiveText;
    private ControlLines controlLines;

    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;

    private InputStream mInputStream;

    private final byte[] Serial_Read_Data = new byte[25];//Данные принимаемые от компютера//5 байт "b"(98)+ 8 байт+2 байта ID + 10 штук "d"(100)
    private final char[] bufOutCAN00 = new char[8];  //MCO_STATE_A светофор
    private final char[] bufOutCAN01 = new char[8];  //IPD_STATE_A
    private final char[] bufOutCAN02 = new char[8];  //MM_STATION
    private final char[] bufOutCAN03 = new char[8];  //MM_SIGNAL
    private final char[] bufOutCAN04 = new char[8];  //IPD_DATE
    private final char[] bufOutCAN05 = new char[8];  //MM_COORD
    private final char[] bufOutCAN06 = new char[8];  //REG_STATE
    private final char[] bufOutCAN07 = new char[8];  //AMR_STATE
    private final char[] bufOutCAN08 = new char[8];  //BVU_STATE_A
    private final char[] bufOutCAN09 = new char[8];  //MCO_LIMITS_A
    private final char[] bufOutCAN10 = new char[8];  //SYS_DATA, INPUT_DATA
    private final char[] bufOutCAN11 = new char[8];  //SYS_DATA_STATE
    private final char[] bufOutCAN_EK = new char[8]; //AUX_RESOURCE_MM передает номер ЭК

    //=================================================================================================
    //Жизненный цикл
    //=================================================================================================
    public TerminalFragment()
    {
        broadcastReceiver = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                if(INTENT_ACTION_GRANT_USB.equals(intent.getAction()))
                {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }

    //=================================================================================================
    //Жизненный цикл
    //=================================================================================================
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
        withIoManager = getArguments().getBoolean("withIoManager");
    }

    //=================================================================================================
    //
    //=================================================================================================
    @Override
    public void onStart()
    {
        super.onStart();
        ContextCompat.registerReceiver(getActivity(), broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    //=================================================================================================
    //
    //=================================================================================================
    @Override
    public void onStop()
    {
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onStop();
    }

    //=================================================================================================
    //
    //=================================================================================================
    @Override
    public void onResume()
    {
        super.onResume();
        if(!connected && (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted))
            mainLooper.post(this::connect);
    }

    //=================================================================================================
    //
    //=================================================================================================
    @Override
    public void onPause()
    {
        if(connected)
        {
            status("disconnected");
            disconnect();
        }
        super.onPause();
    }

    //=================================================================================================
    //
    //=================================================================================================
    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        TextView sendText = view.findViewById(R.id.send_text);
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        View receiveBtn = view.findViewById(R.id.receive_btn);
        controlLines = new ControlLines(view);

        if(withIoManager)
        {
            receiveBtn.setVisibility(View.GONE);
        }
        else
        {
            receiveBtn.setOnClickListener(v -> read());
        }
        return view;
    }

    //=================================================================================================
    //
    //=================================================================================================
    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater)
    {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    //=================================================================================================
    //
    //=================================================================================================
    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        int id = item.getItemId();

        if (id == R.id.clear)
        {
            receiveText.setText("");
            return true;
        }
        else
        {
            return super.onOptionsItemSelected(item);
        }
    }

    //=================================================================================================
    //
    //=================================================================================================
    /*
     * Serial
     */
    @Override
    public void onNewData(byte[] data)
    {
        mainLooper.post(() -> {
            receive(data);
        });
    }

    //=================================================================================================
    //
    //=================================================================================================
    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status("connection lost: " + e.getMessage());
            disconnect();
        });
    }

    //=================================================================================================
    //
    //=================================================================================================
    /*
     * Serial + UI
     */
    private void connect()
    {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId) device = v;


        if(device == null)
        {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);

        if(driver == null)
        {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }

        if(driver == null)
        {
            status("connection failed: no driver for device");
            return;
        }

        if(driver.getPorts().size() < portNum)
        {
            status("connection failed: not enough ports at device");
            return;
        }

        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice()))
        {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(INTENT_ACTION_GRANT_USB);
            intent.setPackage(getActivity().getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }

        if(usbConnection == null)
        {
            if(!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            try{
                usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            }catch (UnsupportedOperationException e){
                status("unsupport setparameters");
            }

            if(withIoManager)
            {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }

            status("Подключено");

            connected = true;
            controlLines.start();
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    //=================================================================================================
    //
    //=================================================================================================
    private void disconnect()
    {
        connected = false;
        controlLines.stop();

        if(usbIoManager != null)
        {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }

        usbIoManager = null;

        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    //=================================================================================================
    //
    //=================================================================================================
    private void send(String str)
    {
        if(!connected)
        {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
        byte[] data = (str + '\n').getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder();
            spn.append("send " + data.length + " bytes\n");
            spn.append(HexDump.dumpHexString(data)).append("\n");
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            onRunError(e);
        }
    }

    //=================================================================================================
    //Чтение данных из COM порта
    //=================================================================================================
    private void read()
    {
        int size = 0;
        if(!connected)
        {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            byte[] readBuffer = new byte[8200];
            int len = usbSerialPort.read(readBuffer, READ_WAIT_MILLIS);
            receive(Arrays.copyOf(readBuffer, len));
        } catch (IOException e) {
            // when using read with timeout, USB bulkTransfer returns -1 on timeout _and_ errors
            // like connection loss, so there is typically no exception thrown here on error
            status("connection lost: " + e.getMessage());
            disconnect();
        }
    }

    //=================================================================================================
    //Обработчик принятых данных
    //Данные принимаемые из компютера 5 байт "b"(98)+ 8 байт+2 байта ID + 10 штук "d"(100)
    //=================================================================================================
    private void receive(byte[] data)
    {
        int S_DataRead = 0;        //байт считанный из буфера COM
        int opozkol = 0;           //число пришедших подряд опозновательных символов (должно быть 10 штук "d"(100))

        byte[] S = new byte [25];  //Вспомогательный массив для организации стека
        byte[] S1 = new byte [25]; //Вспомогательный массив для организации стека

        if(data.length > 0)
        {
            for (int i = data.length-1; i >= 0; i--)
            {
                S_DataRead  = data[i];//Чтение полученных данных для последующего разбора

                if (S_DataRead >= 0)
                {
                    //Смещаем все значения в стеке на один байт
                    for (int a = 0; a <= 23; a++)//операция 1
                    {
                        S1[a + 1] = S[a];
                    }

                    for (int b = 0; b <= 24; b++)//операция 2
                    {
                        S[b] = S1[b];
                    }

                    S[0] = (byte)(S_DataRead & 0xFF);//Читаем байт low Byte

                    if (S_DataRead == 100)//Ищем начало - 10 штук "d"(100)
                    {
                        opozkol++;
                    }
                    else
                    {
                        opozkol = 0;
                    }

                    if (S_DataRead == 100 & opozkol == 10)
                    {
                        //Если нашли начало , то обновляем стек
                        for (int j=0; j <= 24; j++)
                        {
                            Serial_Read_Data[j] = S[j];
                        }

                        Serial_Parsing_Data();//Вызов функции разбор данных из COM порта.
                        SpannableStringBuilder spn = new SpannableStringBuilder();
                        //Convert bytes to String
                        //String s_out = new String(bufOutCAN00, "UTF-8");
                        spn.append("receive " + bufOutCAN00[0] + " bytes\n");
                        receiveText.append(spn);
                    }//конец if (S_DataRead == 100 & opozkol == 10)
                }//конец if (S_DataRead >= 0)
            }//конец for (int i = data.length-1; i >= 0; i--)
        }//конец if(data.length > 0)
    }//конец private void receive(byte[] data)
    //=================================================================================================
    //Разбор данных из COM порта
    //=================================================================================================
    void Serial_Parsing_Data()
    {
        if (Serial_Read_Data[19] == 0)               //MCO_STATE_A
        {
            bufOutCAN00[0] = (char) Serial_Read_Data[18];
            bufOutCAN00[1] = (char) Serial_Read_Data[17];
            bufOutCAN00[2] = (char) Serial_Read_Data[16];
            bufOutCAN00[3] = (char) Serial_Read_Data[15];
            bufOutCAN00[4] = (char) Serial_Read_Data[14];
            bufOutCAN00[5] = (char) Serial_Read_Data[13];
            bufOutCAN00[6] = (char) Serial_Read_Data[12];
            bufOutCAN00[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 1)         //IPD_STATE_A
        {
            bufOutCAN01[0] = (char) Serial_Read_Data[18];
            bufOutCAN01[1] = (char) Serial_Read_Data[17];
            bufOutCAN01[2] = (char) Serial_Read_Data[16];
            bufOutCAN01[3] = (char) Serial_Read_Data[15];
            bufOutCAN01[4] = (char) Serial_Read_Data[14];
            bufOutCAN01[5] = (char) Serial_Read_Data[13];
            bufOutCAN01[6] = (char) Serial_Read_Data[12];
            bufOutCAN01[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 2)         //MM_STATION Название станции в кодировке Win-1251.
        {
            bufOutCAN02[0] = (char) Serial_Read_Data[18];
            bufOutCAN02[1] = (char) Serial_Read_Data[17];
            bufOutCAN02[2] = (char) Serial_Read_Data[16];
            bufOutCAN02[3] = (char) Serial_Read_Data[15];
            bufOutCAN02[4] = (char) Serial_Read_Data[14];
            bufOutCAN02[5] = (char) Serial_Read_Data[13];
            bufOutCAN02[6] = (char) Serial_Read_Data[12];
            bufOutCAN02[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 3)         //MM_SIGNAL Название первой от головы поезда цели (кодировка Win-1251).
        {
            bufOutCAN03[0] = (char) Serial_Read_Data[18];
            bufOutCAN03[1] = (char) Serial_Read_Data[17];
            bufOutCAN03[2] = (char) Serial_Read_Data[16];
            bufOutCAN03[3] = (char) Serial_Read_Data[15];
            bufOutCAN03[4] = (char) Serial_Read_Data[14];
            bufOutCAN03[5] = (char) Serial_Read_Data[13];
            bufOutCAN03[6] = (char) Serial_Read_Data[12];
            bufOutCAN03[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 4)         //IPD_DATE
        {
            bufOutCAN04[0] = (char) Serial_Read_Data[18];
            bufOutCAN04[1] = (char) Serial_Read_Data[17];
            bufOutCAN04[2] = (char) Serial_Read_Data[16];
            bufOutCAN04[3] = (char) Serial_Read_Data[15];
            bufOutCAN04[4] = (char) Serial_Read_Data[14];
            bufOutCAN04[5] = (char) Serial_Read_Data[13];
            bufOutCAN04[6] = (char) Serial_Read_Data[12];
            bufOutCAN04[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 5)         //MM_COORD
        {
            bufOutCAN05[0] = (char) Serial_Read_Data[18];
            bufOutCAN05[1] = (char) Serial_Read_Data[17];
            bufOutCAN05[2] = (char) Serial_Read_Data[16];
            bufOutCAN05[3] = (char) Serial_Read_Data[15];
            bufOutCAN05[4] = (char) Serial_Read_Data[14];
            bufOutCAN05[5] = (char) Serial_Read_Data[13];
            bufOutCAN05[6] = (char) Serial_Read_Data[12];
            bufOutCAN05[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 6)         //REG_STATE
        {
            bufOutCAN06[0] = (char) Serial_Read_Data[18];
            bufOutCAN06[1] = (char) Serial_Read_Data[17];
            bufOutCAN06[2] = (char) Serial_Read_Data[16];
            bufOutCAN06[3] = (char) Serial_Read_Data[15];
            bufOutCAN06[4] = (char) Serial_Read_Data[14];
            bufOutCAN06[5] = (char) Serial_Read_Data[13];
            bufOutCAN06[6] = (char) Serial_Read_Data[12];
            bufOutCAN06[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 7)         //ARM_STATE
        {
            bufOutCAN07[0] = (char) Serial_Read_Data[18];
            bufOutCAN07[1] = (char) Serial_Read_Data[17];
            bufOutCAN07[2] = (char) Serial_Read_Data[16];
            bufOutCAN07[3] = (char) Serial_Read_Data[15];
            bufOutCAN07[4] = (char) Serial_Read_Data[14];
            bufOutCAN07[5] = (char) Serial_Read_Data[13];
            bufOutCAN07[6] = (char) Serial_Read_Data[12];
            bufOutCAN07[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 8)         //BVU_STATE_A
        {
            bufOutCAN08[0] = (char) Serial_Read_Data[18];
            bufOutCAN08[1] = (char) Serial_Read_Data[17];
            bufOutCAN08[2] = (char) Serial_Read_Data[16];
            bufOutCAN08[3] = (char) Serial_Read_Data[15];
            bufOutCAN08[4] = (char) Serial_Read_Data[14];
            bufOutCAN08[5] = (char) Serial_Read_Data[13];
            bufOutCAN08[6] = (char) Serial_Read_Data[12];
            bufOutCAN08[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 9)        //MCO_LIMITS_A
        {
            bufOutCAN09[0] = (char) Serial_Read_Data[18];
            bufOutCAN09[1] = (char) Serial_Read_Data[17];
            bufOutCAN09[2] = (char) Serial_Read_Data[16];
            bufOutCAN09[3] = (char) Serial_Read_Data[15];
            bufOutCAN09[4] = (char) Serial_Read_Data[14];
            bufOutCAN09[5] = (char) Serial_Read_Data[13];
            bufOutCAN09[6] = (char) Serial_Read_Data[12];
            bufOutCAN09[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 10)        //SYS_DATA_INPUT_DATA
        {
            bufOutCAN10[0] = (char) Serial_Read_Data[18];
            bufOutCAN10[1] = (char) Serial_Read_Data[17];
            bufOutCAN10[2] = (char) Serial_Read_Data[16];
            bufOutCAN10[3] = (char) Serial_Read_Data[15];
            bufOutCAN10[4] = (char) Serial_Read_Data[14];
            bufOutCAN10[5] = (char) Serial_Read_Data[13];
            bufOutCAN10[6] = (char) Serial_Read_Data[12];
            bufOutCAN10[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 11)        //SYS_DATA_STATE
        {
            bufOutCAN11[0] = (char) Serial_Read_Data[18];
            bufOutCAN11[1] = (char) Serial_Read_Data[17];
            bufOutCAN11[2] = (char) Serial_Read_Data[16];
            bufOutCAN11[3] = (char) Serial_Read_Data[15];
            bufOutCAN11[4] = (char) Serial_Read_Data[14];
            bufOutCAN11[5] = (char) Serial_Read_Data[13];
            bufOutCAN11[6] = (char) Serial_Read_Data[12];
            bufOutCAN11[7] = (char) Serial_Read_Data[11];
        }
        else if (Serial_Read_Data[19] == 12)         //AUX_RESOURCE_MM
        {
            bufOutCAN_EK[0] = (char) Serial_Read_Data[18];
            bufOutCAN_EK[1] = (char) Serial_Read_Data[17];
            bufOutCAN_EK[2] = (char) Serial_Read_Data[16];
            bufOutCAN_EK[3] = (char) Serial_Read_Data[15];
            bufOutCAN_EK[4] = (char) Serial_Read_Data[14];
            bufOutCAN_EK[5] = (char) Serial_Read_Data[13];
            bufOutCAN_EK[6] = (char) Serial_Read_Data[12];
            bufOutCAN_EK[7] = (char) Serial_Read_Data[11];
        }
        return;
    }
    //=================================================================================================

    //=================================================================================================
    //
    //=================================================================================================
    void status(String str)
    {
        SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    //=================================================================================================
    //
    //=================================================================================================
    class ControlLines
    {
        private static final int refreshInterval = 200; // msec

        private final Runnable runnable;


        ControlLines(View view)
        {
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
        }

        //=================================================================================================
        //
        //=================================================================================================
        private void toggle(View v)
        {
            ToggleButton btn = (ToggleButton) v;
            if (!connected) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }

            String ctrl = "";
        }

        //=================================================================================================
        //
        //=================================================================================================
        private void run()
        {
            if (!connected)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getControlLines();
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (Exception e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        //=================================================================================================
        //
        //=================================================================================================
        void start()
        {
            if (!connected)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getSupportedControlLines();

                run();
            } catch (Exception e) {
                Toast.makeText(getActivity(), "getSupportedControlLines() failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        //=================================================================================================
        //
        //=================================================================================================
        void stop()
        {
            mainLooper.removeCallbacks(runnable);
        }
    }
}
