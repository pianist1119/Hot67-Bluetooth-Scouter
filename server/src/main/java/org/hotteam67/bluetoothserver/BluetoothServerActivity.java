package org.hotteam67.bluetoothserver;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.ListView;

import org.hotteam67.common.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class BluetoothServerActivity extends AppCompatActivity
{
    // Messages, for when any event happens, to be sent to the main thread
    public static final int MESSAGE_INPUT = 0;
    public static final int MESSAGE_OTHER = 1;
    public static final int MESSAGE_DISCONNECTED = 2;
    public static final int MESSAGE_CONNECTED = 3;

    // Number of active and allowed devices
    private static final int allowedDevices = 7;

    // Bluetooth hardware adapter
    protected BluetoothAdapter m_bluetoothAdapter;
    protected boolean bluetoothFailed = false;
    public static final int REQUEST_BLUETOOTH = 1;

    private Handler m_handler;

    private List<String> clientNames = new ArrayList<>();

    protected void SetHandler(Handler h)
    {
        m_handler = h;
    }

    // Send a specific message, from the above list
    public synchronized void MSG(int msg) { m_handler.obtainMessage(msg, 0, -1, 0).sendToTarget(); }

    // Simple log function
    protected void l(String s)
    {
        Log.d("BLUETOOTH_SCOUTER_DEBUG", s);
    }

    /*
   Connect Thread
    */
    ConnectThread connectThread;
    private class ConnectThread extends Thread {
        private final Set<BluetoothDevice> devices;
        private List<BluetoothSocket> sockets;
        ConnectThread(Set<BluetoothDevice> devices)
        {
            this.devices = devices;

            sockets = new ArrayList<>();

            BluetoothSocket connectionSocket;
            for (BluetoothDevice device : this.devices)
            {

                connectionSocket = null;
                try
                {
                    l("Getting Connection");
                    connectionSocket = device.createRfcommSocketToServiceRecord(Constants.uuid);
                } catch (java.io.IOException e)
                {
                    Log.e("[Bluetooth]", "Failed to connect to socket", e);
                }
                sockets.add(connectionSocket);
            }
        }

        public void run()
        {
            for (BluetoothSocket connectionSocket : sockets)
            {
                try
                {
                    l("Connecting to socket");
                    connectionSocket.connect();
                    connectSocket(connectionSocket);
                } catch (java.io.IOException e)
                {
                    try
                    {
                        connectionSocket.close();
                    } catch (java.io.IOException e2)
                    {
                        Log.e("[Bluetooth]", "Failed to close socket after failure to connect", e2);
                    }
                }
            }
            l("Connect thread ended!");
        }

        public void cancel()
        {
            for (BluetoothSocket connectionSocket : sockets) {
                try {
                    connectionSocket.close();
                } catch (java.io.IOException e) {
                    Log.e("[Bluetooth]", "Failed to close socket", e);
                }
            }
        }
    }


    protected synchronized void Connect()
    {
        // Reset all connections
        if (connectedThreads.size() > 0)
            for (ConnectedThread thread : connectedThreads) disconnect(thread);

        l("Connecting");

        if (bluetoothFailed) {
            l("Failed to connect, bluetooth setup was unsuccessful");
            return;
        }

        Set<BluetoothDevice> pairedDevices = m_bluetoothAdapter.getBondedDevices();

        List<String> pairedNames =  new ArrayList<>();
        for (BluetoothDevice device : pairedDevices)
        {
            String name = device.getName();
            pairedNames.add(name);
        }

        // Queue for the selected items, starts with those previously selected
        List<String> selected = new ArrayList<>();
        boolean[] prevClients = new boolean[pairedNames.size()];
        for (String name : clientNames)
        {
            if (pairedNames.contains(name))
            {
                int index = pairedNames.indexOf(name);
                prevClients[index] = true;
                selected.add(name);
            }
        }

        AlertDialog.Builder ab = new AlertDialog.Builder(this);
        ab.setTitle("Choose Devices to Connect To").setMultiChoiceItems(
                pairedNames.toArray(new CharSequence[pairedNames.size()]),
                prevClients, (dialogInterface, i, b) ->
                {
                    if (b)
                    {
                        selected.add(0, pairedNames.get(i)); // Put current at start of queue

                        ListView list = ((AlertDialog) dialogInterface).getListView();
                        // Limit to 6, so fix it if more than 6
                        if (selected.size() > 6)
                        {
                            // Uncheck last and remove it
                            int lastIndex = pairedNames.indexOf(selected.get(6));
                            list.setItemChecked(lastIndex, false);

                            selected.remove(6);

                        }
                    }
                    else
                    {
                        selected.remove(pairedNames.get(i));
                    }
                }).setPositiveButton("Ok", (dialogInterface, i) ->
        {
            clientNames = selected;

            Set<BluetoothDevice> devices = new HashSet<>();
            for (BluetoothDevice pairedDevice : pairedDevices)
            {
                if (clientNames.contains(pairedDevice.getName()))
                    devices.add(pairedDevice);
            }

            connectThread = new ConnectThread(devices);
            connectThread.start();
        }).create().show();
    }

    private void connectSocket(BluetoothSocket connection)
    {

        if (connectedThreads.size() < allowedDevices)
        {
            l("Received a connection, adding a new thread: " + connectedThreads.size());
            ConnectedThread thread = new ConnectedThread(connection);
            thread.setId(connectedThreads.size() + 1);
            thread.start();
            connectedThreads.add(thread);
            MSG(MESSAGE_CONNECTED);
        }

    }

    // Disconnect a specific connected device, usually called from the thread itself
    private synchronized void disconnect(ConnectedThread thread)
    {
        thread.close();
        thread.interrupt();
        connectedThreads.remove(thread);
    }

    //
    // An arraylist of threads for each connected device,
    // with a unique id for when they finish so they may be removed
    //
    private ArrayList<ConnectedThread> connectedThreads = new ArrayList<>();
    private class ConnectedThread extends Thread
    {
        private BluetoothSocket connectedSocket;
        private byte[] buffer;
        private int id;

        private void setId(int i ) { id = i; }

        ConnectedThread(BluetoothSocket sockets)
        {
            connectedSocket = sockets;
        }

        void close()
        {
            try
            {
                connectedSocket.close();
            }
            catch (Exception e)
            {
                l("Error: " + e.getMessage());
                e.printStackTrace();
            }
        }
        public void run()
        {
            while (!Thread.currentThread().isInterrupted())
            {
                InputStream stream;
                InputStream tmpIn = null;
                try {
                    l("Loading input stream");
                    tmpIn = connectedSocket.getInputStream();
                } catch (IOException e) {
                    Log.e("[Bluetooth]", "Error occurred when creating input stream", e);
                }
                stream = tmpIn;

                l("Reading stream");
                if (!read(stream))
                {
                    break;
                }

                if (Thread.currentThread().isInterrupted())
                {
                    break;
                }
            }
            l("Connected Thread Ended!!!");
            disconnect(this);
            MSG(MESSAGE_DISCONNECTED);
        }

        private boolean read(InputStream stream)
        {
            buffer = new byte[1024];
            int numBytes;
            try
            {
                numBytes = stream.read(buffer);

                l("Reading Bytes of Length:" + numBytes);

                m_handler.obtainMessage(MESSAGE_INPUT, numBytes, id, new String(buffer, "UTF-8").substring(0, numBytes).replace("\0", "")).sendToTarget();
                return true;
            }
            catch (java.io.IOException e)
            {
                Log.d("[Bluetooth]", "Input stream disconnected", e);
                return false;
            }
        }


        public void write(byte[] bytes)
        {
            l("Writing: " + new String(bytes));
            l("Bytes Length: " + bytes.length);
            OutputStream stream;

            OutputStream tmpOut = null;
            try {
                tmpOut = connectedSocket.getOutputStream();
            } catch (IOException e) {
                Log.e("[Bluetooth]", "Error occurred when creating output stream", e);
            }
            stream = tmpOut;

            try
            {
                l("Writing bytes to outstream");
                stream.write(bytes);
            }
            catch (Exception e)
            {
                Log.e("[Bluetooth]", "Failed to send data", e);
                disconnect(this);
            }
        }


        public void cancel()
        {
            try
            {
                connectedSocket.close();
            }
            catch (java.io.IOException e)
            {
                Log.e("[Bluetooth]", "Failed to close socket", e);
            }
        }
    }

    // Write to all threads, returns number of connected devices
    protected synchronized int WriteAllDevices(byte[] bytes)
    {
        // Send to each device
        for (ConnectedThread device : connectedThreads) {
            device.write(bytes);
        }
        return connectedThreads.size();
    }

    // Write a specific thread (by index). Returns number of connected devices
    protected synchronized int WriteDevice(byte[] bytes, int index)
    {
        if (connectedThreads.size() > index)
            connectedThreads.get(index).write(bytes);
        return connectedThreads.size();
    }

    protected synchronized int GetDevices()
    {
        return connectedThreads.size();
    }

    // Initialize the bluetooth hardware adapter
    protected synchronized void setupBluetooth(Runnable oncomplete)
    {
        l("Getting adapter");
        m_bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();


        if (m_bluetoothAdapter == null) {
            l("Bluetooth not detected");
            bluetoothFailed = true;
        }

        if (!bluetoothFailed && !m_bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_BLUETOOTH);
        }
        else
        {
            oncomplete.run();
        }

    }

    //
    // This is to handle the enable bluetooth activity,
    // and disable all attempts at bluetooth functionality
    // if for some reason the user denies permission
    //
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {

        if (requestCode==REQUEST_BLUETOOTH)
        {
            bluetoothFailed = (resultCode != RESULT_OK);
        }
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        l("Destroying application threads");
        if (bluetoothFailed)
            return;
        for (ConnectedThread thread : connectedThreads)
        {
            thread.close();
            thread.interrupt();
        }
    }
}
