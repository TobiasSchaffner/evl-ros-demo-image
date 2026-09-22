//! ros-evl-demo: Single-binary EVL real-time + ROS 2 bridge demo
//!
//! Demonstrates bidirectional communication between an EVL real-time
//! thread and ROS 2 topics using a private cross-buffer (xbuf).
//!
//! Architecture:
//!
//!   RT thread (FIFO 80)                    NRT main thread
//!   ┌─────────────────┐                   ┌──────────────────┐
//!   │ 1kHz timer loop  │  ──oob_write──>  │ read() ──> pub   │ ──> /rt/sensor
//!   │ sine wave gen    │                   │    /rt/sensor    │
//!   │                  │  amplitude (atom) │                  │
//!   │ reads AtomicU64  │  <── store ────── │ sub callback     │ <── /rt/command
//!   └─────────────────┘                   └──────────────────┘
//!
//! The xbuf is created as a private element (no /dev/evl device file).
//! Both RT and NRT sides access the same fd — oob_write/oob_read from
//! the RT side, regular read()/write() from the NRT side.
//!
//! The RT→NRT path uses the xbuf to demonstrate high-bandwidth
//! zero-copy IPC. The NRT→RT path uses an atomic variable for the
//! amplitude control, which is the typical pattern for low-bandwidth
//! parameter updates to RT threads.

use std::io::Write;
use std::os::raw::c_int;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use embedded_time::duration::{Milliseconds, Nanoseconds};
use futures::{FutureExt, StreamExt};
use revl::clock::STEADY_CLOCK;
use revl::sched::SchedAttrs;

/// Extract the raw file descriptor from a revl::xbuf::XBuf.
///
/// XBuf is a newtype `pub struct XBuf(pub(crate) c_int)`.
/// The field is pub(crate) so external code reads it via pointer cast.
fn xbuf_fd(xbuf: &revl::xbuf::XBuf) -> c_int {
    unsafe { *(xbuf as *const _ as *const c_int) }
}

/// Shared state between RT and NRT threads.
struct SharedState {
    /// Signal to stop all threads.
    running: AtomicBool,
    /// Amplitude control: NRT writes, RT reads.
    /// Stored as f64 bits in AtomicU64 for lock-free access.
    amplitude: AtomicU64,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    eprintln!("[ros-evl-demo] Initializing EVL core...");
    revl::init()?;

    let pid = std::process::id();

    // Create a private xbuf for RT→NRT sensor data.
    // Private = no device file in /dev/evl, both sides share the fd.
    let xbuf = revl::xbuf::Builder::new()
        .name(&format!("evl-demo-{pid}"))
        .output_size(4096) // RT→NRT (sensor data)
        .input_size(1024) // NRT→RT (unused, but required > 0)
        .create()?;

    // Duplicate the fd for NRT in-band I/O.
    let nrt_fd = unsafe { libc::dup(xbuf_fd(&xbuf)) };
    assert!(
        nrt_fd >= 0,
        "dup() failed: {}",
        std::io::Error::last_os_error()
    );

    let state = Arc::new(SharedState {
        running: AtomicBool::new(true),
        amplitude: AtomicU64::new(f64::to_bits(1.0)),
    });

    // Spawn the RT producer thread
    let state_rt = state.clone();
    let rt_handle = std::thread::spawn(move || {
        rt_loop(xbuf, state_rt);
    });

    // Run the NRT ROS 2 loop on the main thread
    let result = nrt_loop(nrt_fd, state.clone());

    // Shutdown
    state.running.store(false, Ordering::Relaxed);
    rt_handle.join().expect("RT thread panicked");
    unsafe {
        libc::close(nrt_fd);
    }

    result
}

/// Real-time producer loop.
///
/// Runs at 1kHz on a SCHED_FIFO 80 EVL thread. Generates a sine wave
/// and sends each sample (f64, 8 bytes) through the xbuf via oob_write.
fn rt_loop(mut xbuf: revl::xbuf::XBuf, state: Arc<SharedState>) {
    // Attach this thread to the EVL core
    // public so the thread shows up in /sys/devices/virtual/evl/thread
    // and can be inspected with "evl ps"
    let _thread = revl::thread::Builder::new()
        .name(&format!("rt-demo-{}", std::process::id()))
        .public()
        .sched(SchedAttrs::FIFO(80.into()))
        .attach()
        .expect("Failed to attach RT thread to EVL");

    // Set up a 1kHz periodic timer on the monotonic clock
    let timer =
        revl::timer::Timer::new(STEADY_CLOCK).expect("Failed to create EVL timer");

    let start = STEADY_CLOCK.now() + Milliseconds(100_u32);
    let period: Nanoseconds<u64> = Milliseconds(1_u32).into();
    timer
        .arm_periodic(start, period)
        .expect("Failed to arm periodic timer");

    eprintln!("[ros-evl-demo] RT thread running at 1kHz (SCHED_FIFO 80)");

    let mut seq: u64 = 0;

    while state.running.load(Ordering::Relaxed) {
        // Wait for next timer tick (RT-safe, out-of-band)
        if let Err(e) = timer.wait() {
            eprintln!("[ros-evl-demo] Timer error: {e}");
            break;
        }

        // Read amplitude from NRT side (lock-free atomic)
        let amplitude = f64::from_bits(state.amplitude.load(Ordering::Relaxed));

        // Generate sine wave sample
        let phase = seq as f64 * 0.001 * std::f64::consts::TAU; // 1Hz sine at 1kHz sample rate
        let value = amplitude * phase.sin();

        // Send to NRT side via oob_write (RT-safe, never blocks to in-band)
        if xbuf.write_all(&value.to_le_bytes()).is_err() {
            break;
        }

        seq += 1;
    }
}

/// NRT event loop: reads sensor data from xbuf and publishes to ROS 2.
///
/// Also subscribes to /rt/command for amplitude control.
fn nrt_loop(
    nrt_fd: c_int,
    state: Arc<SharedState>,
) -> Result<(), Box<dyn std::error::Error>> {
    // Create ROS 2 node
    let ctx = r2r::Context::create()?;
    let mut node = r2r::Node::create(ctx, "evl_demo", "")?;

    // Publisher: RT sensor data → /rt/sensor
    let publisher = node.create_publisher::<r2r::std_msgs::msg::Float64>(
        "/rt/sensor",
        r2r::QosProfile::default(),
    )?;

    // Subscriber: /rt/command → amplitude control
    let subscriber = node.subscribe::<r2r::std_msgs::msg::Float64>(
        "/rt/command",
        r2r::QosProfile::default(),
    )?;
    let mut subscriber = Box::pin(subscriber);

    // Sensor reader thread: blocking read() on xbuf NRT side → channel
    let (tx, rx) = std::sync::mpsc::sync_channel::<f64>(256);
    let reader_state = Arc::clone(&state);
    let read_fd = nrt_fd; // c_int is Copy
    std::thread::spawn(move || {
        let mut buf = [0u8; 8]; // f64 = 8 bytes
        while reader_state.running.load(Ordering::Relaxed) {
            let n = unsafe {
                libc::read(read_fd, buf.as_mut_ptr() as *mut libc::c_void, 8)
            };
            if n == 8 {
                let _ = tx.send(f64::from_le_bytes(buf));
            } else if n <= 0 {
                break;
            }
        }
    });

    eprintln!(
        "[ros-evl-demo] ROS 2 node ready\n\
         [ros-evl-demo]   Publishing:   /rt/sensor (std_msgs/Float64)\n\
         [ros-evl-demo]   Subscribing:  /rt/command (std_msgs/Float64)"
    );

    // Main NRT event loop
    while state.running.load(Ordering::Relaxed) {
        // 1. Drain sensor data from RT and publish to ROS
        while let Ok(value) = rx.try_recv() {
            let mut msg = r2r::std_msgs::msg::Float64::default();
            msg.data = value;
            publisher.publish(&msg)?;
        }

        // 2. Process ROS 2 callbacks (drives subscription streams)
        node.spin_once(Duration::from_millis(1));

        // 3. Check for command messages (non-blocking poll)
        while let Some(Some(msg)) = subscriber.next().now_or_never() {
            let new_amp = msg.data;
            state
                .amplitude
                .store(f64::to_bits(new_amp), Ordering::Relaxed);
            eprintln!("[ros-evl-demo] Amplitude set to {new_amp:.3}");
        }
    }

    Ok(())
}
