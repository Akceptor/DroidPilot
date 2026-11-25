# SITL test

This is a manual integration procedure. It has not been run with an Android phone. The automated JVM suite tests bidirectional UDP routing on loopback with valid MAVLink frames.

1. Install ArduPilot SITL and MAVProxy using the [official simulator instructions](https://ardupilot.org/dev/docs/sitl-simulator-software-in-the-loop.html).
2. Put the phone and simulator on a reachable Wi-Fi, Ethernet or VPN network. Find the phone's reachable IPv4 address in Network settings.
3. In DroidPilot select UDP input, local bind `0.0.0.0`, vehicle listener port `14550`. Add the GCS endpoint `GCS_IP:14550@14551`, replacing GCS_IP with a numeric address. Save and start.
4. Start a simulator, replacing PHONE_IP with its numeric address:

```sh
sim_vehicle.py -v Rover --console --map --out=udp:PHONE_IP:14550
```

For Copter use `-v ArduCopter`; for Plane use `-v ArduPlane`. MAVProxy forwards commands from its output link when `mavfwd` is enabled. If your MAVProxy setup disables it, enable it for this test. Do not create an additional simulator-to-GCS path during router verification.

5. In Mission Planner select UDP, connect, and listen on port `14550`.
6. Confirm that DroidPilot receives an ArduPilot heartbeat. Check system/type, byte counters and packet counters. Fetch a parameter from Mission Planner to verify the return path. Do not use arming as a connectivity test.
7. Stop SITL for at least ten seconds. Confirm that the heartbeat becomes stale. Restart it and check recovery. Repeat with the network disconnected/reconnected. Confirm that old commands are not sent after recovery.
8. Add a second explicit GCS peer with a different local port and confirm both receive telemetry. Disconnect one and confirm the other continues.

Use [ZeroTier instructions](NETWORK.md) when phone and GCS are on different networks. Restrict UDP access to the intended peers. The listener learns and pins one sender; an unauthorized first sender can occupy it. Stop/start the runtime to reset it immediately.

For a direct SITL UDP serial backend without MAVProxy, use the firmware's documented `--serial0=udpclient:PHONE_IP:14550` option and `--no-mavproxy`. Verify the options with the installed simulator's help before using this alternative. See [SITL networking](https://ardupilot.org/dev/docs/using-sitl-for-ardupilot-testing.html).
