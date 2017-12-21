package br.com.montreal.sample_td2555

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import kotlin.experimental.and

class MainActivity : AppCompatActivity() {

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var mLEScanner: BluetoothLeScanner? = null
    //DEVICE SERVICE
    private var mBluetoothGattService: BluetoothGattService? = null
    private var mBluetoothGattCharacteristic: BluetoothGattCharacteristic? = null

    lateinit var filters: List<ScanFilter>
    lateinit var settings: ScanSettings

    var mGatt: BluetoothGatt? = null

    private var mDevice: BluetoothDevice? = null
    private var isBusy = false
    private var deviceIdentify: String? = "2555"
    private var isFirstOnCharacteristicChanged = true
    private var weigthPreviousBytes: MutableList<Byte> = mutableListOf()

    companion object {
        val PERMISSION_REQUEST_COARSE_LOCATION = 1
        val MANUFACTURER = "TAIDOC"

        val STATE_DISCONNECTED = 0
        val STATE_CONNECTING = 1
        val STATE_CONNECTED = 2
        val STATE_OTHER = 3

        var SERVICE_UUID = "00001523-1212-efde-1523-785feabcd123"
        var CHARACTERISTIC_UUID = "00001524-1212-efde-1523-785feabcd123"

        var connectionState = 0

        var TAG = "BluetoothHelper"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        verifyPermission()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && requestCode == PERMISSION_REQUEST_COARSE_LOCATION) {
            bluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            mLEScanner = bluetoothAdapter?.bluetoothLeScanner

            activateScan()
        } else {
            Log.d(TAG, "no permission")
        }
    }

    fun verifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                        PERMISSION_REQUEST_COARSE_LOCATION
                )
            } else {
                bluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bluetoothAdapter = bluetoothManager?.adapter
                mLEScanner = bluetoothAdapter?.bluetoothLeScanner

                activateScan()
            }
        } else {
            bluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            mLEScanner = bluetoothAdapter?.bluetoothLeScanner

            activateScan()
        }
    }

    var mScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val deviceName = result?.device?.name
            deviceName?.let {
                if (it.startsWith(MANUFACTURER) and it.contains(deviceIdentify ?: return)) {
                    stopScan()
                    Log.d(TAG, "Found device: $deviceName")
                    connectToDevice(result.device)
                }
            }
        }
    }

    val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG,"onConnectionStateChange => Status: $status / newStatus: $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG,"gattCallback => Status: STATE_CONNECTED")
                    connectionState = STATE_CONNECTED
                    mGatt?.let {
                        Log.d(TAG,"Ble => Connected to device: $mDevice")
                        stopScan()

                        it.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG,"gattCallback => Status: STATE_DISCONNECTED")
                    connectionState = STATE_DISCONNECTED
                    isBusy = false
                    mGatt?.close()
                    mGatt?.disconnect()
                    mGatt = null
                    mDevice = null
                    mBluetoothGattCharacteristic = null
                    mBluetoothGattService = null
                    isFirstOnCharacteristicChanged = true
                    weigthPreviousBytes = mutableListOf()

                    activateScan()
                }
                else -> {
                    Log.d(TAG, "gattCallback => Status: $STATE_OTHER")
                    connectionState = STATE_OTHER
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG,"onDescriptorWrite")

                sendCommandAsync(0x71)
            } else {
                Log.w(TAG, "onDescriptorWrite received: " + status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG,"onServicesDiscovered")
                setService(SERVICE_UUID)
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt,
                                             characteristic: BluetoothGattCharacteristic) {
            Log.d(TAG,"onCharacteristicChanged: " + mGatt?.device?.name)

            val data = characteristic.value

            if (data[1].compareTo(0x52) == 0) {
                turnOffDevice()
                return
            }

            if (data[1].compareTo(0x50) == 0) {
                return
            }

            if (data.size < 8) {
                return
            }

            for (item in data) {
                weigthPreviousBytes.add(item)
            }

            if (weigthPreviousBytes.size > 23) {
                val taiDocWeightScale = translateResult(weigthPreviousBytes.toByteArray())

                if (taiDocWeightScale != Double.NaN) {
                    runOnUiThread {
                        lblInfo.text = "Medição: $taiDocWeightScale kg"
                    }
                }

                clearDevice()
                weigthPreviousBytes = mutableListOf()
            }
        }
    }

    fun translateResult(rawData: ByteArray): Double {
        if (rawData[1].compareTo(0x71) == 0) {
            if (rawData[17].toInt() > 0) {
                return ((rawData[16].toInt() shl 8) + rawData[17].toInt()) * 0.1
            } else {
                return ((rawData[16].toInt() shl 8) + (rawData[17].toInt() + 256)) * 0.1
            }
        }

        return Double.NaN
    }

    fun connectToDevice(device: BluetoothDevice) {
        if (mGatt == null) {
            isBusy = true
            Log.d(TAG,"Connecting to device: " + device.toString())
            this.mDevice = device
            mGatt = device.connectGatt(this, false, gattCallback)
        }
    }

    fun activateScan() {
        settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        filters = emptyList<ScanFilter>()
        scanLeDevice()
    }

    private fun scanLeDevice() {
        mLEScanner?.startScan(filters, settings, mScanCallback)
    }

    fun stopScan() {
        mLEScanner?.stopScan(mScanCallback)
    }

    fun disconnect() {
        mGatt?.apply {
            disconnect()
            close()
        }
    }

    fun setService(serviceUuid: String) {
        try {
            Log.d(TAG, "setService")
            mBluetoothGattService = mGatt?.getService(UUID.fromString(serviceUuid))
            Log.d(TAG,"mBluetoothGattService: " + mBluetoothGattService)

            if (mBluetoothGattService != null) {
                setCharacterisc(CHARACTERISTIC_UUID)
            } else {
                stopScan()
                disconnect()
                Log.d(TAG,"Ocorreu um erro na conexão com o dispositivo, por favor aguarde que estamos restabelecendo a conexão.")
                Thread.sleep(10000)
                mGatt = mDevice?.connectGatt(this, false, gattCallback)
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            return
        }
    }

    fun setCharacterisc(characteristicUuid: String) {
        try {
            Log.d(TAG,"setCharacterisc")
            mBluetoothGattCharacteristic = mBluetoothGattService?.getCharacteristic(UUID.fromString(characteristicUuid))
            Log.d(TAG,"mBluetoothGattCharacteristic: " + mBluetoothGattCharacteristic)

            if (mBluetoothGattCharacteristic != null) {
                mBluetoothGattCharacteristic?.let {
                    val charaProp = it.properties
                    var cccd: ByteArray? = null

                    if (charaProp != null) {
                        if ((charaProp and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            cccd = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            Log.d(TAG,"enable Notification")
                        } else if ((charaProp and BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0) {
                            cccd = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            Log.d(TAG,"enable Indicate")
                        } else {
                            mGatt?.readCharacteristic(mBluetoothGattCharacteristic)
                            Log.d(TAG,"none descriptor")
                        }
                    }

                    if (cccd != null) {
                        for (descriptor in it.descriptors) {
                            descriptor.value = cccd
                            if (mGatt?.writeDescriptor(descriptor) == true) {
                                Log.d(TAG,"writeDescriptor: SUCCESS")
                            } else {
                                Log.d(TAG,"writeDescriptor: FAIL")
                            }
                        }

                        if (mGatt?.setCharacteristicNotification(mBluetoothGattCharacteristic, true) == true) {
                            Log.d(TAG,"setCharacteristicNotification: SUCCESS")
                        } else {
                            Log.d(TAG,"setCharacteristicNotification: FAIL")
                        }
                    }
                }
            } else {
                stopScan()
                disconnect()
                Log.d(TAG,"Ocorreu um erro na conexão com o dispositivo, por favor aguarde que estamos restabelecendo a conexão.")
                Thread.sleep(10000)
                mGatt = mDevice?.connectGatt(this, false, gattCallback)
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            return
        }
    }

    fun sendCommandAsync(command: Byte): Boolean {
        return sendCommandAsync(command, null)
    }

    fun sendCommandAsync(command: Byte, param: Any?): Boolean {
        var data: List<Byte>? = null

        if (command.compareTo(0x71) == 0) {
            data = Arrays.asList(0x51.toByte(), command, 0x2.toByte(), 0x0.toByte(), 0x0.toByte(), 0xA3.toByte())
        } else {
            data = Arrays.asList(0x51.toByte(), command, 0x0.toByte(), 0x0.toByte(), 0x0.toByte(), 0x0.toByte(), 0xA3.toByte())
        }

        val checksum = getChecksum(data!!.toTypedArray())

        val byteArray = ByteArray(data.size + 1)
        var i = 0
        for (b in data) {
            byteArray[i++] = b
        }

        byteArray[byteArray.size - 1] = checksum

        return writeCharacteristic(byteArray) ?: false
    }

    fun getChecksum(dataArray: Array<Byte>): Byte {
        var checksum = 0
        for (i in dataArray.indices) {
            checksum += dataArray[i] and 0xFF.toByte()
        }

        return checksum.toByte()
    }

    fun clearDevice(): Boolean {
        return sendCommandAsync(0x52)
    }

    fun turnOffDevice() {
        sendCommandAsync(0x50)
    }

    fun writeCharacteristic(message: ByteArray): Boolean {
        try {
            val charaProp = mBluetoothGattCharacteristic?.properties

            if (charaProp != null) {
                if (charaProp or BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE != 0) {

                    mBluetoothGattCharacteristic?.value = message
                    mBluetoothGattCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    return mGatt?.writeCharacteristic(mBluetoothGattCharacteristic)?:false

                } else {
                    mBluetoothGattCharacteristic?.value = message
                    mGatt?.writeCharacteristic(mBluetoothGattCharacteristic)
                    return readCharacteristic()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return false
    }

    fun readCharacteristic(): Boolean {
        try {
            val charaProp = mBluetoothGattCharacteristic?.properties

            if (charaProp != null) {
                if (charaProp or BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    return mGatt?.readCharacteristic(mBluetoothGattCharacteristic)?:false

                } else
                    return false
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return false
    }
}
