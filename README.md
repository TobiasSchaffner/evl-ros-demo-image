# ISAR ROS Xenomai Image

An ISAR layer that builds an Ubuntu 26.04 image with a Xenomai 4 (EVL)
real-time kernel and ROS 2 Lyrical from packages.ros.org.

The kernel and libevl come from the xenomai-images layer, the rest is plain
ISAR. The EVL side is pinned to ABI 45: kernel 6.18 (v6.18.22-evl5-rebase)
and libevl r59.

## Building

You need podman or docker. kas-container fetches isar and xenomai-images
itself, so a plain checkout is enough.

```
KAS_CONTAINER_ENGINE=podman ./kas-container build kas/qemu-amd64.yml
```

`kas/raspberry-pi4.yml` builds for the Pi 4 instead. Appending
`:kas/opt-debug.yml` adds gdb, strace, ltrace and valgrind to the image and
builds the kernel with the EVL debug options.

The kas configs set `build_system: isar-privileged`, so the build container
runs privileged, which with podman means sudo. Rootless builds work as well
(`isar-rootless` plus `./kas-container --isar-rootless`), but only with a
subuid/subgid range larger than 65536 — isar's bootstrap nests a second user
namespace inside the container and the usual 65536 leaves nothing for it.

## Running

```
./isar/scripts/start_vm -a amd64 -d ros-xenomai -i ros-xenomai-image -b build
```

Login is root/root. ROS 2 sits in /opt/ros/lyrical, so source
`/opt/ros/lyrical/setup.bash` before using it.

Under QEMU the EVL core comes up and `evl check` works, but the latency
numbers it reports are meaningless. Use real hardware for that.

## ros-evl-demo

The image ships ros-evl-demo, a Rust binary that runs an EVL real-time thread
and a ROS 2 node in one process. The real-time side generates a 1 kHz sine
wave and pushes the samples through a cross-buffer, the ROS side publishes
them on /rt/sensor. Writing a float to /rt/command changes the amplitude.

```
source /opt/ros/lyrical/setup.bash
ros-evl-demo
```

r2r, revl and evl-sys are not packaged by Ubuntu, so recipes-ros/librust-r2r,
recipes-xenomai/librust-revl and recipes-xenomai/librust-evl-sys unpack their
crate sources into /usr/share/cargo/registry, where dh-cargo picks them up.
Everything else those crates need comes from Ubuntu's librust-*-dev packages.
