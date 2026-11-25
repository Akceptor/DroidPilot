# ArduPilot configuration

Use the telemetry UART mapped to the board's `SERIALx` instance. Check the board documentation; connector labels do not always map directly to a parameter index.

- Set `SERIALx_PROTOCOL=2` for MAVLink 2 or `1` for MAVLink 1.
- Match `SERIALx_BAUD` and DroidPilot. ArduPilot uses a scaled baud enumeration: select the labeled baud in your GCS rather than entering the full bit rate blindly.
- For a TX/RX/GND connection, disable hardware flow control on that port if the firmware exposes a relevant flow-control setting.
- Set message rates for the available UART and network capacity. Use normal GCS message interval requests or firmware stream-rate parameters.
- Keep independent RC, battery, GCS and vehicle failsafes configured. DroidPilot never arms automatically or changes those parameters.

The router supports Rover, Copter and Plane by forwarding bytes without vehicle-specific command rewriting. Local system/component IDs are stored for future internal message generation. Phase 1 does not emit a companion heartbeat or GPS messages. Existing signed messages retain their original signature. Configure signing in the flight controller and GCS independently.

## Future MAVLink GPS backend

GNSS forwarding is not implemented and is disabled. For a future GPS_INPUT implementation, the current ArduPilot backend is selected with `GPS1_TYPE=14` (MAVLink); older firmware calls this `GPS_TYPE`. A second GPS has a corresponding second-instance parameter. Reboot after a backend change and verify the exact firmware documentation before use. Do not change a working GPS configuration for this Phase 1 app.

Before implementing GNSS, handle ellipsoid versus mean sea level altitude, actual measurement time, NED velocity, unavailable-field ignore flags, stale data and honest fix/accuracy reporting.

Sources: [serial setup](https://ardupilot.org/copter/docs/common-telemetry-port-setup.html), [MAVLink GPS input](https://ardupilot.org/mavproxy/docs/modules/GPSInput.html).
