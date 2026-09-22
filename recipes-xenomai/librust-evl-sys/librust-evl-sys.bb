#
# librust-evl-sys: Raw Rust bindings for Xenomai 4 EVL
#
# Deploys the crate source into the system Cargo registry so downstream
# packages can build it like any Debian-packaged crate.
#
# SPDX-License-Identifier: MIT
#

inherit dpkg

DESCRIPTION = "Rust crate evl-sys for Xenomai EVL"

# 0.36.1 vendors EVL ABI 45, which is what libevl r59 and the
# v6.18.x-evl5 kernel implement.
CRATE_VERSION = "0.36.1"

SRC_URI = " \
    git://gitlab.com/Xenomai/xenomai4/evl-sys.git;protocol=https;branch=master \
    file://debian \
    file://build.rs \
"
SRCREV = "d1ac0537f5c140a1d021dd40c3f6815f8494250f"

DEPENDS = "libevl"

S = "${WORKDIR}/git"

DEBIAN_BUILD_DEPENDS = "debhelper-compat (= ${DEBIAN_COMPAT})"

TEMPLATE_FILES = "debian/control.tmpl"
TEMPLATE_VARS += "DEBIAN_BUILD_DEPENDS"

do_prepare_build() {
    # Upstream's build.rs writes its generated static-inline wrappers next
    # to the sources. Downstream builds see this crate as a read-only
    # registry directory, so use a version that keeps everything in OUT_DIR.
    cp ${WORKDIR}/build.rs ${S}/build.rs

    rm -rf ${S}/debian
    cp -r ${WORKDIR}/debian ${S}/
    chmod 0755 ${S}/debian/rules
    sed -i "s|@VERSION@|${CRATE_VERSION}|g" ${S}/debian/rules
    deb_add_changelog
}
