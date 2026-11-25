# Network and Mission Planner

DroidPilot uses normal Android socket routing. It does not bind exclusively to Wi-Fi or cellular, create a VPN, or use broadcast discovery. UDP source ports are configurable. Network diagnostics list Android transport types and interface addresses.

## ZeroTier telemetry

1. Install the separate ZeroTier app on Android and on the GCS computer.
2. Join the same private network. Authorize both members. Record their assigned numeric addresses. Keep membership and credentials out of this repository.
3. Keep ZeroTier active. Android normally permits only one active VPN; another VPN app can displace it.
4. In DroidPilot add a remote endpoint `GCS_ZEROTIER_IP:14550@14551`. Use `@0` instead of `@14551` if a fixed phone source port is unnecessary. Use local bind `0.0.0.0` for IPv4. Select USB input for a physical controller, or UDP for SITL.
5. In Mission Planner choose UDP and listen on `14550`. Allow incoming UDP `14550` and video `5600` in the computer's firewall on the intended network profile. Mission Planner must reply to the packet's phone source port, `14551` in this example.
6. Start DroidPilot from its visible Activity. Verify heartbeat, telemetry and a parameter fetch through this path.
7. Lock the screen. Verify continued traffic. Interrupt ZeroTier, restore it, and verify recovery without restarting the Activity.

Use explicit addresses; VPN broadcast and multicast are not required. Default wildcard binding includes all reachable interfaces. MAVLink UDP is not authenticated by this app. Use a trusted private network, firewall rules and flight-controller signing as appropriate. There is no unauthenticated web command API.

## RTP/H.264 video

Enable video and set the destination to the GCS address, port `5600`. Select an actual supported camera size and frame rate. H.264 payload type is 96, clock rate 90000. The phone sends SPS/PPS with keyframes and does not send SDP or RTCP.

First verify reception with GStreamer on the GCS:

```sh
gst-launch-1.0 udpsrc port=5600 caps='application/x-rtp,media=video,encoding-name=H264,clock-rate=90000,payload=96' ! rtph264depay ! h264parse ! avdec_h264 ! videoconvert ! autovideosink sync=false
```

In Mission Planner, right-click HUD → Video → Set GStreamer Source. A candidate pipeline is:

```text
udpsrc port=5600 caps="application/x-rtp,media=video,encoding-name=H264,clock-rate=90000,payload=96" ! rtph264depay ! h264parse ! avdec_h264 ! videoconvert ! video/x-raw,format=BGRA ! appsink name=outsink sync=false
```

Install the GStreamer components required by your Mission Planner version. The pipeline is not device/GCS verified. Check the standalone receiver before debugging the HUD. Do not run both receivers on the same UDP port at once. Video orientation is native sensor orientation in Phase 1.

Source: [Mission Planner video interface](https://ardupilot.org/planner/docs/mission-planner-flight-data.html).
