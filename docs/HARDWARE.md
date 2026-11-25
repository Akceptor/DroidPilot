# Hardware

Use an Android API 28+ phone with USB Host support. Use a powered USB-C OTG hub with verified simultaneous host data and PD passthrough. PD support alone does not prove that the phone can charge while hosting USB devices.

Connect a supported USB-to-UART adapter to an ArduPilot telemetry UART:

| USB UART | Flight controller |
| --- | --- |
| TX | Telemetry RX |
| RX | Telemetry TX |
| GND | GND |

Use the controller's documented logic voltage, normally 3.3 V TTL. Do not use RS-232 voltage levels. Do not connect the adapter power pin unless the board power design explicitly requires it. Confirm the board pinout. Phase 1 uses 8N1 without RTS/CTS.

CP210x, CH340/CH341, FTDI and CDC ACM are supplied by usb-serial-for-android. Driver availability is verified at build time; actual adapters are untested. If two adapters share VID/PID, selection currently uses the first matching device. Multiport adapters have a zero-based port setting. USB permission can be lost after detach; grant it again if Android requires this.

Close other serial applications before connecting. Select the adapter in MAVLink settings and grant USB permission. Match the UART baud to the controller.

Built-in cameras must expose Camera2 surface capture at the selected size and frame rate. The app lists logical camera IDs. It does not assume access to every physical lens. Use capabilities from Diagnostics and try a conservative supported size first.

UVC capture is deferred to Phase 2. Simultaneous serial and UVC operation, hub bandwidth, PD passthrough, thermal behavior and external H.264 passthrough are unverified. The synthetic source exercises the surface encoder without a camera; it still needs a real Android hardware encoder.
