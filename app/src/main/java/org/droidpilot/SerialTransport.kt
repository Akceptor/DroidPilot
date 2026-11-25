package org.droidpilot

import android.hardware.usb.*
import com.hoho.android.usbserial.driver.*
import kotlinx.coroutines.*
import org.droidpilot.core.*
import java.io.Closeable

fun UsbDevice.selectionKey() = "$vendorId:$productId"
fun serialDevices(manager:UsbManager):List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

class SerialTransport(private val manager:UsbManager,private val config:PilotConfig,private val router:Router):Closeable {
    @Volatile private var port:UsbSerialPort?=null
    suspend fun run():Unit=coroutineScope {
        val driver=serialDevices(manager).firstOrNull { config.usbKey.isEmpty() || it.device.selectionKey()==config.usbKey }
            ?: error("USB adapter unavailable")
        check(manager.hasPermission(driver.device)) { "USB permission required: open MAVLink settings" }
        val connection=manager.openDevice(driver.device) ?: error("USB open failed")
        val opened=driver.ports.getOrNull(config.usbPort) ?: run { connection.close();error("USB port unavailable") }
        val queue=PacketQueue()
        try {
            opened.open(connection);port=opened
            opened.setParameters(config.baud,8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE)
            router.add("USB",queue);AppState.connection("USB","Connected at ${config.baud}")
            val reader=launch(Dispatchers.IO) {
                val buffer=ByteArray(4096)
                while(isActive) {
                    val count=opened.read(buffer,200)
                    if(count>0) router.receive("USB",buffer.copyOf(count))
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    while(isActive) {
                        val frame=queue.poll()
                        if(frame==null) delay(5) else {
                            opened.write(frame,200);router.sent(frame.size)
                        }
                    }
                }
            } finally { reader.cancelAndJoin() }
        } finally {
            router.remove("USB");queue.clear();port=null
            runCatching { opened.close() };connection.close()
        }
    }
    override fun close() { runCatching { port?.close() } }
}
