#
# ISAR ROS Xenomai Image
#
# Xenomai 4 (EVL) real-time kernel plus ROS 2.
#
# SPDX-License-Identifier: MIT
#

require recipes-core/images/isar-image-base.bb

DESCRIPTION = "Xenomai EVL image with ROS 2"

IMAGE_INSTALL += "customizations sshd-regen-keys expand-on-first-boot"

# Xenomai / EVL real-time tools
IMAGE_INSTALL += "libevl-test"

# ROS 2 packages (from packages.ros.org, ROS_DISTRO set by the distro conf)
IMAGE_PREINSTALL += "\
    ros-${ROS_DISTRO}-ros-base \
    ros-${ROS_DISTRO}-example-interfaces \
    python3-rosdep \
    python3-colcon-common-extensions \
    "

# Common tooling
IMAGE_PREINSTALL += "\
    vim \
    openssh-server \
    bash-completion \
    less \
    net-tools \
    iputils-ping \
    isc-dhcp-client \
    ifupdown \
    systemd \
    dbus \
    ca-certificates \
    curl \
    build-essential \
    cmake \
    "

# no need for an SBOM here, it only costs build time
ROOTFS_FEATURES:remove = "generate-sbom"
