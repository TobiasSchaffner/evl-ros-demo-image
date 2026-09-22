#
# librust-revl: Safe Rust API for the Xenomai 4 EVL real-time core
#
# Deploys the revl crate source into the system Cargo registry, together
# with embedded-time, which revl exposes in its clock/timer API and which
# Ubuntu does not package.
#
# SPDX-License-Identifier: MIT
#

inherit dpkg

DESCRIPTION = "Safe Rust bindings for EVL"

CRATE_VERSION = "0.6.2"
EMBEDDED_TIME_VERSION = "0.12.1"

SRC_URI = " \
    git://gitlab.com/Xenomai/xenomai4/revl.git;protocol=https;branch=master \
    file://0001-timer-fix-wait-return-value-handling.patch \
    file://0002-timer-fix-itimerspec-conversion.patch \
    https://static.crates.io/crates/embedded-time/embedded-time-${EMBEDDED_TIME_VERSION}.crate;sha256sum=d7a4b4d10ac48d08bfe3db7688c402baadb244721f30a77ce360bd24c3dffe58 \
    file://debian \
"
SRCREV = "1d7713418d70ef2e90c96396a7a5c2467ab3ff24"

DEPENDS = "librust-evl-sys"

S = "${WORKDIR}/git"

DEBIAN_BUILD_DEPENDS = "debhelper-compat (= ${DEBIAN_COMPAT})"

TEMPLATE_FILES = "debian/control.tmpl"
TEMPLATE_VARS += "DEBIAN_BUILD_DEPENDS"

do_prepare_build() {
    # Resolve evl-sys from the system registry instead of git, and match
    # the version we actually ship (upstream still points at 0.32).
    sed -i 's|^evl-sys = .*|evl-sys = "0.36"|' ${S}/Cargo.toml
    rm -f ${S}/Cargo.lock

    tar -C ${S} -xzf ${WORKDIR}/embedded-time-${EMBEDDED_TIME_VERSION}.crate
    # embedded-time pins num 0.3; Ubuntu ships 0.4, which is compatible
    # for the traits it uses.
    sed -i 's/^version = "0.3.0"/version = "0.4"/' \
        ${S}/embedded-time-${EMBEDDED_TIME_VERSION}/Cargo.toml

    rm -rf ${S}/debian
    cp -r ${WORKDIR}/debian ${S}/
    chmod 0755 ${S}/debian/rules
    sed -i -e "s|@VERSION@|${CRATE_VERSION}|g" \
           -e "s|@ET_VERSION@|${EMBEDDED_TIME_VERSION}|g" ${S}/debian/rules
    deb_add_changelog
}
