#
# ros-evl-demo: EVL real-time + ROS 2 bridge demo (Rust)
#
# Single binary combining an EVL RT thread (1kHz periodic timer, xbuf IPC)
# with a ROS 2 node (publisher + subscriber) using revl and r2r.
#
# SPDX-License-Identifier: MIT
#

inherit dpkg

DESCRIPTION = "EVL real-time and ROS 2 bridge demo in Rust"

SRC_URI = " \
    file://src \
    file://Cargo.toml \
    file://debian \
"

DEPENDS = "libevl librust-evl-sys librust-revl librust-r2r"

S = "${WORKDIR}/ros-evl-demo"

# cargo is called directly (see debian/rules), so it would build for the
# build machine. Build in the target chroot instead; this is a no-op when
# target and host architecture match.
ISAR_CROSS_COMPILE = "0"

# Everything except the EVL and r2r crates comes from Ubuntu; those three
# -dev packages pull in their own crate dependencies.
DEBIAN_BUILD_DEPENDS = " \
    debhelper-compat (= ${DEBIAN_COMPAT}), \
    dh-cargo, \
    rustc, \
    cargo, \
    pkg-config, \
    libevl, \
    librust-evl-sys-dev, \
    librust-revl-dev, \
    librust-r2r-dev, \
    librust-libc-dev, \
    librust-futures-dev, \
    ros-${ROS_DISTRO}-rcl, \
    ros-${ROS_DISTRO}-rcl-action, \
    ros-${ROS_DISTRO}-std-msgs, \
    ros-${ROS_DISTRO}-builtin-interfaces, \
    ros-${ROS_DISTRO}-rosidl-default-generators \
"

TEMPLATE_FILES = "debian/control.tmpl"
TEMPLATE_VARS += "DEBIAN_BUILD_DEPENDS ROS_DISTRO"

do_prepare_build() {
    rm -rf ${S}
    mkdir -p ${S}

    cp -r ${WORKDIR}/src ${S}/
    cp ${WORKDIR}/Cargo.toml ${S}/

    cp -r ${WORKDIR}/debian ${S}/
    sed -i "s|@ROS_DISTRO@|${ROS_DISTRO}|g" ${S}/debian/rules
    deb_add_changelog
}
