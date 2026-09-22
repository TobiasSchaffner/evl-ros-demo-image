#
# Copyright (c) Siemens AG, 2026
#
# Authors:
#  Tobias Schaffner <tobias.schaffner@siemens.com>
#
# SPDX-License-Identifier: MIT

inherit dpkg-raw

DESCRIPTION = "image customizations"

SRC_URI = "file://postinst \
           file://ethernet \
           file://dhclient.service"

DEBIAN_DEPENDS = "ifupdown, \
                  isc-dhcp-client, \
                  net-tools, \
                  iputils-ping"

DEBIAN_DEPENDS:debian-sid-ports = "ifupdown, \
                                   isc-dhcp-client, \
                                   net-tools, \
                                   iputils-ping, \
                                   ntpsec"

do_install() {
    install -v -d ${D}/etc/network/interfaces.d
    install -v -m 644 ${WORKDIR}/ethernet ${D}/etc/network/interfaces.d/

    install -v -d ${D}/etc/systemd/system/
    install -v -m 644 ${WORKDIR}/dhclient.service ${D}/etc/systemd/system/

    echo "${MACHINE_HOSTNAME}" > ${D}/etc/hostname
}
